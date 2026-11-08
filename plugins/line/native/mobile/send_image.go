package mobile

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"strconv"
	"strings"
	"time"

	"github.com/beeper/line/pkg/e2ee"
	lineapi "github.com/beeper/line/pkg/line"
)

const maxImageBytes = 6 * 1024 * 1024

// Diagnostic selector: true = A (raw ENC_KM, no preview), false = B (preview,
// sealed-body key only). B deliberately reproduces the reference's CTR reuse.
const diagnosticImageKeyInMetadata = false

// Retain only the encrypted upload's locator/key, never the captured JPEG.
// This also ties a late own-message echo to an ambiguous invocation after reopening.
type sentImage struct {
	ChatID      string `json:"chatId"`
	OID         string `json:"oid"`
	KeyMaterial string `json:"keyMaterial"`
	MessageID   string `json:"messageId,omitempty"`
	Preview     bool   `json:"preview,omitempty"`
}

func imageDigest(chatID string, jpeg []byte) string {
	hash := sha256.New()
	hash.Write([]byte("image\x00" + chatID + "\x00"))
	hash.Write(jpeg)
	return hex.EncodeToString(hash.Sum(nil))
}

// SendImage sends one JPEG the wearer just took on the glasses. Same ownership,
// idempotency, fail-closed and bookkeeping rules as Send.
func (e *Engine) SendImage(invocation, chatID, chatTitle string, jpeg []byte) string {
	e.op.Lock()
	defer e.op.Unlock()
	if invocation == "" || len(invocation) > 128 || !validMid(chatID) || len(jpeg) == 0 || len(jpeg) > maxImageBytes {
		return "not_sent"
	}
	digest := imageDigest(chatID, jpeg)
	if outcome, known := e.replaySend(invocation, digest); known {
		if record := e.stored.Sends[invocation]; outcome == "sent" && record.Image != nil {
			e.appendOwnImage(record.Image)
		}
		return outcome
	}
	if e.ctx.Err() != nil || e.client == nil || e.crypto == nil {
		return "not_sent"
	}
	item := e.findChat(chatID)
	if item == nil || item.Title != chatTitle {
		return "recipient_changed"
	}
	e.mu.Lock()
	ready := e.state == "ready"
	e.mu.Unlock()
	if !ready || !e.makeSendRoom() || !validImageJPEG(jpeg) {
		return "not_sent"
	}
	seal, err := e.prepareImageSealer(chatID, item.Group)
	if err != nil {
		return "not_sent"
	}
	return e.sendPreparedImage(invocation, chatID, digest, jpeg, seal)
}

// The caller holds op and has validated the recipient and its Letter Sealing key.
func (e *Engine) sendPreparedImage(invocation, chatID, digest string, jpeg []byte, seal func([]byte) ([]string, error)) string {
	flow, err := e.client.DetermineMediaMessageFlow(chatID)
	if err != nil || flow == nil {
		return "not_sent"
	}
	if selected, exists := flow.FlowMap["1"]; exists && selected != 2 {
		return "not_sent"
	}

	// Variant B reproduces the pinned reference's encrypted preview without
	// exposing ENC_KM. Its key/nonce reuse is an explicit diagnostic limitation.
	upload, keyMaterial, err := encryptFileData(jpeg)
	if err != nil {
		return "not_sent"
	}
	oid, err := e.client.UploadOBS(upload)
	if err != nil || oid == "" {
		return "not_sent"
	}
	metadata := map[string]string{
		"OID": oid, "SID": "emi", "FILE_SIZE": strconv.Itoa(len(upload)),
		"contentType": "1", "FILE_NAME": "image.jpg",
		"MEDIA_CONTENT_INFO": encode(map[string]any{"category": "original", "fileSize": len(upload), "extension": "jpg"}),
	}
	if diagnosticImageKeyInMetadata {
		metadata["ENC_KM"] = keyMaterial
	}
	previewUploaded := false
	if !diagnosticImageKeyInMetadata {
		if preview, width, height, err := generateThumbnail(jpeg); err == nil {
			if encrypted, err := encryptThumbnail(preview, keyMaterial); err == nil {
				if e.client.UploadOBSWithOID(encrypted, oid+"__ud-preview") == nil {
					previewUploaded = true
					metadata["MEDIA_THUMB_INFO"] = encode(map[string]int{"width": width, "height": height})
				}
			}
		}
	}
	payload, _ := json.Marshal(map[string]string{"keyMaterial": keyMaterial})
	chunks, err := seal(payload)
	if err != nil || len(chunks) == 0 || e.ctx.Err() != nil {
		return "not_sent"
	}
	metadata["e2eeVersion"] = strconv.Itoa(e2ee.ChunksVersion(chunks))
	now := strconv.FormatInt(time.Now().UnixMilli(), 10)
	msg := &lineapi.Message{ID: "local-" + now, From: e.stored.Mid, To: chatID,
		CreatedTime: json.Number(now), ContentType: 1, HasContent: true, ContentMetadata: metadata, Chunks: chunks}
	if strings.HasPrefix(strings.ToLower(chatID), "c") {
		msg.ToType = 2
	} else if strings.HasPrefix(strings.ToLower(chatID), "r") {
		msg.ToType = 1
	}
	image := &sentImage{ChatID: chatID, OID: oid, KeyMaterial: keyMaterial, Preview: previewUploaded}
	record := sendRecord{Digest: digest, State: "unconfirmed", Image: image}
	if !e.journalSend(invocation, record) {
		return "not_sent"
	}
	response, err := e.client.SendMessage(e.stored.Sequence, msg)
	if err != nil || response == nil || response.ID == "" {
		return "unconfirmed"
	}
	image.MessageID = response.ID
	record.State = "sent"
	e.stored.Sends[invocation] = record
	_ = e.save()
	e.appendOwnImage(image)
	return "sent"
}

func (e *Engine) prepareImageSealer(chatID string, group bool) (func([]byte) ([]string, error), error) {
	if e.crypto == nil {
		return nil, errors.New("Letter Sealing unavailable")
	}
	if group {
		key, err := e.client.GetLastE2EEGroupSharedKey(chatID)
		if err != nil || key == nil {
			return nil, errors.New("group key unavailable")
		}
		if _, err := e.crypto.UnwrapGroupSharedKey(chatID, key); err != nil {
			return nil, err
		}
		return func(payload []byte) ([]string, error) {
			return e.crypto.EncryptGroupMessageRaw(chatID, e.stored.Mid, 1, payload)
		}, nil
	}
	peer, err := e.client.NegotiateE2EEPublicKey(chatID)
	if err != nil || peer == nil || peer.Expired {
		return nil, errors.New("peer key unavailable")
	}
	peerID, err := peer.KeyID.Int64()
	if err != nil || peerID <= 0 || peerID > int64(^uint32(0)) {
		return nil, errors.New("peer key invalid")
	}
	ownRaw, ownKey, err := e.crypto.MyKeyIDs()
	if err != nil {
		return nil, err
	}
	return func(payload []byte) ([]string, error) {
		return e.crypto.EncryptMessageV2Raw(chatID, e.stored.Mid, ownKey, peer.PublicKey, ownRaw, int(peerID), 1, payload)
	}, nil
}

func (e *Engine) appendOwnImage(image *sentImage) {
	if image.MessageID == "" {
		return
	}
	source := &mediaSource{oid: image.OID, messageID: image.MessageID, keys: []string{image.KeyMaterial}, encrypted: true}
	if image.Preview {
		source.previewOID = image.OID + "__ud-preview"
	}
	e.addMessage(image.ChatID, message{ID: image.MessageID, Text: "[Photo]", Mine: true, Media: "photo", source: source})
}

// Called under op by history loading and SSE, never by an automatic resend.
func (e *Engine) confirmOwnImage(chatID string, row message) {
	if !row.Mine || row.Media != "photo" || row.ID == "" || row.source == nil || row.source.oid == "" {
		return
	}
	for invocation, record := range e.stored.Sends {
		image := record.Image
		if image == nil || image.ChatID != chatID || image.OID != row.source.oid {
			continue
		}
		if image.Preview {
			row.source.previewOID = image.OID + "__ud-preview"
		}
		if record.State != "sent" || image.MessageID != row.ID {
			record.State, image.MessageID = "sent", row.ID
			e.stored.Sends[invocation] = record
			_ = e.save()
		}
		return
	}
}
