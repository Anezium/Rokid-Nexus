package whatsappengine

import (
	"context"
	"errors"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

type localConnection struct {
	client      *whatsmeow.Client
	connected   atomic.Bool
	loggedIn    atomic.Bool
	disconnects atomic.Int32
	qr          chan whatsmeow.QRChannelItem
	dial        chan context.Context
	waitStarted chan struct{}
	login       chan struct{}
	probe       func()
	earlyWait   bool
}

func newLocalConnection(client *whatsmeow.Client) *localConnection {
	return &localConnection{client: client, qr: make(chan whatsmeow.QRChannelItem, 8), dial: make(chan context.Context, 1), login: make(chan struct{}), waitStarted: make(chan struct{}, 1)}
}
func (c *localConnection) ConnectContext(ctx context.Context) error {
	if c.client.Store.Deleted {
		return store.ErrDeviceDeleted
	}
	c.connected.Store(true)
	c.dial <- ctx
	return nil
}
func (c *localConnection) GetQRChannel(context.Context) (<-chan whatsmeow.QRChannelItem, error) {
	if c.probe != nil {
		c.probe()
	}
	if c.connected.Load() {
		return nil, errors.New("already connected")
	}
	return c.qr, nil
}
func (c *localConnection) PairPhone(context.Context, string, bool, whatsmeow.PairClientType, string) (string, error) {
	return "SYNTHETIC", nil
}
func (c *localConnection) Disconnect() {
	c.disconnects.Add(1)
	c.connected.Store(false)
	c.loggedIn.Store(false)
}
func (c *localConnection) IsConnected() bool {
	if c.probe != nil {
		c.probe()
	}
	return c.connected.Load()
}
func (c *localConnection) IsLoggedIn() bool { return c.loggedIn.Load() }
func (c *localConnection) WaitForConnection(timeout time.Duration) bool {
	select {
	case c.waitStarted <- struct{}{}:
	default:
	}
	if c.earlyWait {
		return false
	}
	select {
	case <-c.login:
		return c.loggedIn.Load()
	case <-time.After(timeout):
		return false
	}
}
func (c *localConnection) Logout(context.Context) error {
	c.Disconnect()
	c.client.Store.Deleted = true
	c.client.Store.ID = nil
	return nil
}

func localWhatsApp(t *testing.T) (*WhatsApp, *[]*localConnection) {
	t.Helper()
	connections := new([]*localConnection)
	w, err := openWhatsApp(context.Background(), filepath.Join(t.TempDir(), "session.sqlite"), func(client *whatsmeow.Client) connection {
		c := newLocalConnection(client)
		*connections = append(*connections, c)
		return c
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(w.Close)
	return w, connections
}
func waitDone(t *testing.T, done <-chan struct{}) {
	t.Helper()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("operation did not finish")
	}
}
func beginLocalPairing(t *testing.T, w *WhatsApp, c *localConnection) (Link, context.Context) {
	t.Helper()
	link, err := w.StartLink(context.Background(), "qr", "")
	if err != nil {
		t.Fatal(err)
	}
	select {
	case ctx := <-c.dial:
		return link, ctx
	case <-time.After(time.Second):
		t.Fatal("no dial")
	}
	return Link{}, nil
}

func TestPairSuccessSurvives515AndAwaitsAuthentication(t *testing.T) {
	w, connections := localWhatsApp(t)
	c := (*connections)[0]
	link, owner := beginLocalPairing(t, w, c)
	id := types.NewJID("synthetic-test", types.DefaultUserServer)
	c.client.Store.ID = &id
	w.handleEvent(c.client, &events.PairSuccess{ID: id})
	c.qr <- whatsmeow.QRChannelItem{Event: "success"}
	waitDone(t, w.pairDone)
	if owner.Err() != nil || c.disconnects.Load() != 0 {
		t.Fatal("challenge completion closed owner connection")
	}
	polled, _ := w.PollLink(link.Session)
	if polled.Status != "success" || w.Status().Link != "linked" || w.Status().Authenticated() {
		t.Fatal("pair acceptance must precede authentication")
	}
	// Emulate the 515 transport replacement using exactly the context recorded by ConnectContext.
	c.Disconnect()
	if err := c.ConnectContext(owner); err != nil {
		t.Fatal(err)
	}
	authenticated := make(chan bool, 1)
	go func() { authenticated <- w.AwaitLogin(time.Second) }()
	waitDone(t, c.waitStarted)
	c.loggedIn.Store(true)
	w.handleEvent(c.client, &events.Connected{})
	close(c.login)
	if !<-authenticated {
		t.Fatal("login was not observed after stream replacement")
	}
	w.Close()
	if owner.Err() == nil {
		t.Fatal("owner close must cancel transport")
	}
}

func TestEveryUnsuccessfulPairingDisconnectsAndAllowsRetry(t *testing.T) {
	for _, event := range []string{"err-pairing", "timeout", "passkey-request", "passkey-confirmation", "closed", "cancel"} {
		t.Run(event, func(t *testing.T) {
			w, connections := localWhatsApp(t)
			c := (*connections)[0]
			link, _ := beginLocalPairing(t, w, c)
			switch event {
			case "closed":
				close(c.qr)
			case "cancel":
				w.cancel()
			default:
				c.qr <- whatsmeow.QRChannelItem{Event: event}
			}
			waitDone(t, w.pairDone)
			state, _ := w.PollLink(link.Session)
			expected := "error"
			if event == "timeout" || event == "cancel" {
				expected = "timeout"
			}
			if event == "passkey-request" || event == "passkey-confirmation" {
				expected = "passkey_required"
			}
			if state.Status != expected || state.Code != "" || state.QR != "" || c.disconnects.Load() != 1 {
				t.Fatal("unsuccessful attempt was not cleaned up")
			}
			c.qr = make(chan whatsmeow.QRChannelItem, 8)
			beginLocalPairing(t, w, c)
		})
	}
}

func TestAcceptedPairingWinsDeadlineRace(t *testing.T) {
	w, connections := localWhatsApp(t)
	c := (*connections)[0]
	beginLocalPairing(t, w, c)
	w.handleEvent(c.client, &events.PairSuccess{})
	w.cancel()
	waitDone(t, w.pairDone)
	if c.disconnects.Load() != 0 || w.link.Status != "success" {
		t.Fatal("deadline disconnected an accepted pairing")
	}
}

func TestUnlinkAndRemoteRemovalCreateFreshDeviceBeforeRelink(t *testing.T) {
	for _, remote := range []bool{false, true} {
		t.Run(map[bool]string{true: "remote", false: "local"}[remote], func(t *testing.T) {
			w, connections := localWhatsApp(t)
			old := (*connections)[0]
			old.client.Store.Deleted = true
			if remote {
				w.handleEvent(old.client, &events.LoggedOut{})
			} else if err := w.Logout(context.Background()); err != nil {
				t.Fatal(err)
			}
			if _, err := w.StartLink(context.Background(), "qr", ""); err != nil {
				t.Fatal(err)
			}
			current := (*connections)[len(*connections)-1]
			select {
			case <-current.dial:
			case <-time.After(time.Second):
				t.Fatal("relink never dialed")
			}
			if current == old || current.client.Store.Deleted || w.Status().Blocker != "" {
				t.Fatal("deleted device or stale blocker reused")
			}
		})
	}
}

func TestAwaitLoginDeadlineBlockerAndOwnerCancellation(t *testing.T) {
	w, connections := localWhatsApp(t)
	c := (*connections)[0]
	w.mu.Lock()
	w.account = "synthetic"
	w.mu.Unlock()
	if w.AwaitLogin(10 * time.Millisecond) {
		t.Fatal("unauthenticated login accepted")
	}
	started := time.Now()
	w.handleEvent(c.client, &events.TemporaryBan{})
	if w.AwaitLogin(time.Second) || time.Since(started) > 100*time.Millisecond {
		t.Fatal("blocker did not stop login wait")
	}
	w.mu.Lock()
	w.blocker = ""
	w.mu.Unlock()
	result := make(chan bool, 1)
	go func() { result <- w.AwaitLogin(time.Second) }()
	w.ownerCancel()
	select {
	case ok := <-result:
		if ok {
			t.Fatal("closed owner authenticated")
		}
	case <-time.After(time.Second):
		t.Fatal("owner cancellation did not interrupt login")
	}
}

func TestClientCallsNeverHoldStateMutex(t *testing.T) {
	w, connections := localWhatsApp(t)
	c := (*connections)[0]
	c.probe = func() {
		if !w.mu.TryLock() {
			t.Error("client called under state mutex")
			return
		}
		w.mu.Unlock()
	}
	w.Status()
	beginLocalPairing(t, w, c)
}

func TestPhoneNormalization(t *testing.T) {
	if normalized, err := normalizePhone("+1 (202) 555-0100"); err != nil || normalized != "12025550100" {
		t.Fatal("formatted synthetic number rejected")
	}
	for _, input := range []string{"1+2025550100", "++12025550100", "0123456789", "123", "１２３４５６７８９"} {
		if _, err := normalizePhone(input); err == nil {
			t.Fatal("malformed number accepted")
		}
	}
}

func TestLoginWaitContinuesAfterTransportWaitReturnsEarly(t *testing.T) {
	w, connections := localWhatsApp(t)
	c := (*connections)[0]
	c.earlyWait = true
	id := types.NewJID("synthetic", types.DefaultUserServer)
	c.client.Store.ID = &id
	w.handleEvent(c.client, &events.PairSuccess{ID: id})
	result := make(chan bool, 1)
	go func() { result <- w.AwaitLogin(time.Second) }()
	waitDone(t, c.waitStarted)
	c.connected.Store(true)
	c.loggedIn.Store(true)
	w.handleEvent(c.client, &events.Connected{})
	if !<-result {
		t.Fatal("early transport wakeup was mistaken for a final login failure")
	}
}

func TestSettingsCanAbortPairingWhileAnotherOwnerRetainsEngine(t *testing.T) {
	w, connections := localWhatsApp(t)
	c := (*connections)[0]
	_, transport := beginLocalPairing(t, w, c)
	w.AbortLink()
	if transport.Err() == nil || w.ctx.Err() != nil || c.connected.Load() {
		t.Fatal("settings did not cancel only its pairing transport")
	}
	if _, err := w.StartLink(context.Background(), "qr", ""); err != nil {
		t.Fatal(err)
	}
}

func TestSettingsClosePreservesReconnectAfterAuthenticatedPairing(t *testing.T) {
	w, connections := localWhatsApp(t)
	c := (*connections)[0]
	_, transport := beginLocalPairing(t, w, c)
	id := types.NewJID("synthetic-test", types.DefaultUserServer)
	w.handleEvent(c.client, &events.PairSuccess{ID: id})
	c.qr <- whatsmeow.QRChannelItem{Event: "success"}
	waitDone(t, w.pairDone)
	c.loggedIn.Store(true)
	w.handleEvent(c.client, &events.Connected{})
	if !w.Status().Authenticated() {
		t.Fatal("test did not reach authenticated state")
	}
	// A transient transport outage must not make a completed link cancellable again.
	c.connected.Store(false)
	c.loggedIn.Store(false)
	w.AbortLink()
	if transport.Err() != nil || c.disconnects.Load() != 0 {
		t.Fatal("settings close canceled reconnection for an already authenticated link")
	}
	if err := c.ConnectContext(transport); err != nil {
		t.Fatal(err)
	}
	c.loggedIn.Store(true)
	w.handleEvent(c.client, &events.Connected{})
	if !w.Status().Authenticated() {
		t.Fatal("retained owner could not reconnect")
	}
	w.Close()
	if transport.Err() == nil {
		t.Fatal("last owner close leaked the completed transport")
	}
}
