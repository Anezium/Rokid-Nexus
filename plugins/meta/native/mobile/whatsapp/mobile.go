// Package whatsapp is the gomobile binding surface for WhatsApp. It runs inside the
// phone APK; no listener, bearer token, gateway or glasses-side Go process exists.
package whatsapp

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	engine "github.com/Anezium/Rokid-Nexus/plugins/meta/native/internal/whatsappengine"
	"go.mau.fi/whatsmeow/proto/waE2E"
)

const maxImageBytes = 8 << 20

type Engine struct {
	ctx    context.Context
	mu     sync.Mutex
	wa     engine.Backend
	outbox *engine.Outbox
	lock   *os.File
	cancel context.CancelFunc
}

func Open(directory string) (*Engine, error) {
	if !filepath.IsAbs(directory) {
		return nil, errors.New("private absolute directory required")
	}
	if err := os.MkdirAll(directory, 0700); err != nil {
		return nil, errors.New("storage unavailable")
	}
	if err := os.Chmod(directory, 0700); err != nil {
		return nil, errors.New("storage permissions unavailable")
	}
	lock, err := os.OpenFile(filepath.Join(directory, "session.lock"), os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return nil, errors.New("session lock unavailable")
	}
	if err = lockSession(lock); err != nil {
		lock.Close()
		return nil, errors.New("session already open")
	}
	ctx, cancel := context.WithCancel(context.Background())
	outbox, err := engine.OpenOutbox(filepath.Join(directory, "outbox.sqlite"))
	if err != nil {
		cancel()
		lock.Close()
		return nil, errors.New("journal unavailable")
	}
	outbox.RetainExpired = true
	wa, err := engine.OpenWhatsApp(ctx, filepath.Join(directory, "session.sqlite"))
	if err != nil {
		cancel()
		outbox.Close()
		lock.Close()
		return nil, errors.New("connection unavailable")
	}
	return &Engine{ctx: ctx, wa: wa, outbox: outbox, lock: lock, cancel: cancel}, nil
}
func encode(v any) (string, error) { b, err := json.Marshal(v); return string(b), err }
func (e *Engine) Status() (string, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.wa == nil {
		return "", errors.New("closed")
	}
	return encode(e.wa.Status())
}
func (e *Engine) Contacts() (string, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.wa == nil {
		return "", errors.New("closed")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	contacts, err := e.wa.Contacts(ctx)
	if err != nil {
		return "", errors.New("contacts unavailable")
	}
	return encode(contacts)
}

// Thumbnail returns the stored thumbnail a message's preview names, from local storage
// only. Its bytes are never logged.
func (e *Engine) Thumbnail(preview string) ([]byte, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.wa == nil {
		return nil, errors.New("closed")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	return e.wa.Thumbnail(ctx, preview)
}

// Chats lists stored conversations with up to perChat recent messages each, from local
// storage only.
func (e *Engine) Chats(perChat int64) (string, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.wa == nil {
		return "", errors.New("closed")
	}
	if perChat < 1 || perChat > engine.MaxHistory {
		return "", errors.New("invalid message count")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	chats, err := e.wa.Chats(ctx, int(perChat))
	if err != nil {
		return "", errors.New("chats unavailable")
	}
	return encode(chats)
}

// RequestHistory asks the main phone for older messages of one conversation. The
// answer arrives asynchronously and shows up in a later Chats call.
func (e *Engine) RequestHistory(chat string, count int64) error {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.wa == nil {
		return errors.New("closed")
	}
	if !e.wa.Status().Authenticated() {
		return errors.New("not connected")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	if err := e.wa.RequestHistory(ctx, chat, int(count)); err != nil {
		return errors.New("history request refused")
	}
	return nil
}

func (e *Engine) AwaitLogin(timeoutMs int64) (string, error) {
	if timeoutMs <= 0 || timeoutMs > 8000 {
		return "", errors.New("invalid login timeout")
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.wa == nil {
		return "", errors.New("closed")
	}
	e.wa.AwaitLogin(time.Duration(timeoutMs) * time.Millisecond)
	return encode(e.wa.Status())
}

func (e *Engine) Cancel() {
	if e.cancel != nil {
		e.cancel()
	}
}
func (e *Engine) AbortLink() {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.wa != nil {
		e.wa.AbortLink()
	}
}
func (e *Engine) StartLink(method, phone string) (string, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.wa == nil {
		return "", errors.New("closed")
	}
	link, err := e.wa.StartLink(context.Background(), method, phone)
	if err != nil {
		return "", errors.New("pairing refused")
	}
	return encode(link)
}
func (e *Engine) Link(session string) (string, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.wa == nil {
		return "", errors.New("closed")
	}
	link, err := e.wa.PollLink(session)
	if err != nil {
		return "", errors.New("pairing unavailable")
	}
	return encode(link)
}
func (e *Engine) Logout() error {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.wa == nil {
		return errors.New("closed")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	if err := e.wa.Logout(ctx); err != nil {
		return errors.New("unlink refused; use WhatsApp linked devices")
	}
	return nil
}

// Send is called only for a freshly confirmed tap. Unknown outcomes are looked
// up, never submitted again. The durable journal gates dispatch across restarts.
func (e *Engine) Send(id, to, expectedName, text string) (string, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.wa == nil {
		return "", errors.New("closed")
	}
	if len(id) < 16 || len(id) > 128 || strings.TrimSpace(text) == "" || !utf8.ValidString(text) {
		return e.notSent(id, "invalid_draft")
	}
	units := 0
	for _, c := range text {
		units++
		if c > 0xffff {
			units++
		}
	}
	if units > 1000 {
		return e.notSent(id, "text_too_long")
	}
	return e.deliver(id, to, expectedName, text, nil, func(ctx context.Context, waID string) (int64, error) {
		return e.wa.Send(ctx, to, text, waID)
	})
}

// SendImage sends one owner-confirmed photo to a listed one-to-one conversation. The
// upload happens before admission; the journal records a digest instead of the image.
func (e *Engine) SendImage(id, to, expectedName string, data []byte) (string, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.wa == nil {
		return "", errors.New("closed")
	}
	if len(id) < 16 || len(id) > 128 || len(data) == 0 || len(data) > maxImageBytes {
		return e.notSent(id, "invalid_image")
	}
	digest := sha256.Sum256(data)
	var prepared *waE2E.Message
	prepare := func(ctx context.Context) error {
		var err error
		prepared, err = e.wa.PrepareImage(ctx, data)
		return err
	}
	return e.deliver(id, to, expectedName, "[Photo] sha256:"+hex.EncodeToString(digest[:]), prepare,
		func(ctx context.Context, waID string) (int64, error) {
			return e.wa.SendPrepared(ctx, to, prepared, waID)
		})
}

// deliver checks the recipient, runs an optional message-free preparation, then admits
// the send in the durable journal and dispatches it at most once.
func (e *Engine) deliver(id, to, expectedName, payload string, prepare func(context.Context) error,
	send func(context.Context, string) (int64, error)) (string, error) {
	s := e.wa.Status()
	if !s.Authenticated() {
		return e.notSent(id, "not_connected")
	}
	deadline := time.Now().Add(10 * time.Second)
	if prepare != nil {
		deadline = deadline.Add(20 * time.Second)
	}
	ctx, cancel := context.WithDeadline(e.ctx, deadline)
	defer cancel()
	contacts, err := e.wa.Contacts(ctx)
	if err != nil {
		return e.notSent(id, "contacts_unavailable")
	}
	found := false
	for _, contact := range contacts {
		if contact.JID == to && contact.Name == expectedName && !contact.IsGroup {
			found = true
			break
		}
	}
	if !found {
		// A conversation listed from stored messages counts under the name it was shown with.
		if chats, err := e.wa.Chats(ctx, 1); err == nil {
			for _, chat := range chats {
				if chat.JID == to && chat.Name == expectedName && chat.Supported {
					found = true
					break
				}
			}
		}
	}
	if !found {
		return e.notSent(id, "recipient_changed")
	}
	if prepare != nil {
		// An id already in the journal reports its outcome instead of uploading again.
		if out, err := e.lookup(id); err == nil && out.State != "not_found" {
			return encode(out)
		}
		if err := prepare(ctx); err != nil {
			return e.notSent(id, "upload_failed")
		}
	}
	scope := e.wa.AccountScope()
	intent, err := e.outbox.CreateIntent(ctx, scope, id, to)
	if err != nil {
		return e.notSent(id, "authority_unavailable")
	}
	out, dispatch, err := e.outbox.Admit(ctx, scope, id, intent.Token, to, payload, e.wa.MessageID())
	if err != nil {
		return e.notSent(id, "admission_refused")
	}
	if dispatch {
		dispatchContext, stopDispatch := context.WithDeadline(context.Background(), deadline)
		at, sendErr := send(dispatchContext, out.WAMessageID)
		stopDispatch()
		if sendErr == nil {
			out.State = "acked"
			out.ServerAt = at
		}
		finish, finishCancel := context.WithTimeout(context.Background(), 2*time.Second)
		err = e.outbox.Finish(finish, scope, id, out)
		finishCancel()
		if err != nil {
			out.State = "unknown"
			out.ServerAt = 0
		}
	}
	return encode(out)
}
func (e *Engine) Lookup(id string) (string, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.wa == nil {
		return "", errors.New("closed")
	}
	out, err := e.lookup(id)
	if err != nil {
		return "", errors.New("unconfirmed; check WhatsApp")
	}
	return encode(out)
}
func (e *Engine) lookup(id string) (engine.Outcome, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	out, err := e.outbox.Lookup(ctx, e.wa.AccountScope(), id)
	if errors.Is(err, sql.ErrNoRows) {
		admitted, lookupErr := e.outbox.HasAdmission(ctx, id)
		if lookupErr != nil {
			return engine.Outcome{}, lookupErr
		}
		if admitted {
			return engine.Outcome{State: "unknown"}, nil
		}
		return engine.Outcome{State: "not_found"}, nil
	}
	return out, err
}

func (e *Engine) notSent(id, reason string) (string, error) {
	if len(id) >= 16 && len(id) <= 128 {
		out, err := e.lookup(id)
		if err != nil {
			return "", errors.New("send outcome unavailable")
		}
		if out.State != "not_found" {
			return encode(engine.Outcome{State: "unknown"})
		}
	}
	return encode(engine.Outcome{State: "not_sent", Reason: reason})
}
func (e *Engine) Close() {
	e.Cancel()
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.wa == nil {
		return
	}
	e.wa.Close()
	e.outbox.Close()
	e.lock.Close()
	e.wa = nil
}
