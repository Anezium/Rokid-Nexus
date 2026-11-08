package mobile

import (
	"context"
	"errors"
	"strings"
	"time"

	"github.com/beeper/line/pkg/e2ee"
	lineapi "github.com/beeper/line/pkg/line"
)

func (e *Engine) setLogin(status loginStatus) {
	e.mu.Lock()
	e.login = status
	e.mu.Unlock()
	e.changed()
}

func (e *Engine) StartLogin(email, password string) error {
	email = strings.TrimSpace(email)
	if email == "" || password == "" || len(email) > 254 || len(password) > 1024 {
		return errors.New("email and password required")
	}
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
		err := e.loginEmail(ctx, email, password)
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

type emailLoginAPI interface {
	LoginContext(context.Context, string, string, string) (*lineapi.LoginResult, error)
	WaitForLogin(string, bool) (*lineapi.LoginResult, error)
}

// Passwords exist only for the initial request; neither certificates nor passwords
// are retained for unattended reauthentication.
func approveEmailLogin(ctx context.Context, c emailLoginAPI, email, password string, update func(loginStatus)) (*lineapi.LoginResult, error) {
	result, err := c.LoginContext(ctx, email, password, "")
	if err != nil {
		return nil, err
	}
	if result == nil || result.NoE2EE {
		return nil, errors.New("Letter Sealing required")
	}
	if result.AuthToken != "" {
		return result, nil
	}
	if result.Verifier == "" || (result.Type != 0 && result.Type != 3) {
		return nil, errors.New("unsupported login challenge")
	}
	status := loginStatus{Phase: "approve"}
	status.PIN = result.PinCode
	if status.PIN == "" {
		status.PIN = result.Pin
	}
	if status.PIN != "" {
		status.Phase = "pin"
	}
	update(status)
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	result, err = c.WaitForLogin(result.Verifier, false)
	if err != nil {
		return nil, err
	}
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	if result == nil || result.NoE2EE || result.AuthToken == "" {
		return nil, errors.New("login not authenticated")
	}
	return result, nil
}

func (e *Engine) loginEmail(ctx context.Context, email, password string) error {
	c := e.newClient("")
	defer func() {
		if c.LoginAttempt != nil {
			c.LoginAttempt.Close()
		}
	}()
	result, err := approveEmailLogin(ctx, c, email, password, e.setLogin)
	if err != nil {
		return err
	}
	c.AccessToken = result.AuthToken
	profile, err := c.GetProfileContext(ctx)
	if err != nil {
		return err
	}
	if profile.Mid == "" || (result.Mid != "" && result.Mid != profile.Mid) {
		return errors.New("account mismatch")
	}
	keys := map[string]string{}
	if result.EncryptedKeyChain != "" && result.Attempt != nil {
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
	// The prototype only accepts logins that carry usable Letter Sealing keys.
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
	e.setState("standby")
	e.setLogin(loginStatus{Phase: "linked"})
	return nil
}
