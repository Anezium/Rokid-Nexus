// Package mobile embeds LINE in the phone APK. No Matrix bridge or gateway is used.
package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/beeper/line/pkg/e2ee"
	lineapi "github.com/beeper/line/pkg/line"
)

type Listener interface{ Changed() }

type message struct {
	ID   string `json:"id"`
	Text string `json:"text"`
	Mine bool   `json:"mine"`
}

type chat struct {
	ID      string    `json:"id"`
	Title   string    `json:"title"`
	Group   bool      `json:"group"`
	History []message `json:"history"`
}

type sendRecord struct {
	Digest string `json:"digest"`
	State  string `json:"state"`
}

type storedSession struct {
	Token    string                `json:"token"`
	Mid      string                `json:"mid"`
	Keys     map[string]string     `json:"keys,omitempty"`
	Sequence int64                 `json:"sequence"`
	Sends    map[string]sendRecord `json:"sends"`
	Order    []string              `json:"order"`
}

type loginStatus struct {
	Phase string `json:"phase"`
	PIN   string `json:"pin,omitempty"`
}

// op serializes protocol and crypto calls; mu protects snapshots and callbacks.
// Cancel does not take op, so closing the last owner interrupts blocked network work.
type Engine struct {
	op              sync.Mutex
	mu              sync.Mutex
	ctx             context.Context
	cancel          context.CancelFunc
	wg              sync.WaitGroup
	file            *os.File
	dir             string
	client          *lineapi.Client
	crypto          *e2ee.Manager
	stored          storedSession
	state           string
	chats           []chat
	version         int64
	listener        Listener
	login           loginStatus
	loginCancel     context.CancelFunc
	closed          bool
	inboxEntries    int
	contactEntries  int
	excludedEntries int
}

func Open(dir string) (*Engine, error) {
	if !filepath.IsAbs(dir) {
		return nil, errors.New("absolute session directory required")
	}
	if err := os.MkdirAll(dir, 0700); err != nil {
		return nil, errors.New("session unavailable")
	}
	file, err := os.OpenFile(filepath.Join(dir, "session.lock"), os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return nil, errors.New("session unavailable")
	}
	if err = lockSession(file); err != nil {
		file.Close()
		return nil, errors.New("session already owned")
	}
	ctx, cancel := context.WithCancel(context.Background())
	e := &Engine{ctx: ctx, cancel: cancel, file: file, dir: dir, state: "signed_out", chats: []chat{}, login: loginStatus{Phase: "idle"}}
	data, err := os.ReadFile(filepath.Join(dir, "session.json"))
	if err == nil {
		if len(data) > 256*1024 || json.Unmarshal(data, &e.stored) != nil || e.stored.Token == "" || e.stored.Mid == "" {
			cancel()
			file.Close()
			return nil, errors.New("saved session invalid; forget it on the phone")
		}
		e.state = "offline"
	} else if !os.IsNotExist(err) {
		cancel()
		file.Close()
		return nil, errors.New("session unavailable")
	}
	if e.stored.Sends == nil {
		e.stored.Sends = make(map[string]sendRecord)
	}
	return e, nil
}

func (e *Engine) SetListener(listener Listener) { e.mu.Lock(); e.listener = listener; e.mu.Unlock() }

func (e *Engine) changed() {
	e.mu.Lock()
	e.version++
	listener := e.listener
	closed := e.closed
	e.mu.Unlock()
	if listener != nil && !closed {
		listener.Changed()
	}
}

func (e *Engine) setState(state string) { e.mu.Lock(); e.state = state; e.mu.Unlock(); e.changed() }

func encode(value any) string { data, _ := json.Marshal(value); return string(data) }

func (e *Engine) Snapshot() string {
	e.mu.Lock()
	defer e.mu.Unlock()
	return encode(struct {
		State           string `json:"state"`
		Version         int64  `json:"version"`
		Chats           []chat `json:"chats"`
		InboxEntries    int    `json:"inboxEntries"`
		ContactEntries  int    `json:"contactEntries"`
		ExcludedEntries int    `json:"excludedEntries"`
	}{e.state, e.version, e.chats, e.inboxEntries, e.contactEntries, e.excludedEntries})
}

func (e *Engine) LoginStatus() string { e.mu.Lock(); defer e.mu.Unlock(); return encode(e.login) }

// requestTransport binds older upstream methods without a context argument to
// the current owner lease.
type requestTransport struct{ ctx context.Context }

func (t requestTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	ctx, cancel := context.WithCancel(req.Context())
	stop := context.AfterFunc(t.ctx, cancel)
	resp, err := http.DefaultTransport.RoundTrip(req.Clone(ctx))
	if err != nil {
		stop()
		cancel()
		return nil, err
	}
	resp.Body = &ownedBody{ReadCloser: resp.Body, stop: func() { stop(); cancel() }}
	return resp, nil
}

type ownedBody struct {
	io.ReadCloser
	stop func()
}

func (b *ownedBody) Close() error { defer b.stop(); return b.ReadCloser.Close() }

func (e *Engine) newClient(token string) *lineapi.Client {
	c := lineapi.NewClient(token)
	c.HTTPClient = &http.Client{Timeout: 6 * time.Second, Transport: requestTransport{e.ctx}}
	return c
}

func (e *Engine) save() error {
	data, err := json.Marshal(e.stored)
	if err != nil {
		return err
	}
	path := filepath.Join(e.dir, "session.json")
	f, err := os.OpenFile(path+".tmp", os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0600)
	if err != nil {
		return err
	}
	_, err = f.Write(data)
	if err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err == nil {
		err = closeErr
	}
	if err != nil {
		return err
	}
	return os.Rename(path+".tmp", path)
}

func (e *Engine) Connect() error {
	e.op.Lock()
	defer e.op.Unlock()
	if e.ctx.Err() != nil {
		return errors.New("session closed")
	}
	if e.client != nil {
		return nil
	}
	if e.stored.Token == "" {
		return nil
	}
	e.setState("connecting")
	c := e.newClient(e.stored.Token)
	profile, err := c.GetProfileContext(e.ctx)
	if err != nil || profile.Mid != e.stored.Mid {
		e.setState("failed")
		return errors.New("LINE sign-in unavailable; check the phone")
	}
	if len(e.stored.Keys) > 0 {
		mgr, err := e2ee.NewManager()
		if err != nil {
			e.setState("failed")
			return errors.New("Letter Sealing unavailable")
		}
		if err = mgr.LoadMyKeyFromExportedMap(e.stored.Keys); err != nil {
			mgr.Close()
			e.setState("failed")
			return errors.New("Letter Sealing keys unavailable; sign in again")
		}
		e.crypto = mgr
	}
	e.client = c
	revision, err := c.GetLastOpRevisionContext(e.ctx)
	if err == nil {
		err = e.loadChats()
	}
	if err != nil {
		e.client = nil
		if e.crypto != nil {
			e.crypto.Close()
			e.crypto = nil
		}
		e.setState("failed")
		return errors.New("LINE conversations unavailable")
	}
	e.setState("ready")
	e.wg.Add(1)
	go func() { defer e.wg.Done(); e.listen(c, revision) }()
	return nil
}

func (e *Engine) Cancel() { e.cancel() }

func (e *Engine) Close() {
	e.mu.Lock()
	e.closed = true
	e.listener = nil
	e.mu.Unlock()
	e.cancel()
	e.wg.Wait()
	e.op.Lock()
	defer e.op.Unlock()
	if e.crypto != nil {
		e.crypto.Close()
		e.crypto = nil
	}
	if e.file != nil {
		e.file.Close()
		e.file = nil
	}
}

// Forget only removes this APK's saved session; it does not claim remote logout.
func Forget(dir string) error {
	if !filepath.IsAbs(dir) {
		return errors.New("absolute session directory required")
	}
	if err := os.MkdirAll(dir, 0700); err != nil {
		return errors.New("session unavailable")
	}
	file, err := os.OpenFile(filepath.Join(dir, "session.lock"), os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return errors.New("session unavailable")
	}
	defer file.Close()
	if lockSession(file) != nil {
		return errors.New("session already owned")
	}
	for _, name := range []string{"session.json", "session.json.tmp"} {
		if err := os.Remove(filepath.Join(dir, name)); err != nil && !os.IsNotExist(err) {
			return errors.New("could not forget session")
		}
	}
	return nil
}
