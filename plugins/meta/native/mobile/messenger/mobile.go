// Package messenger is the gomobile binding surface for Messenger, signed in through
// the native Messenger Lite flow. It runs inside the phone APK only while the owner
// keeps the service page visible; no listener, gateway, poller or glasses-side Go
// process exists. End-to-end encrypted chats are not handled yet.
package messenger

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"sync"

	"github.com/rs/zerolog"
	"go.mau.fi/mautrix-meta/pkg/messagix"
	"go.mau.fi/mautrix-meta/pkg/messagix/cookies"
	"go.mau.fi/mautrix-meta/pkg/messagix/types"
)

const (
	sessionFile    = "session.json"
	bindingFile    = "account-binding.json"
	sendsDirectory = "sends"
)

// storedSession is everything a later Connect needs; credentials are never part of it.
type storedSession struct {
	Cookies *cookies.Cookies     `json:"cookies"`
	Native  *types.NativeSession `json:"native"`
	FBID    int64                `json:"fbid"`
	Name    string               `json:"name"`
}

type Engine struct {
	dir    string
	lock   *os.File
	ctx    context.Context
	cancel context.CancelFunc
	sendMu sync.Mutex

	mu         sync.Mutex
	closed     bool
	login      *loginRun
	loginState string
	conn       *connection
	connState  string
	// historyLimit is how many recent messages Chats returns per conversation.
	historyLimit int
}

func Open(directory string) (*Engine, error) {
	if !filepath.IsAbs(directory) {
		return nil, errors.New("private absolute directory required")
	}
	if err := os.MkdirAll(filepath.Join(directory, sendsDirectory), 0700); err != nil {
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
	// Upstream logs may contain identifiers, so every logger is discarded.
	zerolog.SetGlobalLevel(zerolog.Disabled)
	zerolog.DefaultContextLogger = nil
	ctx, cancel := context.WithCancel(context.Background())
	return &Engine{dir: directory, lock: lock, ctx: ctx, cancel: cancel, loginState: "idle", connState: "disconnected", historyLimit: 30}, nil
}

func discardLogger() zerolog.Logger { return zerolog.New(io.Discard).Level(zerolog.Disabled) }

func newClient(jar *cookies.Cookies) *messagix.Client {
	return messagix.NewClient(jar, discardLogger(), &messagix.Config{})
}

func readPrivate(path string, value any) error {
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	return json.Unmarshal(data, value)
}

// writePrivate replaces a file atomically so a crash never leaves half a session.
func writePrivate(path string, value any) error {
	data, err := json.Marshal(value)
	if err != nil {
		return err
	}
	temporary := path + ".tmp"
	file, err := os.OpenFile(temporary, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	_, err = file.Write(data)
	if err == nil {
		err = file.Sync()
	}
	if closeErr := file.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		os.Remove(temporary)
		return err
	}
	return os.Rename(temporary, path)
}

func encode(v any) string { b, _ := json.Marshal(v); return string(b) }

func accountBinding(fbid int64) string {
	sum := sha256.Sum256([]byte(strconv.FormatInt(fbid, 10)))
	return hex.EncodeToString(sum[:])
}

func (e *Engine) hasSession() bool {
	_, err := os.Stat(filepath.Join(e.dir, sessionFile))
	return err == nil
}

// loadSession returns the stored session only when it is complete and still belongs
// to the bound account.
func (e *Engine) loadSession() (*storedSession, error) {
	var session storedSession
	var binding string
	if readPrivate(filepath.Join(e.dir, sessionFile), &session) != nil || readPrivate(filepath.Join(e.dir, bindingFile), &binding) != nil {
		return nil, errors.New("no session")
	}
	if session.Cookies != nil {
		// The cookie jar does not serialize its platform, and the user id depends on it.
		session.Cookies.Platform = types.MessengerLiteAndroid
	}
	if session.Cookies == nil || session.Native == nil || session.Native.AccessToken == "" || session.FBID <= 0 ||
		session.Cookies.GetUserID() != session.FBID || accountBinding(session.FBID) != binding {
		return nil, errors.New("session does not match the bound account")
	}
	return &session, nil
}

type status struct {
	Account   bool   `json:"account"`
	Username  string `json:"username,omitempty"`
	Login     string `json:"login"`
	Connected bool   `json:"connected"`
	Link      string `json:"link"`
}

// Status never blocks on the network.
func (e *Engine) Status() string {
	e.mu.Lock()
	defer e.mu.Unlock()
	out := status{Login: e.loginState, Link: e.connState}
	if session, err := e.loadSession(); err == nil {
		out.Account = true
		out.Username = session.Name
	}
	if e.conn != nil {
		out.Connected = e.conn.isConnected()
	}
	return encode(out)
}

// Forget deletes this phone's copy of the session. Facebook still lists the login
// until the owner removes it from the account's login activity.
func (e *Engine) Forget() error {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.login != nil || e.conn != nil {
		return errors.New("busy")
	}
	for _, name := range []string{sessionFile, bindingFile} {
		if err := os.Remove(filepath.Join(e.dir, name)); err != nil && !errors.Is(err, os.ErrNotExist) {
			return errors.New("forget failed")
		}
	}
	e.loginState = "idle"
	return nil
}

func (e *Engine) Close() {
	e.AbortLogin()
	e.Disconnect()
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.closed {
		return
	}
	e.closed = true
	e.cancel()
	e.lock.Close()
}
