package mobile

import (
	"encoding/json"
	"errors"
	"slices"
	"strings"
	"time"

	"github.com/beeper/line/pkg/e2ee"
	lineapi "github.com/beeper/line/pkg/line"
)

const maxChats = 48
const maxMessages = 24

func (e *Engine) loadChats() error {
	// Match the reference client's inbox request; history is fetched separately.
	boxes, err := e.client.GetMessageBoxes(lineapi.MessageBoxesOptions{ActiveOnly: true, MessageBoxCountLimit: 100, WithUnreadCount: true, LastMessagesPerMessageBoxCount: 0})
	if err != nil {
		return err
	}
	inboxEntries, contactEntries, excludedEntries := len(boxes.MessageBoxes), 0, 0
	var excludedIdLengths []int
	// New accounts can have contacts before Chrome's recent inbox exposes a
	// thread. Resolve those contacts without inventing ids or including blocks.
	if len(boxes.MessageBoxes) == 0 {
		ids, err := e.client.GetAllContactIds()
		if err != nil {
			return err
		}
		contactEntries = len(ids)
		blocked, err := e.client.GetBlockedContactIds()
		if err != nil {
			return err
		}
		excluded := map[string]bool{e.stored.Mid: true}
		for _, id := range blocked {
			excluded[id] = true
		}
		for _, id := range ids {
			if excluded[id] || !validMid(id) || !strings.HasPrefix(strings.ToLower(id), "u") {
				excludedEntries++
				continue
			}
			excluded[id] = true
			boxes.MessageBoxes = append(boxes.MessageBoxes, lineapi.MessageBox{ID: id})
			if len(boxes.MessageBoxes) >= maxChats {
				break
			}
		}
	}
	chats := make([]chat, 0, min(len(boxes.MessageBoxes), maxChats))
	e.mu.Lock()
	previous := make(map[string][]message, len(e.chats))
	for _, item := range e.chats {
		previous[item.ID] = item.History
	}
	e.mu.Unlock()
	for _, box := range boxes.MessageBoxes[:min(len(boxes.MessageBoxes), maxChats)] {
		if e.ctx.Err() != nil {
			return e.ctx.Err()
		}
		if !validMid(box.ID) {
			excludedEntries++
			excludedIdLengths = append(excludedIdLengths, len(box.ID))
			continue
		}
		item := chat{ID: box.ID, Title: "Conversation", Group: !strings.HasPrefix(strings.ToLower(box.ID), "u"), History: []message{}}
		if history, ok := previous[box.ID]; ok {
			item.History = history
		}
		if item.Group {
			if info, err := e.client.GetChats([]string{box.ID}, false, false); err == nil && len(info.Chats) > 0 {
				item.Title = info.Chats[0].ChatName
			}
		} else {
			if info, err := e.client.GetContactsV2([]string{box.ID}); err == nil {
				if contact, ok := info.Contacts[box.ID]; ok {
					item.Title = contact.Contact.EffectiveDisplayName()
				}
			}
		}
		if item.Title == "" {
			item.Title = "Conversation"
		}
		chats = append(chats, item)
	}
	e.mu.Lock()
	e.chats = chats
	e.inboxEntries, e.contactEntries, e.excludedEntries = inboxEntries, contactEntries, excludedEntries
	e.excludedIdLengths = excludedIdLengths
	e.mu.Unlock()
	return nil
}

// History is pulled only when the wearer opens that conversation.
func (e *Engine) LoadHistory(id string) error {
	e.op.Lock()
	defer e.op.Unlock()
	if e.client == nil || e.ctx.Err() != nil || e.findChat(id) == nil {
		return errors.New("conversation unavailable")
	}
	rows, err := e.client.GetRecentMessagesV2(id, maxMessages)
	if err != nil {
		return errors.New("history unavailable")
	}
	history := make([]message, 0, len(rows))
	for _, row := range rows {
		if row != nil {
			decoded := e.decode(row)
			e.confirmOwnImage(id, decoded)
			history = append(history, decoded)
		}
	}
	// LINE returns newest first. The conversation template expects oldest first.
	slices.Reverse(history)
	e.mu.Lock()
	for i := range e.chats {
		if e.chats[i].ID == id {
			e.chats[i].History = history
		}
	}
	e.mu.Unlock()
	e.changed()
	return nil
}

func validMid(mid string) bool {
	if (len(mid) != 33 && len(mid) != 44) || !strings.ContainsRune("uUcCrR", rune(mid[0])) {
		return false
	}
	for _, r := range mid[1:] {
		if !(r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '-' || r == '_') {
			return false
		}
	}
	return true
}

func (e *Engine) findChat(id string) *chat {
	e.mu.Lock()
	defer e.mu.Unlock()
	for _, item := range e.chats {
		if item.ID == id {
			return &item
		}
	}
	return nil
}

func (e *Engine) decode(row *lineapi.Message) message {
	text := row.Text
	plain := ""
	if len(row.Chunks) > 0 {
		if e.crypto == nil {
			return message{ID: row.ID, Text: "[Unable to decrypt message]", Mine: row.From == e.stored.Mid}
		}
		var err error
		plain, err = e.decrypt(row)
		var payload struct {
			Text string `json:"text"`
		}
		if err != nil || json.Unmarshal([]byte(plain), &payload) != nil {
			return message{ID: row.ID, Text: "[Unable to decrypt message]", Mine: row.From == e.stored.Mid}
		}
		text = payload.Text
	}
	item := message{ID: row.ID, Text: text, Mine: row.From == e.stored.Mid}
	if row.ContentType != 0 {
		item.Text, item.Media, item.source = decodeMedia(row, plain)
	}
	return item
}

func (e *Engine) decrypt(row *lineapi.Message) (string, error) {
	if len(row.Chunks) < 5 {
		return "", errors.New("invalid encrypted message")
	}
	sender, err := e2ee.DecodeKeyID(row.Chunks[len(row.Chunks)-2])
	if err != nil {
		return "", err
	}
	if !e.crypto.IsMyKey(sender) && !e.crypto.HasPeerPublicKey(sender) {
		key, err := e.client.GetE2EEPublicKey(row.From, 1, sender)
		if err != nil {
			return "", err
		}
		actual, err := key.KeyID.Int64()
		if err != nil || actual != int64(sender) {
			return "", errors.New("sender key mismatch")
		}
		e.crypto.RegisterPeerPublicKey(sender, key.PublicKey)
	}
	if row.ToType == 1 || row.ToType == 2 {
		keyID, err := e2ee.DecodeKeyID(row.Chunks[len(row.Chunks)-1])
		if err != nil {
			return "", err
		}
		shared, err := e.client.GetE2EEGroupSharedKey(row.To, keyID)
		if err != nil {
			return "", err
		}
		if _, err = e.crypto.UnwrapGroupSharedKey(row.To, shared); err != nil {
			return "", err
		}
		text, _, err := e.crypto.DecryptGroupMessage(row, row.To)
		return text, err
	}
	// Echoes sent by our other devices need the recipient's public key.
	if e.crypto.IsMyKey(sender) {
		receiver, err := e2ee.DecodeKeyID(row.Chunks[len(row.Chunks)-1])
		if err != nil {
			return "", err
		}
		if !e.crypto.IsMyKey(receiver) && !e.crypto.HasPeerPublicKey(receiver) {
			key, err := e.client.GetE2EEPublicKey(row.To, 1, receiver)
			if err != nil {
				return "", err
			}
			actual, err := key.KeyID.Int64()
			if err != nil || actual != int64(receiver) {
				return "", errors.New("recipient key mismatch")
			}
			e.crypto.RegisterPeerPublicKey(receiver, key.PublicKey)
		}
	}
	return e.crypto.DecryptMessageV2(row)
}

func (e *Engine) listen(client *lineapi.Client, revision int64) {
	for attempt := 0; e.ctx.Err() == nil; attempt++ {
		err := client.ListenSSE(e.ctx, revision, func(event, data string) {
			if event == lineapi.SSEEventOpen {
				e.setState("ready")
				return
			}
			if event == "fullSync" {
				e.op.Lock()
				defer e.op.Unlock()
				var payload struct {
					NextRevision json.Number `json:"nextRevision"`
				}
				if json.Unmarshal([]byte(data), &payload) == nil {
					if rev, err := payload.NextRevision.Int64(); err == nil {
						revision = max(revision, rev)
					}
				}
				if e.ctx.Err() == nil && e.loadChats() == nil {
					e.changed()
				}
				return
			}
			var op lineapi.Operation
			if event != "operation" || json.Unmarshal([]byte(data), &op) != nil {
				return
			}
			e.op.Lock()
			defer e.op.Unlock()
			if e.ctx.Err() != nil {
				return
			}
			if rev, err := op.Revision.Int64(); err == nil {
				revision = max(revision, rev)
			}
			if op.Type == 64 || op.Type == 65 {
				e.removeMessage(op.Param1, op.Param2)
				return
			}
			if op.Message == nil || (op.Type != 25 && op.Type != 26) {
				return
			}
			row := op.Message
			id := row.To
			if row.ToType == 0 && row.From != e.stored.Mid {
				id = row.From
			}
			if e.findChat(id) == nil {
				if e.loadChats() == nil {
					e.addMessage(id, e.decode(row))
				}
				return
			}
			e.addMessage(id, e.decode(row))
		})
		if e.ctx.Err() != nil {
			return
		}
		if lineapi.IsLoggedOut(err) {
			e.setState("failed")
			return
		}
		e.setState("offline")
		timer := time.NewTimer(min(time.Second<<min(attempt, 5), 30*time.Second))
		select {
		case <-e.ctx.Done():
			timer.Stop()
			return
		case <-timer.C:
		}
	}
}

func (e *Engine) removeMessage(chatID, messageID string) {
	e.mu.Lock()
	for i := range e.chats {
		if e.chats[i].ID == chatID {
			e.chats[i].History = slices.DeleteFunc(e.chats[i].History, func(row message) bool { return row.ID == messageID })
		}
	}
	e.mu.Unlock()
	e.changed()
}

func (e *Engine) addMessage(id string, row message) {
	e.confirmOwnImage(id, row)
	e.mu.Lock()
	for i := range e.chats {
		if e.chats[i].ID != id {
			continue
		}
		history := e.chats[i].History
		found := false
		for j := range history {
			if history[j].ID == row.ID {
				history[j] = row
				found = true
				break
			}
		}
		if !found {
			history = append(history, row)
		}
		if len(history) > maxMessages {
			history = history[len(history)-maxMessages:]
		}
		e.chats[i].History = history
	}
	e.mu.Unlock()
	e.changed()
}
