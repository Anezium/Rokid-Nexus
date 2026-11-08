// Package instagram is the gomobile binding surface for Instagram direct messages. It runs
// inside the phone APK only while the owner keeps the plugin's settings visible;
// no listener, gateway, poller or glasses-side Go process exists.
package instagram

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"github.com/rs/zerolog"
	"go.mau.fi/mautrix-meta/pkg/instameow"
	"go.mau.fi/mautrix-meta/pkg/instameow/slidetypes"
	"go.mau.fi/mautrix-meta/pkg/messagix/cookies"
	"go.mau.fi/mautrix-meta/pkg/messagix/types"
)

const (
	sessionFile      = "native-session.json"
	bindingFile      = "account-binding.json"
	installationFile = "installation.json"
	sendsDirectory   = "sends"
)

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

func (e *Engine) hasSession() bool {
	_, err := os.Stat(filepath.Join(e.dir, sessionFile))
	return err == nil
}

func (e *Engine) clientParams(handler func(context.Context, slidetypes.ClientEvent) error) instameow.ClientParams {
	logger := zerolog.New(io.Discard).Level(zerolog.Disabled)
	if handler == nil {
		handler = func(context.Context, slidetypes.ClientEvent) error { return nil }
	}
	jar := &cookies.Cookies{Platform: types.Instagram}
	jar.UpdateValues(nil)
	return instameow.ClientParams{Cookies: jar, Log: logger, DisableTyping: true, EventHandler: handler}
}

// storedSession loads the native session and checks it still belongs to the bound account.
func (e *Engine) storedSession() (*types.InstagramNativeSession, string, error) {
	var session types.InstagramNativeSession
	var binding string
	if readPrivate(filepath.Join(e.dir, sessionFile), &session) != nil || readPrivate(filepath.Join(e.dir, bindingFile), &binding) != nil {
		return nil, "", errors.New("no session")
	}
	if session.Username == "" || session.UserID == "" || accountBinding(session.Username) != binding {
		return nil, "", errors.New("session does not match the bound account")
	}
	colon := strings.LastIndex(session.Authorization, ":")
	if colon < 0 {
		return nil, "", errors.New("session authorization invalid")
	}
	decoded, err := base64.StdEncoding.DecodeString(session.Authorization[colon+1:])
	if err != nil {
		decoded, err = base64.RawStdEncoding.DecodeString(session.Authorization[colon+1:])
	}
	var auth struct {
		SessionID string `json:"sessionid"`
		UserID    string `json:"ds_user_id"`
	}
	if err != nil || json.Unmarshal(decoded, &auth) != nil || auth.SessionID == "" || auth.UserID != session.UserID {
		return nil, "", errors.New("session authorization invalid")
	}
	clear(decoded)
	return &session, auth.SessionID, nil
}

func sessionCookies(session *types.InstagramNativeSession, sessionID string) (*cookies.Cookies, error) {
	var csrf [32]byte
	if _, err := rand.Read(csrf[:]); err != nil {
		return nil, err
	}
	jar := &cookies.Cookies{Platform: types.Instagram}
	jar.UpdateValues(map[cookies.MetaCookieName]string{
		cookies.IGCookieSessionID: sessionID, cookies.IGCookieDSUserID: session.UserID,
		cookies.IGCookieCSRFToken: hex.EncodeToString(csrf[:]),
		cookies.IGCookieDeviceID:  strings.ToUpper(session.Device.DeviceID),
		cookies.IGCookieMachineID: session.Device.MachineID,
		cookies.IGCookieRUR:       session.RUR, cookies.IGCookieSHBID: session.SHBID, cookies.IGCookieSHBTS: session.SHBTS,
	})
	return jar, nil
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
	if session, _, err := e.storedSession(); err == nil {
		out.Account = true
		out.Username = session.Username
	}
	if e.conn != nil {
		out.Connected = e.conn.isConnected()
	}
	return encode(out)
}

// Forget deletes this phone's copy of the session. Instagram still lists the
// login until the owner removes it from the account's login activity.
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
