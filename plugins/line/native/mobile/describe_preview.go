package mobile

import (
	"bytes"
	"context"
	"encoding/json"
	"image/jpeg"
	"image/png"
	"time"

	lineapi "github.com/beeper/line/pkg/line"
)

// Only the decrypted sealed-body key is used. Metadata ENC_KM is never a fallback.
// Status is a fixed enum; no locator, key, content or protocol error is returned.
func (e *Engine) probeMediaPreview(row *lineapi.Message, plain string, decrypted bool) (bool, string) {
	if _, present := row.ContentMetadata["MEDIA_THUMB_INFO"]; !present {
		return false, "no_preview_metadata"
	}
	if len(row.Chunks) == 0 || !decrypted {
		return false, "body_not_decrypted"
	}
	var body struct {
		KeyMaterial string `json:"keyMaterial"`
	}
	if json.Unmarshal([]byte(plain), &body) != nil || body.KeyMaterial == "" {
		return false, "body_key_unavailable"
	}
	oid := row.ContentMetadata["OID"]
	if oid == "" {
		return false, "missing_oid"
	}
	if e.client == nil || e.ctx.Err() != nil {
		return false, "session_unavailable"
	}
	ctx, cancel := context.WithTimeout(e.ctx, 12*time.Second)
	defer cancel()
	ciphertext, err := e.client.DownloadOBSWithOptions(ctx, oid+"__ud-preview", row.ID,
		lineapi.OBSDownloadOptions{OBSPop: row.ContentMetadata["OBS_POP"]})
	if err != nil {
		return false, "download_failed"
	}
	preview, err := decryptPhoto(ciphertext, body.KeyMaterial)
	if err != nil {
		return false, "authentication_failed"
	}
	if _, err := jpeg.DecodeConfig(bytes.NewReader(preview)); err == nil {
		return true, "verified_jpeg"
	}
	if _, err := png.DecodeConfig(bytes.NewReader(preview)); err == nil {
		return true, "verified_png"
	}
	return false, "not_jpeg_or_png"
}
