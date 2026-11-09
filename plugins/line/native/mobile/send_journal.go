package mobile

import (
	"crypto/sha256"
	"encoding/hex"
)

const maxSends = 64
const maxSendTombstones = 1024

// Hashing invocation ids keeps compact retention independent of JSON escaping.
type sendTombstone struct {
	InvocationHash string `json:"id"`
	Digest         string `json:"digest"`
	State          string `json:"state"`
}

func invocationHash(invocation string) string {
	digest := sha256.Sum256([]byte(invocation))
	return hex.EncodeToString(digest[:])
}

func (e *Engine) replaySend(invocation, digest string) (string, bool) {
	if record, known := e.stored.Sends[invocation]; known {
		if record.Digest != digest {
			return "recipient_changed", true
		}
		return record.State, true
	}
	id := invocationHash(invocation)
	for i := len(e.stored.Tombstones) - 1; i >= 0; i-- {
		record := e.stored.Tombstones[i]
		if record.InvocationHash != id {
			continue
		}
		if record.Digest != digest {
			return "recipient_changed", true
		}
		return record.State, true
	}
	return "", false
}

func (e *Engine) makeSendRoom() bool {
	if len(e.stored.Sends) < maxSends {
		return true
	}
	for i, invocation := range e.stored.Order {
		record := e.stored.Sends[invocation]
		if record.State != "sent" {
			continue
		}
		e.stored.Tombstones = append(e.stored.Tombstones, sendTombstone{
			InvocationHash: invocationHash(invocation), Digest: record.Digest, State: record.State,
		})
		if len(e.stored.Tombstones) > maxSendTombstones {
			e.stored.Tombstones = e.stored.Tombstones[len(e.stored.Tombstones)-maxSendTombstones:]
		}
		delete(e.stored.Sends, invocation)
		e.stored.Order = append(e.stored.Order[:i], e.stored.Order[i+1:]...)
		return true
	}
	return false
}

// The caller holds op and has checked replay. No network dispatch may precede this.
func (e *Engine) journalSend(invocation string, record sendRecord) bool {
	sequence := e.stored.Sequence
	e.stored.Sequence++
	e.stored.Sends[invocation] = record
	e.stored.Order = append(e.stored.Order, invocation)
	if e.save() == nil {
		return true
	}
	delete(e.stored.Sends, invocation)
	e.stored.Order = e.stored.Order[:len(e.stored.Order)-1]
	e.stored.Sequence = sequence
	return false
}
