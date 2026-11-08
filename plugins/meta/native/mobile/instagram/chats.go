package instagram

import (
	"context"
	"errors"
	"sort"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"go.mau.fi/mautrix-meta/pkg/instameow/slidetypes"
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

// chat is what the glasses list shows; the id is the thread's IG id used for sending.
type chat struct {
	ID        string    `json:"id"`
	Title     string    `json:"title"`
	Group     bool      `json:"group"`
	Supported bool      `json:"supported"`
	Messages  []message `json:"messages"`
	at        int64
}

// visible reports whether a name draws anything: names made only of blank fillers (U+3164 and
// the like) or format characters are common on Instagram and read as an empty title.
func visible(name string) bool {
	for _, r := range name {
		switch {
		case unicode.IsSpace(r), unicode.Is(unicode.Cf, r), unicode.Is(unicode.Mn, r):
		case r == '\u3164', r == '\u115f', r == '\u1160', r == '\uffa0', r == '\u2800':
		default:
			return true
		}
	}
	return false
}

func threadTitle(thread *slidetypes.ThreadInfo) string {
	if title := strings.TrimSpace(thread.ThreadTitle); visible(title) {
		return title
	}
	names := make([]string, 0, len(thread.Users))
	for _, user := range thread.Users {
		if user == nil {
			continue
		}
		if name := strings.TrimSpace(user.FullName); visible(name) {
			names = append(names, name)
		} else if visible(user.Username) {
			names = append(names, user.Username)
		}
	}
	if len(names) == 0 {
		return "You"
	}
	return strings.Join(names, ", ")
}

func senderName(thread *slidetypes.ThreadInfo, fbid int64) string {
	for _, user := range thread.Users {
		if user != nil && user.InteropMessagingUserFBID == fbid {
			if name := strings.TrimSpace(user.FullName); name != "" {
				return name
			}
			return user.Username
		}
	}
	return ""
}

// threadMessages returns up to limit messages the mailbox carries for a thread, oldest first.
func threadMessages(thread *slidetypes.ThreadInfo, ownFBID int64, limit int) []message {
	out := []message{}
	if thread.SlideMessages == nil {
		return out
	}
	for _, edge := range thread.SlideMessages.Edges {
		m := edge.Node
		if m == nil || m.ID == "" {
			continue
		}
		text, preview := strings.TrimSpace(m.TextBody), ""
		if text == "" {
			text, preview = describeContent(m)
		}
		if text == "" {
			continue
		}
		item := message{ID: m.ID, Mine: ownFBID > 0 && m.SenderFBID == ownFBID, Text: text, Preview: preview, at: m.TimestampMS.UnixMilli()}
		if !item.Mine && thread.IsGroup {
			item.Sender = senderName(thread, m.SenderFBID)
		}
		out = append(out, item)
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].at < out[j].at })
	if len(out) > limit {
		out = out[len(out)-limit:]
	}
	return out
}

// mailboxChats lists the most recent threads of the mailbox loaded at connect time.
func mailboxChats(mailbox *slidetypes.Mailbox) []chat { return mailboxChatsFor(mailbox, 0, maxHistory) }

func mailboxChatsFor(mailbox *slidetypes.Mailbox, ownFBID int64, limit int) []chat {
	if mailbox == nil {
		return nil
	}
	seen := map[string]bool{}
	var chats []chat
	for _, edge := range mailbox.ThreadsByFolder.Edges {
		thread := edge.Node.AsIGDirectThread
		if thread == nil || thread.ID == "" || seen[thread.ID] {
			continue
		}
		seen[thread.ID] = true
		chats = append(chats, chat{ID: thread.ID, Title: threadTitle(thread), Group: thread.IsGroup, Supported: true,
			Messages: threadMessages(thread, ownFBID, limit), at: thread.LastActivityTimestampMS.UnixMilli()})
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
	chats := mailboxChatsFor(e.conn.mailbox, e.conn.client.GetOwnFBID(), e.historyLimit)
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

// LoadOlder fetches one conversation again with as many messages as the history limit
// asks for, and keeps them for later Chats calls. Contents are never logged.
func (e *Engine) LoadOlder(chatID string) error {
	e.mu.Lock()
	conn, limit := e.conn, e.historyLimit
	e.mu.Unlock()
	if conn == nil || !conn.isConnected() {
		return errors.New("not connected")
	}
	request := slidetypes.MakeGetThreadInfoRequest(chatID)
	request.InitialMessagePageCount = limit
	ctx, cancel := context.WithTimeout(e.ctx, 15*time.Second)
	defer cancel()
	response, err := conn.client.GetThread(ctx, request)
	if err != nil || response == nil || response.ThreadInfo.AsIGDirectThread == nil || response.ThreadInfo.AsIGDirectThread.SlideMessages == nil {
		return errors.New("history unavailable")
	}
	fetched := response.ThreadInfo.AsIGDirectThread.SlideMessages
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.conn != conn || conn.mailbox == nil {
		return errors.New("not connected")
	}
	for _, edge := range conn.mailbox.ThreadsByFolder.Edges {
		if thread := edge.Node.AsIGDirectThread; thread != nil && thread.ID == chatID {
			thread.SlideMessages = fetched
			return nil
		}
	}
	return errors.New("conversation not listed")
}

// SendText sends one owner-confirmed message to a conversation the owner picked.
// The conversation must still be listed under the same title it was shown with.
func (e *Engine) SendText(id, chatID, title, text string) string {
	target, reason := e.target(id)
	if target == nil {
		return notSent(reason)
	}
	if strings.TrimSpace(text) == "" || !utf8.ValidString(text) || utf8.RuneCountInString(text) > 1000 {
		return notSent("invalid_text")
	}
	found := false
	for _, c := range mailboxChats(target.conn.mailbox) {
		if c.ID == chatID && c.Title == title {
			found = true
			break
		}
	}
	if !found {
		return notSent("recipient_changed")
	}
	client := target.conn.client
	thread := chatID
	return e.dispatch(target, e.admissionPath(target.scope, id), func(ctx context.Context, otid string) (slidetypes.SentMessage, error) {
		response, err := client.SendMessage(ctx, &slidetypes.SendTextRequest{
			IGThreadIGID: &thread, OfflineThreadingID: otid,
			Text:     slidetypes.SensitiveString{Value: text},
			Mentions: []slidetypes.InputMention{}, MentionedUserIDs: []string{}, Commands: []slidetypes.InputCommand{},
		})
		if err != nil || response == nil {
			return slidetypes.SentMessage{}, errors.New("unconfirmed")
		}
		return response.Message, nil
	})
}
