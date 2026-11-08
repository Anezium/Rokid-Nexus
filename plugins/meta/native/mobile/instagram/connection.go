package instagram

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"sync/atomic"
	"time"

	"go.mau.fi/mautrix-meta/pkg/instameow"
	"go.mau.fi/mautrix-meta/pkg/instameow/slidetypes"
	"go.mau.fi/mautrix-meta/pkg/messagix/types"
)

type connection struct {
	client    *instameow.Client
	session   *types.InstagramNativeSession
	mailbox   *slidetypes.Mailbox
	boundary  *sendBoundary
	cancel    context.CancelFunc
	done      chan struct{}
	connected atomic.Bool
}

func (c *connection) isConnected() bool {
	select {
	case <-c.done:
		return false
	default:
		return c.connected.Load()
	}
}

// sendBoundary lets a send mutation cross the transport only once per admitted
// send, so an upstream HTTP retry can never duplicate a message.
type sendBoundary struct {
	base  http.RoundTripper
	armed atomic.Bool
}

func isSendMutation(name string) bool {
	return name == "IGDirectTextSendMutation" || name == "IGDirectMediaSendMutation"
}

func (b *sendBoundary) RoundTrip(request *http.Request) (*http.Response, error) {
	if isSendMutation(request.Header.Get("x-fb-friendly-name")) && !b.armed.Swap(false) {
		if request.Body != nil {
			request.Body.Close()
		}
		return &http.Response{StatusCode: http.StatusBadRequest, Header: make(http.Header),
			Body: io.NopCloser(strings.NewReader(`{"errors":[{"message":"local duplicate blocked"}]}`)), Request: request}, nil
	}
	return b.base.RoundTrip(request)
}

// Connect restores the stored session, verifies the server identity and opens the
// messaging socket. Mailbox contents stay in memory only for the self thread lookup.
func (e *Engine) Connect(timeoutMs int64) (string, error) {
	if timeoutMs < 1000 || timeoutMs > 60000 {
		return "", errors.New("invalid timeout")
	}
	e.mu.Lock()
	if e.closed || e.login != nil || e.conn != nil {
		e.mu.Unlock()
		return "", errors.New("busy")
	}
	session, sessionID, err := e.storedSession()
	if err != nil {
		e.mu.Unlock()
		return "", errors.New("no session")
	}
	jar, err := sessionCookies(session, sessionID)
	if err != nil {
		e.mu.Unlock()
		return "", errors.New("connection unavailable")
	}
	ctx, cancel := context.WithCancel(e.ctx)
	conn := &connection{session: session, cancel: cancel, done: make(chan struct{})}
	authFailed := make(chan struct{}, 1)
	params := e.clientParams(func(_ context.Context, event slidetypes.ClientEvent) error {
		switch event.(type) {
		case *slidetypes.Connected:
			conn.connected.Store(true)
		case *slidetypes.Disconnected:
			conn.connected.Store(false)
		case *slidetypes.AuthError:
			conn.connected.Store(false)
			select {
			case authFailed <- struct{}{}:
			default:
			}
			return errors.New("stop after authentication failure")
		}
		// Message contents are deliberately discarded.
		return nil
	})
	params.Cookies = jar
	params.NativeSession = session
	params.MobileLoginDevice = &session.Device
	conn.client = instameow.NewClient(params)
	conn.boundary = &sendBoundary{base: conn.client.GetHTTP().GetHTTPClient().Transport}
	conn.client.GetHTTP().SetTransportOverride(conn.boundary)
	e.conn = conn
	e.connState = "connecting"
	e.mu.Unlock()

	fail := func(state string) (string, error) {
		e.Disconnect()
		e.mu.Lock()
		e.connState = state
		e.mu.Unlock()
		return e.Status(), nil
	}
	deadline, stop := context.WithTimeout(ctx, time.Duration(timeoutMs)*time.Millisecond)
	defer stop()
	viewer, mailbox, err := conn.client.LoadIndex(deadline)
	if err != nil {
		close(conn.done)
		return fail("failed_initialization")
	}
	if viewer == nil || mailbox == nil || viewer.ID != session.UserID || !strings.EqualFold(viewer.GetUsername(), session.Username) || !conn.client.IsAuthenticated() {
		close(conn.done)
		return fail("failed_identity")
	}
	conn.mailbox = mailbox
	go func() { defer close(conn.done); conn.client.Connect(ctx) }()
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	for !conn.connected.Load() {
		select {
		case <-authFailed:
			return fail("failed_authentication")
		case <-conn.done:
			return fail("failed_socket")
		case <-deadline.Done():
			return fail("failed_timeout")
		case <-ticker.C:
		}
	}
	e.mu.Lock()
	e.connState = "connected"
	e.mu.Unlock()
	return e.Status(), nil
}

func (e *Engine) Disconnect() {
	e.mu.Lock()
	conn := e.conn
	e.mu.Unlock()
	if conn == nil {
		return
	}
	conn.cancel()
	conn.client.Disconnect()
	select {
	case <-conn.done:
	case <-time.After(8 * time.Second):
	}
	e.mu.Lock()
	if e.conn == conn {
		e.conn = nil
		e.connState = "disconnected"
	}
	e.mu.Unlock()
}
