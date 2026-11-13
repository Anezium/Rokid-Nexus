package whatsappengine

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"sync"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

const (
	keptPerChat    = 200
	MaxHistory     = 60
	maxListedChats = 40
)

// HistoryMessage is one stored message as the glasses show it. Contents stay in the
// plugin's private storage and are never logged.
type HistoryMessage struct {
	ID     string `json:"id"`
	Mine   bool   `json:"mine"`
	Text   string `json:"text"`
	Sender string `json:"sender,omitempty"`
	// Preview names the stored thumbnail of a photo or video, for Thumbnail; never logged.
	Preview string `json:"preview,omitempty"`
}

// ThumbnailPrefix starts a Preview that names a thumbnail kept in the history, not a URL.
const ThumbnailPrefix = "whatsapp-thumbnail:"

// maxThumbnailBytes bounds what a message may store: WhatsApp's own previews are a few KiB.
const maxThumbnailBytes = 64 * 1024

// Chat is a conversation known from received, sent or history-synced messages.
type Chat struct {
	JID       string           `json:"jid"`
	Name      string           `json:"name"`
	IsGroup   bool             `json:"isGroup"`
	Supported bool             `json:"supported"`
	Messages  []HistoryMessage `json:"messages"`
}

// History keeps the messages this linked device has seen: those delivered while it
// was connected (including the backlog WhatsApp hands over on reconnect) and the
// conversations the main phone sends at pairing or on request.
type History struct {
	db *sql.DB
	mu sync.Mutex
}

func OpenHistory(path string) (*History, error) {
	db, err := sql.Open("sqlite3", "file:"+path+"?_busy_timeout=5000&_journal_mode=WAL&_synchronous=NORMAL")
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	_, err = db.Exec(`CREATE TABLE IF NOT EXISTS messages (
		chat TEXT NOT NULL, id TEXT NOT NULL, from_me INTEGER NOT NULL, sender TEXT NOT NULL,
		text TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY (chat, id));
		CREATE INDEX IF NOT EXISTS messages_by_time ON messages (chat, at);
		CREATE TABLE IF NOT EXISTS chats (chat TEXT PRIMARY KEY, name TEXT NOT NULL, last INTEGER NOT NULL);`)
	if err == nil && !hasColumn(db, "messages", "thumb") {
		// Histories from before thumbnails were kept gain the column; old rows stay without one.
		_, err = db.Exec(`ALTER TABLE messages ADD COLUMN thumb BLOB`)
	}
	if err != nil {
		db.Close()
		return nil, err
	}
	return &History{db: db}, nil
}

func (h *History) Close() error { return h.db.Close() }

// messageText reduces a message to what the glasses can show; nil means "not a chat message".
func messageText(m *waE2E.Message) *string {
	if m == nil {
		return nil
	}
	text := ""
	switch {
	case m.GetConversation() != "":
		text = m.GetConversation()
	case m.GetExtendedTextMessage() != nil:
		text = m.GetExtendedTextMessage().GetText()
	case m.GetImageMessage() != nil:
		text = strings.TrimSpace("[Photo] " + m.GetImageMessage().GetCaption())
	case m.GetVideoMessage() != nil:
		text = strings.TrimSpace("[Video] " + m.GetVideoMessage().GetCaption())
	case m.GetAudioMessage() != nil:
		text = "[Voice message]"
	case m.GetDocumentMessage() != nil:
		text = strings.TrimSpace("[Document] " + m.GetDocumentMessage().GetFileName())
	case m.GetStickerMessage() != nil:
		text = "[Sticker]"
	case m.GetLocationMessage() != nil, m.GetLiveLocationMessage() != nil:
		text = "[Location]"
	case m.GetContactMessage() != nil, m.GetContactsArrayMessage() != nil:
		text = "[Contact]"
	case m.GetEphemeralMessage() != nil:
		return messageText(m.GetEphemeralMessage().GetMessage())
	case m.GetViewOnceMessage() != nil:
		return messageText(m.GetViewOnceMessage().GetMessage())
	default:
		return nil
	}
	if text = strings.TrimSpace(text); text == "" {
		return nil
	}
	return &text
}

// messageThumbnail is the small JPEG a photo or video message carries inline, or nil. It is
// part of the message itself: nothing is downloaded and no media key is needed.
func messageThumbnail(m *waE2E.Message) []byte {
	var thumb []byte
	switch {
	case m == nil:
		return nil
	case m.GetImageMessage() != nil:
		thumb = m.GetImageMessage().GetJPEGThumbnail()
	case m.GetVideoMessage() != nil:
		thumb = m.GetVideoMessage().GetJPEGThumbnail()
	case m.GetEphemeralMessage() != nil:
		return messageThumbnail(m.GetEphemeralMessage().GetMessage())
	}
	if len(thumb) == 0 || len(thumb) > maxThumbnailBytes {
		return nil
	}
	return thumb
}

func hasColumn(db *sql.DB, table, column string) bool {
	rows, err := db.Query(`SELECT name FROM pragma_table_info(?)`, table)
	if err != nil {
		return false
	}
	defer rows.Close()
	for rows.Next() {
		var name string
		if rows.Scan(&name) == nil && name == column {
			return true
		}
	}
	return false
}

// Record stores one message; reactions, receipts and protocol traffic are skipped.
func (h *History) Record(info types.MessageInfo, m *waE2E.Message, chatName string) {
	text := messageText(m)
	if text == nil || info.ID == "" || info.Chat.IsEmpty() {
		return
	}
	chat := info.Chat.ToNonAD().String()
	sender := ""
	if !info.IsFromMe && info.IsGroup {
		sender = info.PushName
	}
	at := info.Timestamp.UnixMilli()
	h.mu.Lock()
	defer h.mu.Unlock()
	// A message seen again (a later history sync) keeps its row and only gains a missing thumbnail or sender.
	if _, err := h.db.Exec(`INSERT INTO messages (chat, id, from_me, sender, text, at, thumb) VALUES (?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(chat, id) DO UPDATE SET thumb = COALESCE(messages.thumb, excluded.thumb),
		sender = CASE WHEN messages.sender = '' THEN excluded.sender ELSE messages.sender END`,
		chat, info.ID, info.IsFromMe, sender, *text, at, messageThumbnail(m)); err != nil {
		return
	}
	h.db.Exec(`INSERT INTO chats (chat, name, last) VALUES (?, ?, ?)
		ON CONFLICT(chat) DO UPDATE SET last = MAX(last, excluded.last), name = CASE WHEN excluded.name <> '' THEN excluded.name ELSE name END`,
		chat, chatName, at)
	h.db.Exec(`DELETE FROM messages WHERE chat = ? AND id NOT IN (SELECT id FROM messages WHERE chat = ? ORDER BY at DESC LIMIT ?)`,
		chat, chat, keptPerChat)
}

// Named reports whether a stored conversation already has a name of its own.
func (h *History) Named(chat types.JID) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	var name string
	return h.db.QueryRow(`SELECT name FROM chats WHERE chat = ?`, chat.ToNonAD().String()).Scan(&name) == nil && name != ""
}

// Rename names a stored conversation; one not stored yet gains its name with its first message.
func (h *History) Rename(chat types.JID, name string) {
	if name = strings.TrimSpace(name); name == "" {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	h.db.Exec(`UPDATE chats SET name = ? WHERE chat = ?`, name, chat.ToNonAD().String())
}

// RecordOwn stores a message this device just sent, so the conversation shows it.
func (h *History) RecordOwn(chat types.JID, id, text string, at time.Time) {
	h.Record(types.MessageInfo{MessageSource: types.MessageSource{Chat: chat, IsFromMe: true}, ID: id, Timestamp: at},
		&waE2E.Message{Conversation: &text}, "")
}

// Chats lists recent conversations with up to perChat of their latest messages, oldest first.
func (h *History) Chats(ctx context.Context, perChat int, name func(types.JID) string) ([]Chat, error) {
	if perChat < 1 || perChat > MaxHistory {
		perChat = MaxHistory
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	rows, err := h.db.QueryContext(ctx, `SELECT chat, name FROM chats ORDER BY last DESC LIMIT ?`, maxListedChats)
	if err != nil {
		return nil, err
	}
	type entry struct{ chat, name string }
	var entries []entry
	for rows.Next() {
		var e entry
		if rows.Scan(&e.chat, &e.name) == nil {
			entries = append(entries, e)
		}
	}
	rows.Close()
	chats := make([]Chat, 0, len(entries))
	for _, e := range entries {
		jid, err := types.ParseJID(e.chat)
		if err != nil {
			continue
		}
		title := strings.TrimSpace(name(jid))
		if title == "" {
			title = e.name
		}
		if title == "" && jid.Server == types.GroupServer {
			title = "WhatsApp group"
		} else if title == "" {
			title = "+" + jid.User
		}
		messages, err := h.latest(ctx, e.chat, perChat)
		if err != nil {
			return nil, err
		}
		chats = append(chats, Chat{JID: e.chat, Name: title, IsGroup: jid.Server == types.GroupServer,
			Supported: validRecipient(e.chat) || validGroup(e.chat), Messages: messages})
	}
	return chats, nil
}

func (h *History) latest(ctx context.Context, chat string, limit int) ([]HistoryMessage, error) {
	rows, err := h.db.QueryContext(ctx, `SELECT id, from_me, sender, text, has_thumb FROM
		(SELECT id, from_me, sender, text, at, thumb IS NOT NULL AS has_thumb FROM messages WHERE chat = ? ORDER BY at DESC LIMIT ?)
		ORDER BY at ASC`, chat, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []HistoryMessage{}
	for rows.Next() {
		var m HistoryMessage
		var hasThumb bool
		if rows.Scan(&m.ID, &m.Mine, &m.Sender, &m.Text, &hasThumb) == nil {
			if hasThumb {
				m.Preview = ThumbnailPrefix + chat + "/" + m.ID
			}
			out = append(out, m)
		}
	}
	return out, rows.Err()
}

// Thumbnail returns the thumbnail a Preview names, or an error when there is none.
func (h *History) Thumbnail(ctx context.Context, preview string) ([]byte, error) {
	ref, ok := strings.CutPrefix(preview, ThumbnailPrefix)
	cut := strings.LastIndex(ref, "/")
	if !ok || cut <= 0 || cut == len(ref)-1 {
		return nil, errors.New("invalid preview")
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	var thumb []byte
	err := h.db.QueryRowContext(ctx, `SELECT thumb FROM messages WHERE chat = ? AND id = ? AND thumb IS NOT NULL`,
		ref[:cut], ref[cut+1:]).Scan(&thumb)
	if err != nil {
		return nil, errors.New("no thumbnail")
	}
	return thumb, nil
}

// Clear forgets every stored message, for an unlinked account.
func (h *History) Clear() {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.db.Exec(`DELETE FROM messages; DELETE FROM chats;`)
}

// Count reports how many messages are stored for a conversation.
func (h *History) Count(ctx context.Context, chat string) int {
	h.mu.Lock()
	defer h.mu.Unlock()
	var n int
	h.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM messages WHERE chat = ?`, chat).Scan(&n)
	return n
}

// Oldest returns the oldest stored message of a conversation, the anchor of an on-demand request.
func (h *History) Oldest(ctx context.Context, chat types.JID) (*types.MessageInfo, error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	var id string
	var mine bool
	var at int64
	err := h.db.QueryRowContext(ctx, `SELECT id, from_me, at FROM messages WHERE chat = ? ORDER BY at ASC LIMIT 1`, chat.String()).Scan(&id, &mine, &at)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, errors.New("no anchor message")
	} else if err != nil {
		return nil, err
	}
	return &types.MessageInfo{MessageSource: types.MessageSource{Chat: chat, IsFromMe: mine}, ID: id, Timestamp: time.UnixMilli(at)}, nil
}

// withSenderName names a group message's sender from the contacts when the message
// carries no push name, as history-synced messages usually do not.
func withSenderName(client *whatsmeow.Client, info types.MessageInfo) types.MessageInfo {
	if info.IsFromMe || !info.IsGroup || info.PushName != "" {
		return info
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	for _, sender := range []types.JID{info.Sender, info.SenderAlt} {
		if name := storedName(ctx, client, sender.ToNonAD()); name != "" {
			info.PushName = name
			break
		}
	}
	return info
}

// recordEvent stores chat messages from live and history-sync events.
func (h *History) recordEvent(client *whatsmeow.Client, event any) {
	switch event := event.(type) {
	case *events.Message:
		h.Record(withSenderName(client, event.Info), event.Message, "")
	case *events.GroupInfo:
		if event.Name != nil {
			h.Rename(event.JID, event.Name.Name)
		}
	case *events.JoinedGroup:
		h.Rename(event.JID, event.Name)
	case *events.HistorySync:
		for _, conversation := range event.Data.GetConversations() {
			chat, err := types.ParseJID(conversation.GetID())
			if err != nil {
				continue
			}
			name := conversation.GetName()
			if name == "" {
				name = conversation.GetDisplayName()
			}
			for _, item := range conversation.GetMessages() {
				parsed, err := client.ParseWebMessage(chat, item.GetMessage())
				if err == nil && parsed != nil {
					h.Record(withSenderName(client, parsed.Info), parsed.Message, name)
				}
			}
		}
	}
}
