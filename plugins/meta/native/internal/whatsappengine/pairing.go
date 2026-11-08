package whatsappengine

import (
	"context"
	"errors"
	"strings"
	"time"

	"go.mau.fi/whatsmeow"
)

func normalizePhone(raw string) (string, error) {
	raw = strings.TrimPrefix(strings.TrimSpace(raw), "+")
	var digits strings.Builder
	for _, value := range raw {
		switch {
		case value >= '0' && value <= '9':
			digits.WriteRune(value)
		case value == ' ' || value == '-' || value == '(' || value == ')' || value == '\t':
		default:
			return "", errors.New("invalid_phone")
		}
	}
	phone := digits.String()
	if len(phone) < 7 || len(phone) > 15 || phone[0] == '0' {
		return "", errors.New("invalid_phone")
	}
	return phone, nil
}

func (w *WhatsApp) StartLink(_ context.Context, method, phone string) (Link, error) {
	w.operation.Lock()
	defer w.operation.Unlock()
	if method != "qr" && method != "code" {
		return Link{}, errors.New("invalid_method")
	}
	if method == "code" {
		var err error
		phone, err = normalizePhone(phone)
		if err != nil {
			return Link{}, err
		}
	}
	w.mu.Lock()
	busy := w.account != "" || ((w.link.Status == "starting" || w.link.Status == "pairing") && w.until > time.Now().Unix())
	loggedOut := w.blocker == "logged_out"
	w.mu.Unlock()
	if busy {
		return Link{}, errors.New("link_busy")
	}
	w.stopPairing()
	if w.ctx.Err() != nil {
		return Link{}, errors.New("closed")
	}
	if loggedOut {
		w.connection().Disconnect()
		w.installClient(w.store.NewDevice())
	}
	cn := w.connection()
	if cn.IsLoggedIn() {
		return Link{}, errors.New("link_busy")
	}
	if cn.IsConnected() {
		cn.Disconnect()
	}
	challenge, cancel := context.WithTimeout(w.ctx, 160*time.Second)
	transport, transportCancel := context.WithCancel(w.ctx)
	qr, err := cn.GetQRChannel(challenge)
	if err != nil {
		cancel()
		transportCancel()
		return Link{}, err
	}
	session := randomID()
	done := make(chan struct{})
	w.mu.Lock()
	w.cancel, w.linkCancel, w.pairDone = cancel, transportCancel, done
	w.linkAuthenticated = false
	w.until = time.Now().Add(160 * time.Second).Unix()
	w.link = Link{Session: session, Status: "pairing", ExpiresAt: w.until}
	initial := w.link
	w.mu.Unlock()
	go func() { defer close(done); w.runPairing(cn, transport, challenge, cancel, session, method, phone, qr) }()
	return initial, nil
}

func (w *WhatsApp) runPairing(cn connection, transport, challenge context.Context, cancel context.CancelFunc, session, method, phone string, qr <-chan whatsmeow.QRChannelItem) {
	defer cancel()
	fail := func(state string) {
		if w.finishPairing(session, state) {
			cn.Disconnect()
		}
	}
	if err := cn.ConnectContext(transport); err != nil {
		fail("error")
		return
	}
	codeRequested := false
	for {
		select {
		case <-challenge.Done():
			fail("timeout")
			return
		case item, ok := <-qr:
			if !ok {
				fail("error")
				return
			}
			switch item.Event {
			case "code":
				if method == "qr" {
					w.setLink(session, item.Code, "", time.Now().Add(item.Timeout).Unix())
				} else if !codeRequested {
					codeRequested = true
					code, err := cn.PairPhone(challenge, phone, true, whatsmeow.PairClientChrome, "Chrome (Linux)")
					phone = ""
					if err != nil {
						fail("error")
						return
					}
					w.setLink(session, "", code, 0)
				}
			case "success":
				if !w.finishPairing(session, "success") {
					cn.Disconnect()
				}
				return
			case "passkey-request", "passkey-confirmation":
				fail("passkey_required")
				return
			case "timeout":
				fail("timeout")
				return
			default:
				fail("error")
				return
			}
		}
	}
}

func (w *WhatsApp) finishPairing(session, state string) bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.link.Session != session {
		return false
	}
	if w.link.Status == "success" {
		return state == "success"
	}
	if w.link.Status != "pairing" && w.link.Status != "starting" {
		return w.link.Status == state
	}
	w.link.Status, w.link.QR, w.link.Code = state, "", ""
	w.notifyLocked()
	return true
}

func (w *WhatsApp) setLink(session, qr, code string, expires int64) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.link.Session != session || w.link.Status != "pairing" {
		return
	}
	w.link.QR, w.link.Code = qr, code
	if expires > 0 {
		if expires > w.until {
			expires = w.until
		}
		w.link.ExpiresAt = expires
	}
}

func (w *WhatsApp) PollLink(session string) (Link, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if session == "" || session != w.link.Session {
		return Link{}, errors.New("invalid_session")
	}
	result := w.link
	if result.ExpiresAt <= time.Now().Unix() {
		result.QR, result.Code = "", ""
	}
	return result, nil
}
