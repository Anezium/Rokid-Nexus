package mobile

import (
	"context"
	"errors"
	"time"

	"github.com/beeper/line/pkg/e2ee"
	lineapi "github.com/beeper/line/pkg/line"
	qrcode "github.com/skip2/go-qrcode"
)

func (e *Engine) setLogin(status loginStatus) {
	e.mu.Lock()
	e.login = status
	e.mu.Unlock()
	e.changed()
}

func (e *Engine) StartLogin() error {
	e.mu.Lock()
	if e.closed || e.ctx.Err() != nil || e.loginCancel != nil || e.state != "signed_out" {
		e.mu.Unlock()
		return errors.New("login unavailable")
	}
	ctx, cancel := context.WithTimeout(e.ctx, 3*time.Minute)
	e.loginCancel = cancel
	e.login = loginStatus{Phase: "starting"}
	e.wg.Add(1)
	e.mu.Unlock()
	e.changed()
	go func() {
		defer e.wg.Done()
		defer cancel()
		e.op.Lock()
		defer e.op.Unlock()
		err := e.loginQR(ctx)
		e.mu.Lock()
		e.loginCancel = nil
		e.mu.Unlock()
		if err != nil {
			phase := "failed"
			if errors.Is(err, context.Canceled) {
				phase = "idle"
			}
			e.setLogin(loginStatus{Phase: phase})
		}
	}()
	return nil
}

func (e *Engine) AbortLogin() {
	e.mu.Lock()
	cancel := e.loginCancel
	e.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

func (e *Engine) QrPNG() ([]byte, error) {
	e.mu.Lock()
	status := e.login
	e.mu.Unlock()
	if status.Phase != "qr" || status.URL == "" {
		return nil, errors.New("QR unavailable")
	}
	return qrcode.Encode(status.URL, qrcode.Medium, 512)
}

func (e *Engine) loginQR(ctx context.Context) error {
	c := e.newClient("")
	session, qr, err := c.StartQRLogin(ctx)
	if err != nil {
		return err
	}
	defer c.LoginAttempt.Close()
	e.setLogin(loginStatus{Phase: "qr", URL: qr.CallbackURL})
	timeout := time.Duration(qr.LongPollingIntervalSeconds) * time.Second
	if timeout <= 0 || timeout > 150*time.Second {
		timeout = 150 * time.Second
	}
	for attempt := 0; ; attempt++ {
		err = c.CheckQRCodeVerifiedContext(ctx, session, timeout)
		if err == nil {
			break
		}
		if ctx.Err() != nil || !lineapi.IsQRLoginPollExpired(err) || attempt >= 2 {
			return err
		}
	}
	verified, err := c.VerifyQRCertificate(ctx, session, "")
	if err != nil {
		return err
	}
	if !verified {
		pin, err := c.CreatePinCode(ctx, session)
		if err != nil {
			return err
		}
		e.setLogin(loginStatus{Phase: "pin", PIN: pin})
		if err = c.CheckPinCodeVerifiedContext(ctx, session); err != nil {
			return err
		}
	}
	result, err := c.QRCodeLoginV2(ctx, session)
	if err != nil {
		return err
	}
	profile, err := c.GetProfileContext(ctx)
	if err != nil {
		return err
	}
	if profile.Mid == "" || (result.Mid != "" && result.Mid != profile.Mid) {
		return errors.New("account mismatch")
	}
	keys := map[string]string{}
	if result.EncryptedKeyChain != "" {
		mgr := e2ee.NewManagerWithRunner(result.Attempt.Runner)
		defer mgr.Close()
		identity, err := c.GetEncryptedIdentityV3()
		if err != nil {
			return err
		}
		if err = mgr.InitStorage(identity.WrappedNonce, identity.KDFParameter1, identity.KDFParameter2); err != nil {
			return err
		}
		keys, err = mgr.InitFromLoginKeyChain(result.E2EEPublicKey, result.EncryptedKeyChain)
		if err != nil {
			return err
		}
	}
	// A missing keychain must never silently turn a sealed account into plaintext.
	// The prototype only accepts QR logins that carry usable Letter Sealing keys.
	if len(keys) == 0 {
		return errors.New("Letter Sealing keychain required")
	}
	if ctx.Err() != nil {
		return ctx.Err()
	}
	// Chrome sessions suppress mobile notifications by default. Keep the phone
	// receiving them even after this short pairing session closes.
	disabled := false
	if err = c.UpdateSettingsAttributes2Context(ctx, 1, []int{lineapi.SettingsAttributeNotificationDisabledWithSub}, lineapi.Settings{NotificationDisabledWithSub: &disabled}); err != nil {
		return err
	}
	e.stored = storedSession{Token: result.AuthToken, Mid: profile.Mid, Keys: keys, Sequence: 1, Sends: map[string]sendRecord{}}
	if err = e.save(); err != nil {
		return err
	}
	e.setState("offline")
	e.setLogin(loginStatus{Phase: "linked"})
	return nil
}
