package whatsappengine

import (
	"context"
	"errors"
	"net"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waAdv"
	"go.mau.fi/whatsmeow/socket"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

type recoveryConnection struct {
	*localConnection
	attempts    atomic.Int32
	dialAttempt func(context.Context, int32) error
}

func (c *recoveryConnection) ConnectContext(ctx context.Context) error {
	return c.dialAttempt(ctx, c.attempts.Add(1))
}

func recoveryWhatsApp(t *testing.T) (*WhatsApp, *recoveryConnection) {
	t.Helper()
	w, connections := localWhatsApp(t)
	c := &recoveryConnection{localConnection: (*connections)[0]}
	w.mu.Lock()
	w.account = "synthetic"
	w.conn = c
	w.mu.Unlock()
	return w, c
}

func waitRecovery(t *testing.T, w *WhatsApp) {
	t.Helper()
	w.mu.Lock()
	done := w.connectDone
	w.mu.Unlock()
	if done != nil {
		waitDone(t, done)
	}
}

func unavailableNetwork() error { return &net.DNSError{Err: "synthetic network unavailable"} }

func TestInitialDialRetriesAndAuthenticationClearsOnlyNetworkFailure(t *testing.T) {
	w, c := recoveryWhatsApp(t)
	failed := make(chan struct{})
	restore := make(chan struct{})
	c.dialAttempt = func(ctx context.Context, attempt int32) error {
		if attempt == 1 {
			close(failed)
			return unavailableNetwork()
		}
		select {
		case <-restore:
		case <-ctx.Done():
			return ctx.Err()
		}
		c.connected.Store(true)
		c.loggedIn.Store(true)
		w.handleEvent(c.client, &events.Connected{})
		close(c.login)
		return nil
	}
	w.startStoredConnectionWithDelays([]time.Duration{0, 0})
	waitDone(t, failed)
	result := make(chan bool, 1)
	go func() { result <- w.AwaitLogin(time.Second) }()
	waitDone(t, c.waitStarted)
	if s := w.Status(); s.Blocker != "connect_failed" || s.Authenticated() {
		t.Fatal("initial failure was hidden or treated as authentication")
	}
	close(restore)
	if !<-result {
		t.Fatal("login query did not observe recovered authentication")
	}
	waitRecovery(t, w)
	if c.attempts.Load() != 2 || !w.Status().Authenticated() {
		t.Fatal("initial connection did not recover exactly once")
	}
}

func TestRetryBatchIsBoundedAndExplicitQueryCanTryAgain(t *testing.T) {
	w, c := recoveryWhatsApp(t)
	c.dialAttempt = func(context.Context, int32) error { return unavailableNetwork() }
	w.startStoredConnectionWithDelays([]time.Duration{0, 0, 0})
	waitRecovery(t, w)
	if c.attempts.Load() != 3 || w.Status().Blocker != "connect_failed" {
		t.Fatal("network retry batch was not bounded")
	}
	c.dialAttempt = func(context.Context, int32) error {
		c.connected.Store(true)
		c.loggedIn.Store(true)
		w.handleEvent(c.client, &events.Connected{})
		close(c.login)
		return nil
	}
	if !w.AwaitLogin(time.Second) {
		t.Fatal("explicit Refresh/login query could not restart exhausted initial retry")
	}
	waitRecovery(t, w)
	if c.attempts.Load() != 4 {
		t.Fatal("explicit query dispatched duplicate connections")
	}
}

func TestOwnerCloseCancelsDialAndBackoff(t *testing.T) {
	for _, inDial := range []bool{false, true} {
		t.Run(map[bool]string{false: "backoff", true: "dial"}[inDial], func(t *testing.T) {
			w, c := recoveryWhatsApp(t)
			started := make(chan struct{})
			c.dialAttempt = func(ctx context.Context, _ int32) error {
				close(started)
				if inDial {
					<-ctx.Done()
					return ctx.Err()
				}
				return unavailableNetwork()
			}
			w.startStoredConnectionWithDelays([]time.Duration{0, time.Hour})
			waitDone(t, started)
			closed := make(chan struct{})
			go func() { w.Close(); close(closed) }()
			waitDone(t, closed)
			if c.attempts.Load() != 1 || w.ctx.Err() == nil {
				t.Fatal("owner close leaked a dial or retry")
			}
			w.startStoredConnection()
			if w.connectDone != nil {
				t.Fatal("closed owner admitted new connection work")
			}
		})
	}
}

func TestPermanentBlockersStopRecoveryAndSurviveConnected(t *testing.T) {
	for _, event := range []any{&events.LoggedOut{}, &events.StreamReplaced{}, &events.TemporaryBan{}, &events.ClientOutdated{}} {
		t.Run(event.(interface{ PermanentDisconnectDescription() string }).PermanentDisconnectDescription(), func(t *testing.T) {
			w, c := recoveryWhatsApp(t)
			c.dialAttempt = func(context.Context, int32) error {
				w.handleEvent(c.client, event)
				return unavailableNetwork()
			}
			w.startStoredConnectionWithDelays([]time.Duration{0, 0, 0})
			waitRecovery(t, w)
			blocker := w.Status().Blocker
			w.handleEvent(c.client, &events.Connected{})
			if blocker == "" || blocker == "connect_failed" || w.Status().Blocker != blocker || w.AwaitLogin(time.Second) || c.attempts.Load() != 1 {
				t.Fatal("permanent account blocker was cleared or retried")
			}
		})
	}
}

func TestNonNetworkDialFailureIsNotRetriedAsOffline(t *testing.T) {
	w, c := recoveryWhatsApp(t)
	c.dialAttempt = func(context.Context, int32) error { return errors.New("synthetic connection rejection") }
	w.startStoredConnectionWithDelays([]time.Duration{0, 0, 0})
	waitRecovery(t, w)
	if c.attempts.Load() != 1 || w.Status().Blocker != "connect_rejected" || w.AwaitLogin(time.Second) {
		t.Fatal("non-network rejection was retried or mislabeled offline")
	}
}

func TestStoredSessionOpenStartsOwnerCancellableRecovery(t *testing.T) {
	path := filepath.Join(t.TempDir(), "session.sqlite")
	seed, err := openWhatsApp(context.Background(), path, func(client *whatsmeow.Client) connection {
		return newLocalConnection(client)
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(seed.Close)
	id := types.NewJID("synthetic-stored-session", types.DefaultUserServer)
	seed.client.Store.ID = &id
	seed.client.Store.Account = &waAdv.ADVSignedDeviceIdentity{
		Details: []byte{}, AccountSignature: make([]byte, 64), AccountSignatureKey: make([]byte, 32), DeviceSignature: make([]byte, 64),
	}
	err = seed.client.Store.Save(seed.ctx)
	seed.Close()
	if err != nil {
		t.Fatal(err)
	}
	started := make(chan struct{})
	var c *recoveryConnection
	w, err := openWhatsApp(context.Background(), path, func(client *whatsmeow.Client) connection {
		c = &recoveryConnection{localConnection: newLocalConnection(client)}
		c.dialAttempt = func(ctx context.Context, _ int32) error {
			close(started)
			<-ctx.Done()
			return ctx.Err()
		}
		return c
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(w.Close)
	waitDone(t, started)
	w.Close()
	if c.attempts.Load() != 1 || w.connectDone != nil {
		t.Fatal("stored-session opening did not start and stop owner-bound recovery")
	}
}

func TestHTTPAuthorizationFailureIsNotRetriedAsNetworkError(t *testing.T) {
	if retryableConnectionError(socket.ErrWithStatusCode{StatusCode: 403}) {
		t.Fatal("authorization rejection was treated as a retryable outage")
	}
	if !retryableConnectionError(socket.ErrWithStatusCode{StatusCode: 503}) {
		t.Fatal("temporary server failure was not retryable")
	}
}
