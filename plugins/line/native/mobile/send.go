package mobile

import (
	"crypto/sha256"
	"encoding/hex"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/beeper/line/pkg/e2ee"
	lineapi "github.com/beeper/line/pkg/line"
)

const maxSends = 64

func sendDigest(chatID, text string) string {
	sum := sha256.Sum256([]byte(chatID + "\x00" + text))
	return hex.EncodeToString(sum[:])
}

// Send journals an invocation before dispatch. An ambiguous request is never
// retransmitted automatically, including after process death or another page lease.
func (e *Engine) Send(invocation, id, title, text string) string {
	e.op.Lock()
	defer e.op.Unlock()
	if len(invocation) == 0 || len(invocation) > 128 || !validMid(id) || strings.TrimSpace(text) == "" || utf8.RuneCountInString(text) > 2000 {
		return "not_sent"
	}
	digest := sendDigest(id, text)
	if record, ok := e.stored.Sends[invocation]; ok {
		if record.Digest != digest {
			return "recipient_changed"
		}
		return record.State
	}
	if e.ctx.Err() != nil || e.client == nil {
		return "not_sent"
	}
	item := e.findChat(id)
	if item == nil || item.Title != title {
		return "recipient_changed"
	}
	e.mu.Lock()
	ready := e.state == "ready"
	e.mu.Unlock()
	if !ready {
		return "not_sent"
	}
	if len(e.stored.Sends) >= maxSends {
		removed := false
		for i, key := range e.stored.Order {
			if e.stored.Sends[key].State == "sent" {
				delete(e.stored.Sends, key)
				e.stored.Order = append(e.stored.Order[:i], e.stored.Order[i+1:]...)
				removed = true
				break
			}
		}
		if !removed {
			return "not_sent"
		}
	}
	msg := &lineapi.Message{From: e.stored.Mid, To: id, ContentType: 0, ContentMetadata: map[string]string{}}
	if strings.HasPrefix(strings.ToLower(id), "c") {
		msg.ToType = 2
	} else if strings.HasPrefix(strings.ToLower(id), "r") {
		msg.ToType = 1
	}
	if e.crypto == nil {
		return "not_sent"
	}
	var chunks []string
	var err error
	if item.Group {
		var key *lineapi.E2EEGroupSharedKey
		key, err = e.client.GetLastE2EEGroupSharedKey(id)
		if err == nil {
			_, err = e.crypto.UnwrapGroupSharedKey(id, key)
		}
		if err == nil {
			chunks, err = e.crypto.EncryptGroupMessage(id, e.stored.Mid, text)
		}
	} else {
		var peer *lineapi.E2EEPublicKey
		peer, err = e.client.NegotiateE2EEPublicKey(id)
		if err == nil {
			var peerID int64
			peerID, err = peer.KeyID.Int64()
			if err == nil {
				var ownRaw, ownKey int
				ownRaw, ownKey, err = e.crypto.MyKeyIDs()
				if err == nil {
					chunks, err = e.crypto.EncryptMessageV2(id, e.stored.Mid, ownKey, peer.PublicKey, ownRaw, int(peerID), 0, text)
				}
			}
		}
	}
	// Fail closed: the prototype never downgrades an encryption failure to plaintext.
	if err != nil || len(chunks) == 0 {
		return "not_sent"
	}
	msg.Chunks = chunks
	msg.ContentMetadata["e2eeVersion"] = strconv.Itoa(e2ee.ChunksVersion(chunks))
	e.stored.Sequence++
	e.stored.Sends[invocation] = sendRecord{Digest: digest, State: "unconfirmed"}
	e.stored.Order = append(e.stored.Order, invocation)
	if e.save() != nil {
		return "not_sent"
	}
	response, err := e.client.SendMessage(e.stored.Sequence, msg)
	if err != nil || response == nil || response.ID == "" {
		return "unconfirmed"
	}
	e.stored.Sends[invocation] = sendRecord{Digest: digest, State: "sent"}
	// Persisting an acknowledgement can fail after LINE accepted it: keep the
	// durable journal ambiguous, and still report the observed acknowledgement.
	_ = e.save()
	e.addMessage(id, message{ID: response.ID, Text: text, Mine: true})
	return "sent"
}
