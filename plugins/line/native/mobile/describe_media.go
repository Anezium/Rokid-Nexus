package mobile

import (
	"encoding/base64"
	"encoding/json"
	"slices"
	"strconv"

	lineapi "github.com/beeper/line/pkg/line"
)

type mediaKeyDescription struct {
	Present           bool `json:"present"`
	DecodedByteLength int  `json:"decodedByteLength"`
}

type mediaObjectDescription struct {
	Present         bool     `json:"present"`
	JSONObjectValid bool     `json:"jsonObjectValid"`
	Keys            []string `json:"keys"`
}

type sealedBodyDescription struct {
	Present            bool `json:"present"`
	Decrypted          bool `json:"decrypted"`
	JSONObjectValid    bool `json:"jsonObjectValid"`
	KeyMaterialPresent bool `json:"keyMaterialPresent"`
}

type mediaDescription struct {
	Found               bool                   `json:"found"`
	MetadataKeys        []string               `json:"metadataKeys"`
	ENCKM               mediaKeyDescription    `json:"ENC_KM"`
	SealedBody          sealedBodyDescription  `json:"sealedBody"`
	ThumbInfo           mediaObjectDescription `json:"MEDIA_THUMB_INFO"`
	ContentInfo         mediaObjectDescription `json:"MEDIA_CONTENT_INFO"`
	E2EEVersion         string                 `json:"e2eeVersion"`
	ContentType         int                    `json:"contentType"`
	MetadataContentType string                 `json:"metadataContentType"`
	SID                 string                 `json:"SID"`
	SenderIsSelf        bool                   `json:"senderIsSelf"`
	Timestamp           int64                  `json:"timestamp"`
	TimestampValid      bool                   `json:"timestampValid"`
}

// DescribeLatestMedia inspects the newest IMAGE in the chat's recent server history.
// Only field names, structural indicators and explicit non-content scalars escape.
// Called only by opt-in instrumentation, never by the plugin's normal pages/settings.
func (e *Engine) DescribeLatestMedia(chatID string) string {
	e.op.Lock()
	defer e.op.Unlock()
	if !validMid(chatID) || e.client == nil || e.ctx.Err() != nil || e.findChat(chatID) == nil {
		return encode(map[string]any{"found": false, "reason": "session_or_chat_unavailable"})
	}
	rows, err := e.client.GetRecentMessagesV2(chatID, maxMessages)
	if err != nil {
		return encode(map[string]any{"found": false, "reason": "history_unavailable"})
	}
	var latest *lineapi.Message
	for _, row := range rows {
		if row == nil || row.ContentType != 1 {
			continue
		}
		// LINE returns newest first; equal or unavailable times keep that ordering.
		if latest == nil {
			latest = row
			continue
		}
		candidateTime, candidateValid := mediaTimestamp(row.CreatedTime)
		latestTime, latestValid := mediaTimestamp(latest.CreatedTime)
		if candidateValid && latestValid && candidateTime > latestTime {
			latest = row
		}
	}
	if latest == nil {
		return encode(map[string]any{"found": false, "reason": "no_image_in_recent_history"})
	}
	plain, decrypted := "", false
	if len(latest.Chunks) > 0 && e.crypto != nil {
		plain, err = e.decrypt(latest)
		decrypted = err == nil
	}
	return encode(describeMedia(latest, plain, decrypted, e.stored.Mid))
}

func describeMedia(row *lineapi.Message, plain string, decrypted bool, ownMID string) mediaDescription {
	key, present := row.ContentMetadata["ENC_KM"]
	keyLength := 0
	if present {
		decoded, err := base64.StdEncoding.DecodeString(key)
		if err != nil {
			keyLength = -1
		} else {
			keyLength = len(decoded)
		}
	}
	body := sealedBodyDescription{Present: len(row.Chunks) > 0}
	if body.Present && decrypted {
		body.Decrypted = true
		var fields map[string]json.RawMessage
		if json.Unmarshal([]byte(plain), &fields) == nil && fields != nil {
			body.JSONObjectValid = true
			_, body.KeyMaterialPresent = fields["keyMaterial"]
		}
	}
	timestamp, timestampValid := mediaTimestamp(row.CreatedTime)
	sid := ""
	switch row.ContentMetadata["SID"] {
	case "m", "emi", "emv", "ema", "emf":
		sid = row.ContentMetadata["SID"]
	}
	return mediaDescription{
		Found: true, MetadataKeys: diagnosticKeyNames(row.ContentMetadata),
		ENCKM: mediaKeyDescription{Present: present, DecodedByteLength: keyLength}, SealedBody: body,
		ThumbInfo:   describeMediaObject(row.ContentMetadata, "MEDIA_THUMB_INFO"),
		ContentInfo: describeMediaObject(row.ContentMetadata, "MEDIA_CONTENT_INFO"),
		E2EEVersion: diagnosticNumber(row.ContentMetadata["e2eeVersion"]), ContentType: row.ContentType,
		MetadataContentType: diagnosticNumber(row.ContentMetadata["contentType"]), SID: sid,
		SenderIsSelf: ownMID != "" && row.From == ownMID, Timestamp: timestamp, TimestampValid: timestampValid,
	}
}

func diagnosticKeyNames[V any](fields map[string]V) []string {
	keys := make([]string, 0, len(fields))
	for key := range fields {
		keys = append(keys, key)
	}
	slices.Sort(keys)
	return keys
}

func describeMediaObject(metadata map[string]string, key string) mediaObjectDescription {
	value, present := metadata[key]
	result := mediaObjectDescription{Present: present, Keys: []string{}}
	if !present {
		return result
	}
	var fields map[string]json.RawMessage
	if json.Unmarshal([]byte(value), &fields) != nil || fields == nil {
		return result
	}
	result.JSONObjectValid, result.Keys = true, diagnosticKeyNames(fields)
	return result
}

func diagnosticNumber(value string) string {
	if value == "" || len(value) > 10 {
		return ""
	}
	for _, char := range value {
		if char < '0' || char > '9' {
			return ""
		}
	}
	if _, err := strconv.ParseUint(value, 10, 32); err != nil {
		return ""
	}
	return value
}

func mediaTimestamp(value json.Number) (int64, bool) {
	timestamp, err := value.Int64()
	if err != nil || timestamp < 0 {
		return 0, false
	}
	return timestamp, true
}
