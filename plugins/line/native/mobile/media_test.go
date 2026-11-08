package mobile

import (
	"bytes"
	"context"
	"encoding/base64"
	"io"
	"net/http"
	"strings"
	"testing"

	lineapi "github.com/beeper/line/pkg/line"
)

func TestPhotoAuthenticatesAnIndependentFixtureAndRejectsTampering(t *testing.T) {
	// Generated independently with .NET AES-ECB counter blocks and HMAC-SHA256.
	key := "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
	data, _ := base64.StdEncoding.DecodeString("GSlIlouZPmpikAS836Icf3bLFQPG1o6V0bch+5Nzt8FMBE1H/hN3eYVA6Z07cg2HJ/Y=")
	plain, err := decryptPhoto(data, key)
	if err != nil || string(plain) != "LINE photo fixture" {
		t.Fatalf("fixture failed: %v", err)
	}
	for _, offset := range []int{0, len(data) - 1} {
		corrupt := bytes.Clone(data)
		corrupt[offset] ^= 1
		if plain, err := decryptPhoto(corrupt, key); err == nil || plain != nil {
			t.Fatal("tampered media exposed plaintext")
		}
	}
	for _, invalid := range []string{"", "not-base64", base64.StdEncoding.EncodeToString(make([]byte, 32))} {
		if plain, err := decryptPhoto(data, invalid); err == nil || plain != nil {
			t.Fatal("invalid key accepted")
		}
	}
}

func TestEncryptedPhotoNeverFallsBackToPlaintextWithoutAKey(t *testing.T) {
	for _, row := range []*lineapi.Message{
		{ContentType: 1, Chunks: []string{"encrypted"}},
		{ContentType: 1, ContentMetadata: map[string]string{"OID": "encrypted-object"}},
		{ContentType: 1, ContentMetadata: map[string]string{"ENC_KM": ""}},
	} {
		_, kind, source := decodeMedia(row, "")
		if kind != "" || source != nil {
			t.Fatal("missing key offered a photo preview")
		}
	}
	e := &Engine{}
	decoded := e.decode(&lineapi.Message{ID: "encrypted", ContentType: 1, Chunks: []string{"encrypted"}})
	if decoded.source != nil || decoded.Media != "" || decoded.Text != "[Unable to decrypt message]" {
		t.Fatal("failed message decryption was hidden by a media placeholder")
	}
}

func TestMediaReadsOnlyLoadedMessagesAndKeepsKeysOutOfSnapshots(t *testing.T) {
	e, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer e.Close()
	e.client = e.newClient("synthetic-token")
	e.chats = []chat{{ID: "chat", History: []message{{ID: "message", Media: "sticker", source: &mediaSource{
		sticker: "123", keys: []string{"private-media-key"}, oid: "private-object-locator",
	}}}}}
	if strings.Contains(e.Snapshot(), "private-") {
		t.Fatal("native media secrets leaked into snapshot")
	}
	previous := http.DefaultTransport
	defer func() { http.DefaultTransport = previous }()
	calls := 0
	http.DefaultTransport = roundTripFunc(func(req *http.Request) (*http.Response, error) {
		calls++
		if req.URL.String() != "https://stickershop.line-scdn.net/stickershop/v1/sticker/123/android/sticker.png" ||
			req.Header.Get("x-line-access") != "" {
			t.Fatal("sticker request leaked authorization or used an unexpected host")
		}
		return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader("image fixture"))}, nil
	})
	if _, err := e.Media("other-chat", "message"); err == nil || calls != 0 {
		t.Fatal("unloaded chat triggered a download")
	}
	if _, err := e.Media("chat", "other-message"); err == nil || calls != 0 {
		t.Fatal("unloaded message triggered a download")
	}
	if data, err := e.Media("chat", "message"); err != nil || string(data) != "image fixture" || calls != 1 {
		t.Fatalf("loaded media failed: %v", err)
	}
}

func TestMediaLimitsChunkedBodiesAndRejectsCrossHostRedirects(t *testing.T) {
	previous := http.DefaultTransport
	defer func() { http.DefaultTransport = previous }()
	http.DefaultTransport = roundTripFunc(func(req *http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: 200, ContentLength: -1, Header: make(http.Header),
			Body: io.NopCloser(strings.NewReader(strings.Repeat("x", maxMediaBytes+1)))}, nil
	})
	client := mediaHTTPClient(context.Background())
	response, err := client.Get("https://obs.line-apps.com/media")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if _, err := io.ReadAll(response.Body); err == nil {
		t.Fatal("oversized chunked response accepted")
	}
	first, _ := http.NewRequest("GET", "https://obs.line-apps.com/media", nil)
	redirect, _ := http.NewRequest("GET", "https://example.invalid/media", nil)
	if client.CheckRedirect(redirect, []*http.Request{first}) == nil {
		t.Fatal("authorization could follow a cross-host redirect")
	}
}

func TestMediaMetadataCannotChooseArbitraryDownloadTargets(t *testing.T) {
	for _, id := range []string{"../secret", "123?token=secret", "", strings.Repeat("1", 25)} {
		_, _, source := decodeMedia(&lineapi.Message{ContentType: 7, ContentMetadata: map[string]string{"STKID": id}}, "")
		if source != nil {
			t.Fatal("unsafe sticker id accepted")
		}
	}
	for _, target := range []string{"https://example.invalid/a", "//example.invalid/a", "http://obs.line-apps.com/a", "https://user@obs.line-apps.com/a"} {
		if safePublicMedia(target) {
			t.Fatal("unsafe public media target accepted")
		}
	}
	if !safePublicMedia("/r/talk/m/image") || !safePublicMedia("https://obs.line-apps.com/r/talk/m/image") {
		t.Fatal("normal public media target rejected")
	}
}
