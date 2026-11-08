package messenger

import (
	"context"
	"errors"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"go.mau.fi/mautrix-meta/pkg/messagix/methods"
	"go.mau.fi/mautrix-meta/pkg/messagix/socket"
	"go.mau.fi/mautrix-meta/pkg/messagix/table"
)

const (
	maxChats   = 40
	maxHistory = 60
)

// message is one already-loaded message shown in the conversation; never logged.
type message struct {
	ID     string `json:"id"`
	Mine   bool   `json:"mine"`
	Text   string `json:"text"`
	Sender string `json:"sender,omitempty"`
	// Preview is the image URL a later thumbnail can be fetched from; never logged.
	Preview string `json:"preview,omitempty"`
	at      int64
}

// chat is what the glasses list shows. Encrypted chats are listed but not sendable:
// they need the end-to-end encrypted transport this engine does not have yet.
type chat struct {
	ID        string    `json:"id"`
	Title     string    `json:"title"`
	Group     bool      `json:"group"`
	Supported bool      `json:"supported"`
	Messages  []message `json:"messages"`
	at        int64
}

func inboxChats(inbox *table.LSTable, ownFBID int64) []chat {
	return inboxChatsFor(inbox, ownFBID, maxHistory)
}

func inboxChatsFor(inbox *table.LSTable, ownFBID int64, limit int) []chat {
	if inbox == nil {
		return nil
	}
	names := map[int64]string{}
	for _, contact := range inbox.LSVerifyContactRowExists {
		if contact != nil && contact.Name != "" {
			names[contact.ContactId] = contact.Name
		}
	}
	for _, contact := range inbox.LSDeleteThenInsertContact {
		if contact != nil && contact.Name != "" {
			names[contact.Id] = contact.Name
		}
	}
	history := map[int64][]message{}
	seenMessage := map[string]bool{}
	attachments := attachmentLines(inbox)
	add := func(id, text string, key, sender, at int64, admin bool, unsent int64) {
		if id == "" || seenMessage[id] || admin || unsent != 0 {
			return
		}
		seenMessage[id] = true
		text = strings.TrimSpace(text)
		preview := ""
		if line, ok := attachments[id]; ok {
			preview = line.preview
			if text == "" {
				text = line.text
			} else if line.text != "[Attachment]" && !strings.HasPrefix(line.text, "[") {
				// A shared link keeps the text the sender wrote above its description.
				text += "\n" + line.text
			}
		}
		if text == "" {
			text = "[Attachment]"
		}
		history[key] = append(history[key], message{ID: id, Mine: sender == ownFBID, Text: text, Sender: names[sender], Preview: preview, at: at})
	}
	for _, m := range inbox.LSUpsertMessage {
		if m != nil {
			add(m.MessageId, m.Text, m.ThreadKey, m.SenderId, m.TimestampMs, m.IsAdminMessage, m.UnsentTimestampMs)
		}
	}
	for _, m := range inbox.LSInsertMessage {
		if m != nil {
			add(m.MessageId, m.Text, m.ThreadKey, m.SenderId, m.TimestampMs, m.IsAdminMessage, m.UnsentTimestampMs)
		}
	}
	seen := map[int64]bool{}
	var chats []chat
	for _, thread := range inbox.LSDeleteThenInsertThread {
		if thread == nil || thread.ThreadKey == 0 || seen[thread.ThreadKey] {
			continue
		}
		seen[thread.ThreadKey] = true
		title := strings.TrimSpace(thread.ThreadName)
		if title == "" && thread.ThreadType.IsOneToOne() {
			if thread.ThreadKey == ownFBID {
				title = "You"
			} else {
				title = names[thread.ThreadKey]
			}
		}
		if title == "" {
			continue
		}
		messages := history[thread.ThreadKey]
		sort.SliceStable(messages, func(i, j int) bool { return messages[i].at < messages[j].at })
		if len(messages) > limit {
			messages = messages[len(messages)-limit:]
		}
		group := !thread.ThreadType.IsOneToOne()
		for i := range messages {
			if messages[i].Mine || !group {
				messages[i].Sender = ""
			}
		}
		if messages == nil {
			messages = []message{}
		}
		chats = append(chats, chat{
			ID: strconv.FormatInt(thread.ThreadKey, 10), Title: title, Group: group,
			Supported: !thread.ThreadType.IsWhatsApp(), Messages: messages, at: thread.LastActivityTimestampMs,
		})
	}
	sort.SliceStable(chats, func(i, j int) bool { return chats[i].at > chats[j].at })
	if len(chats) > maxChats {
		chats = chats[:maxChats]
	}
	return chats
}

// Chats lists recent conversations from the current connection, without new network calls.
func (e *Engine) Chats() (string, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.conn == nil || !e.conn.isConnected() || e.connState != "connected" {
		return "", errors.New("not connected")
	}
	e.conn.tableMu.Lock()
	chats := inboxChatsFor(e.conn.table, e.conn.session.FBID, e.historyLimit)
	e.conn.tableMu.Unlock()
	if chats == nil {
		chats = []chat{}
	}
	return encode(chats), nil
}

// SetHistoryLimit sets how many recent messages Chats returns per conversation.
func (e *Engine) SetHistoryLimit(n int64) {
	if n < 1 || n > maxHistory {
		return
	}
	e.mu.Lock()
	e.historyLimit = int(n)
	e.mu.Unlock()
}

// LoadOlder asks Messenger for the messages before the oldest one known for a
// conversation. They arrive in the response or in later updates, and show up in a
// following Chats call. Contents are never logged.
func (e *Engine) LoadOlder(chatID string) error {
	e.mu.Lock()
	conn := e.conn
	e.mu.Unlock()
	key, err := strconv.ParseInt(chatID, 10, 64)
	if conn == nil || !conn.isConnected() || err != nil {
		return errors.New("not connected")
	}
	var oldestAt int64
	oldestID := ""
	conn.tableMu.Lock()
	if conn.table != nil {
		visit := func(id string, thread, at int64) {
			if thread == key && id != "" && (oldestID == "" || at < oldestAt) {
				oldestID, oldestAt = id, at
			}
		}
		for _, m := range conn.table.LSUpsertMessage {
			if m != nil {
				visit(m.MessageId, m.ThreadKey, m.TimestampMs)
			}
		}
		for _, m := range conn.table.LSInsertMessage {
			if m != nil {
				visit(m.MessageId, m.ThreadKey, m.TimestampMs)
			}
		}
	}
	conn.tableMu.Unlock()
	if oldestID == "" {
		return errors.New("no anchor message")
	}
	ctx, cancel := context.WithTimeout(e.ctx, 15*time.Second)
	defer cancel()
	response, err := conn.client.ExecuteTasks(ctx, &socket.FetchMessagesTask{
		ThreadKey: key, Direction: 0, ReferenceTimestampMs: oldestAt, ReferenceMessageId: oldestID,
		SyncGroup: 1, Cursor: conn.client.GetCursor(1),
	})
	if err != nil {
		return errors.New("history unavailable")
	}
	conn.merge(response)
	return nil
}

// SendText sends one owner-confirmed message to a conversation the owner picked.
// The conversation must still be listed, sendable, and under the same title.
func (e *Engine) SendText(id, chatID, title, text string) string {
	conn, reason := e.target(id)
	if conn == nil {
		return notSent(reason)
	}
	if strings.TrimSpace(text) == "" || !utf8.ValidString(text) || utf8.RuneCountInString(text) > 1000 {
		return notSent("invalid_text")
	}
	var key int64
	conn.tableMu.Lock()
	listed := inboxChats(conn.table, conn.session.FBID)
	conn.tableMu.Unlock()
	for _, c := range listed {
		if c.ID == chatID && c.Title == title {
			if !c.Supported {
				return notSent("encrypted_chat_unsupported")
			}
			key, _ = strconv.ParseInt(c.ID, 10, 64)
		}
	}
	if key == 0 {
		return notSent("recipient_changed")
	}
	task := selfTask(key, methods.GenerateEpochID())
	task.Text = text
	return e.dispatch(conn, id, task)
}
