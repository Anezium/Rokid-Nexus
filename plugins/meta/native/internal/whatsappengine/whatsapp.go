package whatsappengine

import (
	"context"
	"errors"
	"net/http"
	"path/filepath"
	"sort"
	"sync"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/proto"
)

type Contact struct {
	JID     string `json:"jid"`
	Name    string `json:"name"`
	IsGroup bool   `json:"isGroup"`
}

type Link struct {
	Session   string `json:"session"`
	Status    string `json:"status"`
	QR        string `json:"qr,omitempty"`
	Code      string `json:"code,omitempty"`
	ExpiresAt int64  `json:"expiresAt"`
}

type Status struct {
	Link      string `json:"link"`
	Connected bool   `json:"connected"`
	LoggedIn  bool   `json:"loggedIn"`
	Blocker   string `json:"blocker,omitempty"`
}

func (s Status) Authenticated() bool {
	return s.Link == "linked" && s.Connected && s.LoggedIn && s.Blocker == ""
}

type Backend interface {
	Status() Status
	AwaitLogin(time.Duration) bool
	AccountScope() string
	Contacts(context.Context) ([]Contact, error)
	StartLink(context.Context, string, string) (Link, error)
	PollLink(string) (Link, error)
	AbortLink()
	Logout(context.Context) error
	MessageID() string
	Send(context.Context, string, string, string) (int64, error)
	Chats(context.Context, int) ([]Chat, error)
	Thumbnail(context.Context, string) ([]byte, error)
	PrepareImage(context.Context, []byte) (*waE2E.Message, error)
	SendPrepared(context.Context, string, *waE2E.Message, string) (int64, error)
	RequestHistory(context.Context, string, int) error
	Close()
}

type connection interface {
	ConnectContext(context.Context) error
	GetQRChannel(context.Context) (<-chan whatsmeow.QRChannelItem, error)
	PairPhone(context.Context, string, bool, whatsmeow.PairClientType, string) (string, error)
	Disconnect()
	IsConnected() bool
	IsLoggedIn() bool
	WaitForConnection(time.Duration) bool
	Logout(context.Context) error
}

type WhatsApp struct {
	ctx               context.Context
	ownerCancel       context.CancelFunc
	client            *whatsmeow.Client
	conn              connection
	store             *sqlstore.Container
	history           *History
	mu                sync.Mutex
	operation         sync.Mutex
	closeOnce         sync.Once
	link              Link
	blocker           string
	account           string
	until             int64
	cancel            context.CancelFunc
	linkCancel        context.CancelFunc
	linkAuthenticated bool
	pairDone          chan struct{}
	changed           chan struct{}
	wrapConnection    func(*whatsmeow.Client) connection
	connectDone       chan struct{}
	// naming holds the groups whose name is being fetched, so one fetch serves a burst of messages.
	naming sync.Map
}

func OpenWhatsApp(ctx context.Context, path string) (*WhatsApp, error) {
	return openWhatsApp(ctx, path, nil)
}

func openWhatsApp(ctx context.Context, path string, wrap func(*whatsmeow.Client) connection) (*WhatsApp, error) {
	owner, cancel := context.WithCancel(ctx)
	container, err := sqlstore.New(owner, "sqlite3", "file:"+path+"?_foreign_keys=on&_busy_timeout=5000&_journal_mode=WAL&_synchronous=FULL", nil)
	if err != nil {
		cancel()
		return nil, err
	}
	device, err := container.GetFirstDevice(owner)
	if err != nil {
		cancel()
		container.Close()
		return nil, err
	}
	history, err := OpenHistory(filepath.Join(filepath.Dir(path), "messages.sqlite"))
	if err != nil {
		cancel()
		container.Close()
		return nil, err
	}
	w := &WhatsApp{ctx: owner, ownerCancel: cancel, store: container, history: history, changed: make(chan struct{}), wrapConnection: wrap}
	w.installClient(device)
	if !device.GetJID().IsEmpty() {
		w.startStoredConnection()
	}
	return w, nil
}

func (w *WhatsApp) installClient(device *store.Device) {
	client := whatsmeow.NewClient(device, nil)
	client.BackgroundEventCtx = w.ctx
	var cn connection = client
	if w.wrapConnection != nil {
		cn = w.wrapConnection(client)
	}
	client.AddEventHandler(func(event any) { w.handleEvent(client, event) })
	account := ""
	if id := device.GetJID(); !id.IsEmpty() {
		account = id.String()
	}
	w.mu.Lock()
	w.client, w.conn, w.account, w.blocker = client, cn, account, ""
	w.link = Link{}
	w.linkAuthenticated = false
	w.notifyLocked()
	w.mu.Unlock()
}

func (w *WhatsApp) handleEvent(client *whatsmeow.Client, event any) {
	if w.history != nil {
		w.history.recordEvent(client, event)
		if message, ok := event.(*events.Message); ok && message.Info.IsGroup {
			w.nameGroup(client, message.Info.Chat.ToNonAD())
		}
	}
	switch event.(type) {
	case *events.Connected, *events.PairSuccess, *events.LoggedOut, *events.StreamReplaced, *events.TemporaryBan, *events.ClientOutdated:
	default:
		return
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	if client != w.client {
		return
	}
	switch event := event.(type) {
	case *events.Connected:
		if w.blocker == "connect_failed" {
			w.blocker = ""
		}
		// A later outage must not make a completed pairing cancellable again.
		if w.link.Status == "success" && w.account != "" {
			w.linkAuthenticated = true
		}
	case *events.PairSuccess:
		if !event.ID.IsEmpty() {
			w.account = event.ID.String()
		}
		if w.link.Status == "pairing" || w.link.Status == "starting" {
			w.link.Status, w.link.Code, w.link.QR = "success", "", ""
		}
	case *events.LoggedOut:
		w.account, w.blocker = "", "logged_out"
	case *events.StreamReplaced:
		w.blocker = "stream_replaced"
	case *events.TemporaryBan:
		w.blocker = "temporary_ban"
	case *events.ClientOutdated:
		w.blocker = "client_outdated"
	}
	w.notifyLocked()
}

func (w *WhatsApp) notifyLocked() {
	if w.changed != nil {
		close(w.changed)
	}
	w.changed = make(chan struct{})
}

func (w *WhatsApp) connection() connection {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.conn != nil {
		return w.conn
	}
	return w.client
}

func (w *WhatsApp) stopPairing() {
	w.mu.Lock()
	cancel, transportCancel, done := w.cancel, w.linkCancel, w.pairDone
	w.mu.Unlock()
	if cancel != nil {
		cancel()
	}
	if transportCancel != nil {
		transportCancel()
	}
	if done != nil {
		<-done
	}
}

// AbortLink belongs to the settings owner, even if another surface owns the engine.
func (w *WhatsApp) AbortLink() {
	w.operation.Lock()
	defer w.operation.Unlock()
	w.mu.Lock()
	attempt := w.link.Session != "" && !w.linkAuthenticated
	w.mu.Unlock()
	if !attempt || w.Status().Authenticated() {
		return
	}
	w.stopPairing()
	w.connection().Disconnect()
}

func (w *WhatsApp) Close() {
	w.closeOnce.Do(func() {
		if w.ownerCancel != nil {
			w.ownerCancel()
		}
		w.mu.Lock()
		done := w.connectDone
		w.mu.Unlock()
		if done != nil {
			<-done
		}
		w.operation.Lock()
		defer w.operation.Unlock()
		w.stopPairing()
		w.connection().Disconnect()
		if w.store != nil {
			w.store.Close()
		}
		if w.history != nil {
			w.history.Close()
		}
	})
}

func (w *WhatsApp) AccountScope() string { w.mu.Lock(); defer w.mu.Unlock(); return w.account }

func (w *WhatsApp) Status() Status {
	w.mu.Lock()
	account, blocker, link, until := w.account, w.blocker, w.link.Status, w.until
	w.mu.Unlock()
	cn := w.connection()
	s := Status{Link: "unlinked", Connected: cn.IsConnected(), LoggedIn: cn.IsLoggedIn(), Blocker: blocker}
	if account != "" && blocker != "logged_out" {
		s.Link = "linked"
	} else if (link == "starting" || link == "pairing") && until > time.Now().Unix() {
		s.Link = "pairing"
	}
	return s
}

func (w *WhatsApp) AwaitLogin(timeout time.Duration) bool {
	if timeout <= 0 || timeout > 8*time.Second {
		return false
	}
	s := w.Status()
	if s.Authenticated() {
		return true
	}
	if (s.Blocker != "" && s.Blocker != "connect_failed") || s.Link == "unlinked" {
		return false
	}
	if s.Blocker == "connect_failed" {
		w.startStoredConnection()
	}
	cn := w.connection()
	ready := make(chan bool, 1)
	go func() { ready <- cn.WaitForConnection(timeout) }()
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	for {
		w.mu.Lock()
		changed := w.changed
		w.mu.Unlock()
		s = w.Status()
		if s.Authenticated() {
			return true
		}
		if s.Blocker != "" && s.Blocker != "connect_failed" {
			return false
		}
		select {
		case <-ready:
			// A transport replacement can end WaitForConnection before the login event.
			// Continue observing events until this caller's deadline or an explicit blocker.
			ready = nil
		case <-changed:
		case <-timer.C:
			return false
		case <-w.ctx.Done():
			return false
		}
	}
}

func validRecipient(raw string) bool {
	jid, err := types.ParseJID(raw)
	return err == nil && jid.User != "" && jid.Device == 0 && jid.String() == raw && (jid.Server == types.DefaultUserServer || jid.Server == types.HiddenUserServer)
}

func validGroup(raw string) bool {
	jid, err := types.ParseJID(raw)
	return err == nil && jid.User != "" && jid.Device == 0 && jid.String() == raw && jid.Server == types.GroupServer
}

func (w *WhatsApp) Contacts(ctx context.Context) ([]Contact, error) {
	all, err := w.client.Store.Contacts.GetAllContacts(ctx)
	if err != nil {
		return nil, err
	}
	out := make([]Contact, 0, len(all))
	for jid, c := range all {
		if !validRecipient(jid.String()) {
			continue
		}
		name := c.FullName
		if name == "" {
			name = c.FirstName
		}
		if name == "" {
			name = c.PushName
		}
		if name != "" {
			out = append(out, Contact{JID: jid.String(), Name: name})
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].JID < out[j].JID })
	return out, nil
}

func (w *WhatsApp) Logout(ctx context.Context) error {
	w.operation.Lock()
	defer w.operation.Unlock()
	if w.ctx.Err() != nil {
		return errors.New("closed")
	}
	if err := w.connection().Logout(ctx); err != nil {
		return err
	}
	w.stopPairing()
	w.history.Clear()
	w.installClient(w.store.NewDevice())
	return nil
}

func (w *WhatsApp) contactName(ctx context.Context, jid types.JID) string {
	return storedName(ctx, w.client, jid)
}

// storedName is the name the synced contacts and push names give jid, or "".
func storedName(ctx context.Context, client *whatsmeow.Client, jid types.JID) string {
	if client == nil || client.Store == nil || client.Store.Contacts == nil || jid.IsEmpty() {
		return ""
	}
	c, err := client.Store.Contacts.GetContact(ctx, jid)
	if err != nil || !c.Found {
		return ""
	}
	for _, name := range []string{c.FullName, c.FirstName, c.PushName} {
		if name != "" {
			return name
		}
	}
	return ""
}

// nameGroup fetches the name of a group first seen live: only a history sync or a
// metadata change carries it otherwise, and the glasses would list the bare group id.
func (w *WhatsApp) nameGroup(client *whatsmeow.Client, group types.JID) {
	if w.history.Named(group) {
		return
	}
	if _, busy := w.naming.LoadOrStore(group, struct{}{}); busy {
		return
	}
	go func() {
		defer w.naming.Delete(group)
		ctx, cancel := context.WithTimeout(w.ctx, 15*time.Second)
		defer cancel()
		if info, err := client.GetGroupInfo(ctx, group); err == nil {
			w.history.Rename(group, info.Name)
		}
	}()
}

// Chats lists stored conversations, named from the synced contacts when possible.
func (w *WhatsApp) Chats(ctx context.Context, perChat int) ([]Chat, error) {
	return w.history.Chats(ctx, perChat, func(jid types.JID) string { return w.contactName(ctx, jid) })
}

// RequestHistory asks the main phone for up to count messages older than the oldest
// stored one. They arrive later as a history sync and are stored like any other.
func (w *WhatsApp) RequestHistory(ctx context.Context, chat string, count int) error {
	jid, err := types.ParseJID(chat)
	if err != nil || count < 1 || count > 50 {
		return errors.New("invalid history request")
	}
	anchor, err := w.history.Oldest(ctx, jid)
	if err != nil {
		return err
	}
	_, err = w.client.SendPeerMessage(ctx, w.client.BuildHistorySyncRequest(anchor, count))
	return err
}

func (w *WhatsApp) MessageID() string { return w.client.GenerateMessageID() }

// PrepareImage uploads an encrypted JPEG or PNG to the WhatsApp media servers. Uploading
// creates no message; the returned message is sent later, once admitted.
func (w *WhatsApp) PrepareImage(ctx context.Context, data []byte) (*waE2E.Message, error) {
	mime := http.DetectContentType(data)
	if mime != "image/jpeg" && mime != "image/png" {
		return nil, errors.New("invalid image")
	}
	upload, err := w.client.Upload(ctx, data, whatsmeow.MediaImage)
	if err != nil {
		return nil, err
	}
	return &waE2E.Message{ImageMessage: &waE2E.ImageMessage{
		URL: proto.String(upload.URL), DirectPath: proto.String(upload.DirectPath), MediaKey: upload.MediaKey,
		Mimetype: proto.String(mime), FileEncSHA256: upload.FileEncSHA256, FileSHA256: upload.FileSHA256,
		FileLength: proto.Uint64(upload.FileLength), JPEGThumbnail: makeThumbnail(data),
	}}, nil
}

// Thumbnail returns the stored thumbnail a message Preview names.
func (w *WhatsApp) Thumbnail(ctx context.Context, preview string) ([]byte, error) {
	return w.history.Thumbnail(ctx, preview)
}

// SendPrepared sends an already uploaded message once and keeps it in the history.
func (w *WhatsApp) SendPrepared(ctx context.Context, to string, message *waE2E.Message, id string) (int64, error) {
	jid, err := types.ParseJID(to)
	if err != nil {
		return 0, err
	}
	result, err := w.client.SendMessage(ctx, jid, message, whatsmeow.SendRequestExtra{ID: id, Timeout: 8 * time.Second})
	if err == nil {
		w.history.Record(types.MessageInfo{MessageSource: types.MessageSource{Chat: jid, IsFromMe: true}, ID: id, Timestamp: result.Timestamp}, message, "")
	}
	return result.Timestamp.Unix(), err
}
func (w *WhatsApp) Send(ctx context.Context, to, text, id string) (int64, error) {
	jid, err := types.ParseJID(to)
	if err != nil {
		return 0, err
	}
	result, err := w.client.SendMessage(ctx, jid, &waE2E.Message{Conversation: proto.String(text)}, whatsmeow.SendRequestExtra{ID: id, Timeout: 8 * time.Second})
	if err == nil {
		w.history.RecordOwn(jid, id, text, result.Timestamp)
	}
	return result.Timestamp.Unix(), err
}
