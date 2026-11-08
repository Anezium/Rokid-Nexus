package mobile

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"reflect"
	"strings"
	"testing"

	lineapi "github.com/beeper/line/pkg/line"
)

func TestMediaDescriptionIncludesStructureButNeverMessageValues(t *testing.T) {
	key := base64.StdEncoding.EncodeToString([]byte(strings.Repeat("K", 32)))
	row := &lineapi.Message{ID: "private-message-id", From: "private-owner-mid", To: "private-chat-mid",
		CreatedTime: "1777777777000", ContentType: 1, Text: "private-message-text", Chunks: []string{"private-sealed-chunk"},
		ContentMetadata: map[string]string{
			"ENC_KM": key, "OID": "private-object-id", "DOWNLOAD_URL": "https://private-download.invalid/secret",
			"FILE_NAME": "private-file-name.jpg", "e2eeVersion": "2", "contentType": "1", "SID": "emi",
			"MEDIA_THUMB_INFO":   `{"width":320,"height":240,"extra":"private-thumb-value"}`,
			"MEDIA_CONTENT_INFO": `{"extension":"private-extension","fileSize":999,"category":"private-category","objectId":"private-nested-oid"}`,
		}}
	plain := `{"keyMaterial":"private-body-key","text":"private-body-text","OID":"private-body-oid"}`
	description := describeMedia(row, plain, true, row.From)
	if !description.Found || !description.SenderIsSelf || description.ContentType != 1 || description.E2EEVersion != "2" || description.MetadataContentType != "1" || description.SID != "emi" {
		t.Fatal("requested non-content scalar fields are missing")
	}
	if !description.TimestampValid || description.Timestamp != 1777777777000 {
		t.Fatal("message time was lost")
	}
	if !description.ENCKM.Present || description.ENCKM.DecodedByteLength != 32 {
		t.Fatal("ENC_KM presence or decoded size is wrong")
	}
	if !description.SealedBody.Present || !description.SealedBody.Decrypted || !description.SealedBody.JSONObjectValid || !description.SealedBody.KeyMaterialPresent {
		t.Fatal("sealed-body structural indicators are wrong")
	}
	if !reflect.DeepEqual(description.MetadataKeys, []string{"DOWNLOAD_URL", "ENC_KM", "FILE_NAME", "MEDIA_CONTENT_INFO", "MEDIA_THUMB_INFO", "OID", "SID", "contentType", "e2eeVersion"}) {
		t.Fatal("metadata key names are not sorted")
	}
	if !description.ThumbInfo.Present || !description.ThumbInfo.JSONObjectValid || !reflect.DeepEqual(description.ThumbInfo.Keys, []string{"extra", "height", "width"}) {
		t.Fatal("thumbnail JSON key names are wrong")
	}
	if !description.ContentInfo.Present || !description.ContentInfo.JSONObjectValid || !reflect.DeepEqual(description.ContentInfo.Keys, []string{"category", "extension", "fileSize", "objectId"}) {
		t.Fatal("content JSON key names are wrong")
	}
	encoded := encode(description)
	for _, forbidden := range []string{key, strings.Repeat("K", 32), "private-", "https://", "999", "320", "240"} {
		if strings.Contains(encoded, forbidden) {
			t.Fatalf("diagnostic output contains a disallowed value category: %s", forbidden)
		}
	}
}

func TestMediaDescriptionDistinguishesMissingInvalidAndUndecryptedFields(t *testing.T) {
	row := &lineapi.Message{ContentType: 1, CreatedTime: "private-invalid-time", Chunks: []string{"opaque"},
		ContentMetadata: map[string]string{"ENC_KM": "not-base64!", "MEDIA_THUMB_INFO": `["private-array-value"]`,
			"MEDIA_CONTENT_INFO": "null", "e2eeVersion": "private-version", "contentType": "private-content-type", "SID": "private-sid"}}
	description := describeMedia(row, `{"keyMaterial":"private-key"}`, false, "private-owner")
	if !description.ENCKM.Present || description.ENCKM.DecodedByteLength != -1 {
		t.Fatal("invalid base64 was reported as a decoded key")
	}
	if !description.SealedBody.Present || description.SealedBody.Decrypted || description.SealedBody.KeyMaterialPresent {
		t.Fatal("undecrypted body was treated as parsed")
	}
	if !description.ThumbInfo.Present || description.ThumbInfo.JSONObjectValid || len(description.ThumbInfo.Keys) != 0 || description.ContentInfo.JSONObjectValid {
		t.Fatal("non-object JSON was exposed as an object")
	}
	if description.TimestampValid || description.Timestamp != 0 || description.E2EEVersion != "" || description.MetadataContentType != "" || description.SID != "" {
		t.Fatal("unexpected scalar content was emitted")
	}
	if strings.Contains(encode(description), "private-") {
		t.Fatal("invalid scalar or JSON content leaked")
	}
	absent := describeMedia(&lineapi.Message{ContentType: 1}, "", false, "owner")
	if absent.ENCKM.Present || absent.ENCKM.DecodedByteLength != 0 || absent.ThumbInfo.Present || absent.ContentInfo.Present || absent.SealedBody.Present || absent.SenderIsSelf {
		t.Fatal("absent fields were invented")
	}
	if strings.Contains(encode(absent), "null") {
		t.Fatal("empty key lists must be JSON arrays")
	}
}

func TestMediaDescriptionBodyIndicatorDoesNotExposeValuesOrGuessFromMetadata(t *testing.T) {
	for _, test := range []struct {
		body        string
		object, key bool
	}{
		{`{"keyMaterial":null}`, true, true}, {`{"keyMaterial":""}`, true, true},
		{`{"text":"private-body-text"}`, true, false}, {`["private-array"]`, false, false}, {`not-json-private-value`, false, false},
	} {
		row := &lineapi.Message{ContentType: 1, Chunks: []string{"opaque"}}
		description := describeMedia(row, test.body, true, "owner")
		if description.SealedBody.JSONObjectValid != test.object || description.SealedBody.KeyMaterialPresent != test.key {
			t.Fatal("body field presence is wrong")
		}
		if strings.Contains(encode(description), "private-") {
			t.Fatal("body content leaked")
		}
	}
}

func TestDescribeLatestMediaSelectsNewestImageFromServerForEitherSender(t *testing.T) {
	for _, own := range []bool{false, true} {
		t.Run(fmt.Sprint("own=", own), func(t *testing.T) {
			e, err := Open(t.TempDir())
			if err != nil {
				t.Fatal(err)
			}
			defer e.Close()
			chatID := "c" + strings.Repeat("1", 32)
			e.stored.Mid = "private-owner-mid"
			e.chats = []chat{{ID: chatID, Title: "private-chat-title"}}
			e.client = e.newClient("synthetic-token")
			from := "private-other-sender"
			if own {
				from = e.stored.Mid
			}
			rows := []*lineapi.Message{
				nil, {ContentType: 0, CreatedTime: "3000", Text: "private-newer-text"},
				{ContentType: 1, CreatedTime: "1000", ContentMetadata: map[string]string{"OLD_FIELD": "private-old-value"}},
				{ID: "private-new-image", From: from, ContentType: 1, CreatedTime: "2000", Chunks: []string{"private-chunks"},
					ContentMetadata: map[string]string{"SID": "emi", "e2eeVersion": "2", "contentType": "1", "MEDIA_THUMB_INFO": "{}", "OID": "private-preview-oid", "ENC_KM": "private-metadata-key"}},
			}
			calls := 0
			e.client.HTTPClient.Transport = roundTripFunc(func(request *http.Request) (*http.Response, error) {
				calls++
				if !strings.HasSuffix(request.URL.Path, "getRecentMessagesV2") {
					t.Fatal("diagnostic called an unexpected API")
				}
				return imageRPC(encode(map[string]any{"code": 0, "data": rows})), nil
			})
			before, beforeSnapshot := encode(e.stored), e.Snapshot()
			result := e.DescribeLatestMedia(chatID)
			var description mediaDescription
			if json.Unmarshal([]byte(result), &description) != nil || !description.Found || description.Timestamp != 2000 || description.SenderIsSelf != own {
				t.Fatal("diagnostic selected the wrong message")
			}
			if calls != 1 || !description.SealedBody.Present || description.SealedBody.Decrypted || description.SealedBody.KeyMaterialPresent {
				t.Fatal("diagnostic must report unknown sealed content when no crypto manager is available")
			}
			if description.PreviewDecryptsWithBodyKey || description.PreviewProbeStatus != "body_not_decrypted" {
				t.Fatal("unknown body decryption was misreported as a preview result")
			}
			if encode(e.stored) != before || e.Snapshot() != beforeSnapshot {
				t.Fatal("diagnostic mutated the journal or public snapshot")
			}
			if strings.Contains(result, "private-") || strings.Contains(result, chatID) {
				t.Fatal("diagnostic exposed message or conversation identity")
			}
		})
	}
}

func TestDescribeLatestMediaFailsWithoutReturningProtocolErrors(t *testing.T) {
	e, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer e.Close()
	chatID := "c" + strings.Repeat("1", 32)
	if result := e.DescribeLatestMedia(chatID); !strings.Contains(result, `"found":false`) {
		t.Fatal("unavailable chat was described")
	}
	e.chats = []chat{{ID: chatID}}
	e.client = e.newClient("synthetic")
	for _, response := range []string{`{"code":0,"data":[]}`, `{"code":1,"message":"private-server-error"}`} {
		e.client.HTTPClient.Transport = roundTripFunc(func(*http.Request) (*http.Response, error) { return imageRPC(response), nil })
		result := e.DescribeLatestMedia(chatID)
		if !strings.Contains(result, `"found":false`) || strings.Contains(result, "private-") {
			t.Fatal("diagnostic returned a false success or raw protocol error")
		}
	}
}
