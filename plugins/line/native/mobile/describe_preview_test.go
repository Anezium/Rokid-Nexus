package mobile

import (
	"bytes"
	"encoding/json"
	"image"
	"image/png"
	"net/http"
	"strings"
	"testing"

	lineapi "github.com/beeper/line/pkg/line"
)

func TestPreviewProbeAuthenticatesJPEGAndPNGWithOnlyTheSealedBodyKey(t *testing.T) {
	for _, format := range []string{"jpeg", "png"} {
		t.Run(format, func(t *testing.T) {
			f := newImageFixture(t, false)
			if outcome := f.send(); outcome != "sent" {
				t.Fatal(outcome)
			}
			var body map[string]string
			if err := json.Unmarshal(f.payload, &body); err != nil {
				t.Fatal(err)
			}
			if format == "png" {
				var output bytes.Buffer
				if err := png.Encode(&output, image.NewRGBA(image.Rect(0, 0, 2, 2))); err != nil {
					t.Fatal(err)
				}
				sealed, err := encryptThumbnail(output.Bytes(), body["keyMaterial"])
				if err != nil {
					t.Fatal(err)
				}
				f.objects["preview"] = sealed
			}
			verified, status := f.e.probeMediaPreview(f.message, string(f.payload), true)
			if !verified || status != "verified_"+format {
				t.Fatalf("probe: %v, %s", verified, status)
			}
			description := describeMedia(f.message, string(f.payload), true, f.e.stored.Mid)
			description.PreviewDecryptsWithBodyKey, description.PreviewProbeStatus = verified, status
			encoded := encode(description)
			for _, forbidden := range []string{body["keyMaterial"], f.message.ID, f.e.stored.Mid, string(f.objects["preview"])} {
				if forbidden != "" && strings.Contains(encoded, forbidden) {
					t.Fatal("preview probe emitted a sensitive value")
				}
			}
			if !strings.Contains(encoded, `"previewDecryptsWithBodyKey":true`) {
				t.Fatal("verified boolean missing from JSON")
			}
		})
	}
}

func TestPreviewProbeReportsRedactedFailureAndInconclusiveStatuses(t *testing.T) {
	for _, scenario := range []struct {
		name, status string
		fetch        bool
	}{
		{"no-preview", "no_preview_metadata", false}, {"not-decrypted", "body_not_decrypted", false},
		{"no-body-key", "body_key_unavailable", false}, {"no-oid", "missing_oid", false},
		{"closed", "session_unavailable", false}, {"download", "download_failed", true},
		{"wrong-body-key", "authentication_failed", true}, {"tampered", "authentication_failed", true},
		{"not-image", "not_jpeg_or_png", true}, {"plain-preview", "authentication_failed", true},
	} {
		t.Run(scenario.name, func(t *testing.T) {
			f := newImageFixture(t, false)
			if outcome := f.send(); outcome != "sent" {
				t.Fatal(outcome)
			}
			row := *f.message
			row.ContentMetadata = make(map[string]string)
			for key, value := range f.message.ContentMetadata {
				row.ContentMetadata[key] = value
			}
			plain, decrypted := string(f.payload), true
			var body map[string]string
			if err := json.Unmarshal(f.payload, &body); err != nil {
				t.Fatal(err)
			}
			switch scenario.name {
			case "no-preview":
				delete(row.ContentMetadata, "MEDIA_THUMB_INFO")
			case "not-decrypted":
				decrypted = false
				row.ContentMetadata["ENC_KM"] = body["keyMaterial"]
			case "no-body-key":
				plain = `{"keyMaterial":null,"text":"private-body"}`
			case "no-oid":
				delete(row.ContentMetadata, "OID")
			case "closed":
				f.e.Cancel()
			case "download":
				f.e.client.OBSClient.Transport = roundTripFunc(func(*http.Request) (*http.Response, error) {
					response := imageRPC("private-protocol-error")
					response.StatusCode = 404
					return response, nil
				})
			case "wrong-body-key":
				_, wrongKey, err := encryptFileData([]byte("private-other-file"))
				if err != nil {
					t.Fatal(err)
				}
				plain = encode(map[string]string{"keyMaterial": wrongKey})
				// Correct metadata material must never rescue a wrong sealed-body key.
				row.ContentMetadata["ENC_KM"] = body["keyMaterial"]
			case "tampered":
				f.objects["preview"][len(f.objects["preview"])-1] ^= 1
			case "not-image":
				sealed, err := encryptThumbnail([]byte("private-preview-text"), body["keyMaterial"])
				if err != nil {
					t.Fatal(err)
				}
				f.objects["preview"] = sealed
			case "plain-preview":
				f.objects["preview"] = f.input
			}
			fetches := 0
			transport := f.e.client.OBSClient.Transport
			f.e.client.OBSClient.Transport = roundTripFunc(func(request *http.Request) (*http.Response, error) {
				fetches++
				if request.Method != "GET" || !strings.Contains(request.URL.Path, "object__ud-preview") {
					t.Fatal("probe did not use the preview object read path")
				}
				return transport.RoundTrip(request)
			})
			verified, status := f.e.probeMediaPreview(&row, plain, decrypted)
			if verified || status != scenario.status {
				t.Fatalf("probe: %v, %s; want %s", verified, status, scenario.status)
			}
			if (fetches > 0) != scenario.fetch {
				t.Fatal("probe performed an unexpected download or skipped its check")
			}
			if strings.Contains(status, "private-") || strings.Contains(status, body["keyMaterial"]) {
				t.Fatal("probe status leaked input")
			}
		})
	}
}

func TestPreviewProbeDoesNotUseENCKeyWhenTheSealedBodyIsMissing(t *testing.T) {
	f := newImageFixture(t, false)
	row := &lineapi.Message{ContentType: 1, ContentMetadata: map[string]string{
		"MEDIA_THUMB_INFO": "{}", "OID": "private-object", "ENC_KM": "private-metadata-key"}}
	f.e.client.OBSClient.Transport = roundTripFunc(func(*http.Request) (*http.Response, error) {
		t.Fatal("missing sealed body triggered a preview fetch")
		return nil, nil
	})
	verified, status := f.e.probeMediaPreview(row, `{"keyMaterial":"private-not-sealed"}`, true)
	if verified || status != "body_not_decrypted" {
		t.Fatal("metadata/plain body was mistaken for a sealed-body key")
	}
}
