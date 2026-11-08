package mobile

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/beeper/line/pkg/e2ee"
	lineapi "github.com/beeper/line/pkg/line"
)

func testImageJPEG(t *testing.T, width, height int) []byte {
	t.Helper()
	picture := image.NewRGBA(image.Rect(0, 0, width, height))
	for y := 0; y < height; y++ {
		for x := 0; x < width; x++ {
			picture.Set(x, y, color.RGBA{byte(x), byte(y), 90, 255})
		}
	}
	var encoded bytes.Buffer
	if err := jpeg.Encode(&encoded, picture, nil); err != nil {
		t.Fatal(err)
	}
	return encoded.Bytes()
}

type imageFixture struct {
	e       *Engine
	chatID  string
	input   []byte
	failure string
	steps   []string
	message *lineapi.Message
	objects map[string][]byte
	payload []byte
}

func newImageFixture(t *testing.T, group bool) *imageFixture {
	t.Helper()
	e, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(e.Close)
	f := &imageFixture{e: e, chatID: "u" + strings.Repeat("2", 32), input: testImageJPEG(t, 64, 48), objects: make(map[string][]byte)}
	e.stored.Token, e.stored.Mid = "synthetic-session", "u"+strings.Repeat("1", 32)
	e.state = "ready"
	if group {
		f.chatID = "c" + strings.Repeat("3", 32)
	}
	e.chats = []chat{{ID: f.chatID, Title: "Recipient", Group: group}}
	e.client = e.newClient("synthetic-session")
	e.client.HTTPClient.Transport = roundTripFunc(func(req *http.Request) (*http.Response, error) {
		method := req.URL.Path[strings.LastIndex(req.URL.Path, "/")+1:]
		f.steps = append(f.steps, method)
		if method == f.failure {
			return imageRPC(`{"code":1,"message":"synthetic failure"}`), nil
		}
		var data any
		switch method {
		case "negotiateE2EEPublicKey", "getE2EEPublicKey":
			data = &lineapi.E2EEPublicKey{KeyID: "2", PublicKey: base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{2}, 32)), E2EEVersion: 2}
		case "getLastE2EEGroupSharedKey", "getE2EEGroupSharedKey":
			return imageRPC(`{"code":1,"message":"no synthetic group key"}`), nil
		case "determineMediaMessageFlow":
			flow := 2
			if f.failure == "plain-flow" {
				flow = 1
			}
			data = &lineapi.MediaMessageFlowResponse{FlowMap: map[string]int{"1": flow}}
		case "acquireEncryptedAccessToken":
			data = "3600\x1esynthetic-obs-token"
		case "sendMessage":
			// Inspect the durable journal before simulating server receipt.
			stored, err := os.ReadFile(filepath.Join(e.dir, "session.json"))
			if err != nil {
				t.Fatal(err)
			}
			var journal storedSession
			if json.Unmarshal(stored, &journal) != nil || journal.Sends["photo"].State != "unconfirmed" {
				t.Fatal("message dispatched before durable journal")
			}
			var args []json.RawMessage
			if json.NewDecoder(req.Body).Decode(&args) != nil || len(args) != 2 {
				t.Fatal("invalid send RPC")
			}
			if err := json.Unmarshal(args[1], &f.message); err != nil {
				t.Fatal(err)
			}
			f.message.ID = "server-photo"
			if f.failure == "send-failure" {
				return nil, fmt.Errorf("synthetic connection loss after dispatch")
			}
			if f.failure == "missing-ack" {
				return imageRPC(`{"code":0,"data":null}`), nil
			}
			if f.failure == "ack-save" {
				e.dir = filepath.Join(e.dir, "missing-directory")
			}
			data = f.message
		case "getRecentMessagesV2":
			data = []*lineapi.Message{f.message}
		default:
			t.Fatalf("unexpected RPC %s", method)
		}
		return imageRPC(encode(map[string]any{"code": 0, "data": data})), nil
	})
	e.client.OBSClient.Transport = roundTripFunc(func(req *http.Request) (*http.Response, error) {
		if req.Header.Get("x-line-access") != "synthetic-obs-token" {
			t.Fatal("OBS authorization missing")
		}
		if req.Method == "GET" {
			if strings.HasSuffix(req.URL.Path, "/object_info.obs") {
				return imageRPC(`{"status":"exist"}`), nil
			}
			object := "object"
			if strings.HasSuffix(req.URL.Path, "object__ud-preview") {
				object = "preview"
			}
			return imageRPC(string(f.objects[object])), nil
		}
		step := "upload"
		key := "object"
		if strings.HasSuffix(req.URL.Path, "object__ud-preview") {
			step, key = "preview", "preview"
		}
		f.steps = append(f.steps, step)
		if f.failure == step {
			return &http.Response{StatusCode: 403, Body: io.NopCloser(strings.NewReader("refused")), Header: make(http.Header)}, nil
		}
		f.objects[key], err = io.ReadAll(req.Body)
		if err != nil {
			t.Fatal(err)
		}
		response := imageRPC("")
		response.Header.Set("x-obs-oid", "object")
		return response, nil
	})
	return f
}

func imageRPC(body string) *http.Response {
	return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(body))}
}

// Exercise upload/journal/dispatch independently of account keychain provisioning.
// Production always obtains this callback from prepareImageSealer's raw E2EE APIs.
func (f *imageFixture) send() string {
	return f.e.sendPreparedImage("photo", f.chatID, imageDigest(f.chatID, f.input), f.input, func(payload []byte) ([]string, error) {
		f.payload = bytes.Clone(payload)
		if f.failure == "sealing" {
			return nil, fmt.Errorf("synthetic sealing failure")
		}
		if f.failure == "cancel-before-send" {
			f.e.Cancel()
		}
		return []string{base64.StdEncoding.EncodeToString(make([]byte, 16)), "synthetic-body", "synthetic-tag", "sender", "receiver"}, nil
	})
}

func TestImageEncryptionAndPreviewRoundTrip(t *testing.T) {
	original := append(testImageJPEG(t, 1600, 800), []byte("untouched trailing metadata")...)
	encrypted, key, err := encryptFileData(original)
	if err != nil {
		t.Fatal(err)
	}
	plain, err := decryptPhoto(encrypted, key)
	if err != nil || !bytes.Equal(plain, original) || len(encrypted) != len(original)+32 {
		t.Fatal("original JPEG changed or encryption failed")
	}
	preview, width, height, err := generateThumbnail(original)
	if err != nil || width != 1280 || height != 640 {
		t.Fatalf("preview bounds %dx%d: %v", width, height, err)
	}
	sealedPreview, err := encryptThumbnail(preview, key)
	if err != nil {
		t.Fatal(err)
	}
	decoded, err := decryptPhoto(sealedPreview, key)
	if err != nil || !bytes.Equal(decoded, preview) {
		t.Fatal("preview does not use the parent's key and file format")
	}
	_, otherKey, err := encryptFileData(original)
	if err != nil || otherKey == key {
		t.Fatal("new uploads reused their media key")
	}
}

func TestSendImageDirectAndGroupWireContract(t *testing.T) {
	for _, group := range []bool{false, true} {
		t.Run(fmt.Sprint("group=", group), func(t *testing.T) {
			f := newImageFixture(t, group)
			if outcome := f.send(); outcome != "sent" {
				t.Fatalf("send: %s", outcome)
			}
			msg := f.message
			if msg.ContentType != 1 || !msg.HasContent || msg.Text != "" || len(msg.Chunks) != 5 || (group && msg.ToType != 2) {
				t.Fatal("IMAGE envelope is not sealed")
			}
			var body map[string]string
			if json.Unmarshal(f.payload, &body) != nil || len(body) != 1 || body["keyMaterial"] != msg.ContentMetadata["ENC_KM"] {
				t.Fatal("sealed payload and metadata disagree")
			}
			metadata := msg.ContentMetadata
			if metadata["OID"] != "object" || metadata["SID"] != "emi" || metadata["contentType"] != "1" || metadata["FILE_NAME"] != "image.jpg" || metadata["e2eeVersion"] == "" || metadata["MEDIA_THUMB_INFO"] == "" {
				t.Fatal("required image metadata missing")
			}
			if metadata["FILE_SIZE"] != fmt.Sprint(len(f.input)+32) {
				t.Fatal("file size describes clear bytes")
			}
			plain, err := decryptPhoto(f.objects["object"], body["keyMaterial"])
			if err != nil || !bytes.Equal(plain, f.input) {
				t.Fatal("recipient could not recover original JPEG")
			}
			if _, err := decryptPhoto(f.objects["preview"], body["keyMaterial"]); err != nil {
				t.Fatal("recipient could not recover preview")
			}
			steps := f.steps
			if !reflect.DeepEqual(steps[len(steps)-3:], []string{"upload", "preview", "sendMessage"}) {
				t.Fatalf("upload/send order: %v", steps)
			}
			history := f.e.findChat(f.chatID).History
			if len(history) != 1 || history[0].ID != "server-photo" || !history[0].Mine || history[0].Media != "photo" {
				t.Fatal("sent photo absent from snapshot history")
			}
			media, err := f.e.Media(f.chatID, history[0].ID)
			preview, previewErr := decryptPhoto(f.objects["preview"], body["keyMaterial"])
			if err != nil || previewErr != nil || !bytes.Equal(media, preview) {
				t.Fatalf("own uploaded photo cannot be read through Media: %v", err)
			}
		})
	}
}

func TestSendImageFailureMapping(t *testing.T) {
	for _, test := range []struct{ failure, outcome string }{
		{"determineMediaMessageFlow", "not_sent"}, {"plain-flow", "not_sent"}, {"sealing", "not_sent"}, {"cancel-before-send", "not_sent"},
		{"acquireEncryptedAccessToken", "not_sent"}, {"upload", "not_sent"}, {"preview", "sent"},
		{"send-failure", "unconfirmed"}, {"sendMessage", "unconfirmed"}, {"missing-ack", "unconfirmed"},
	} {
		t.Run(test.failure, func(t *testing.T) {
			f := newImageFixture(t, false)
			f.failure = test.failure
			if outcome := f.send(); outcome != test.outcome {
				t.Fatalf("got %s, want %s", outcome, test.outcome)
			}
			if test.outcome == "not_sent" && f.message != nil {
				t.Fatal("failure dispatched a message")
			}
			if test.outcome == "unconfirmed" && f.e.stored.Sends["photo"].State != "unconfirmed" {
				t.Fatal("ambiguous result not journaled")
			}
			if test.failure == "preview" && f.message.ContentMetadata["MEDIA_THUMB_INFO"] != "" {
				t.Fatal("failed optional preview advertised as uploaded")
			}
		})
	}
}

func TestSendImageRejectsInvalidInputAndLostAuthorityBeforeNetwork(t *testing.T) {
	for _, invalid := range []string{"empty", "oversize", "not-jpeg", "invocation", "chat-id", "title", "offline", "cancelled", "journal-full"} {
		t.Run(invalid, func(t *testing.T) {
			f := newImageFixture(t, false)
			var err error
			f.e.crypto, err = e2ee.NewManager()
			if err != nil {
				t.Fatal(err)
			}
			invocation, chatID, title, input := "photo", f.chatID, "Recipient", f.input
			want := "not_sent"
			switch invalid {
			case "empty":
				input = nil
			case "oversize":
				input = make([]byte, maxImageBytes+1)
			case "not-jpeg":
				input = []byte("not a JPEG")
			case "invocation":
				invocation = strings.Repeat("x", 129)
			case "chat-id":
				chatID = "invalid"
			case "title":
				title, want = "Renamed recipient", "recipient_changed"
			case "offline":
				f.e.state = "offline"
			case "cancelled":
				f.e.Cancel()
			case "journal-full":
				for i := range maxSends {
					id := fmt.Sprint(i)
					f.e.stored.Sends[id] = sendRecord{State: "unconfirmed"}
					f.e.stored.Order = append(f.e.stored.Order, id)
				}
			}
			if got := f.e.SendImage(invocation, chatID, title, input); got != want {
				t.Fatalf("got %s, want %s", got, want)
			}
			if len(f.steps) != 0 {
				t.Fatal("rejected input reached the network")
			}
		})
	}
}

func TestSendImageRejectsMissingOwnPeerAndGroupKeys(t *testing.T) {
	for _, missing := range []string{"manager", "own", "peer", "group"} {
		t.Run(missing, func(t *testing.T) {
			f := newImageFixture(t, missing == "group")
			if missing != "manager" {
				var err error
				f.e.crypto, err = e2ee.NewManager()
				if err != nil {
					t.Fatal(err)
				}
			}
			if missing == "peer" {
				f.failure = "negotiateE2EEPublicKey"
			}
			if got := f.e.SendImage("photo", f.chatID, "Recipient", f.input); got != "not_sent" {
				t.Fatalf("missing key: %s", got)
			}
			if len(f.objects) != 0 || f.message != nil || len(f.e.stored.Sends) != 0 {
				t.Fatal("missing key uploaded or sent a photo")
			}
		})
	}
}

func TestSendImageAmbiguitySurvivesReopenAndOnlyAnExactOwnEchoConfirms(t *testing.T) {
	f := newImageFixture(t, false)
	f.failure = "send-failure"
	if got := f.send(); got != "unconfirmed" {
		t.Fatal(got)
	}
	dir := f.e.dir
	f.e.Close()
	reopened, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	reopened.chats = []chat{{ID: f.chatID, Title: "Recipient"}}
	if got := reopened.SendImage("photo", f.chatID, "Recipient", f.input); got != "unconfirmed" {
		t.Fatal("ambiguous invocation was retried")
	}
	if len(reopened.chats[0].History) != 0 {
		t.Fatal("unconfirmed photo was reported as sent")
	}
	changed := append(bytes.Clone(f.input), byte(1))
	if got := reopened.SendImage("photo", f.chatID, "Recipient", changed); got != "recipient_changed" {
		t.Fatal("changed bytes reused an invocation")
	}
	if got := reopened.Send("photo", f.chatID, "Recipient", "text"); got != "recipient_changed" {
		t.Fatal("text and image invocation namespaces collided")
	}
	key := reopened.stored.Sends["photo"].Image.KeyMaterial
	row := message{ID: "confirmed-photo", Text: "[Photo]", Mine: true, Media: "photo",
		source: &mediaSource{oid: "object", keys: []string{key}, encrypted: true}}
	otherSender := row
	otherSender.Mine = false
	reopened.confirmOwnImage(f.chatID, otherSender)
	reopened.confirmOwnImage("other-chat", row)
	otherObject := row
	otherObject.source = &mediaSource{oid: "other-object"}
	reopened.confirmOwnImage(f.chatID, otherObject)
	if reopened.stored.Sends["photo"].State != "unconfirmed" {
		t.Fatal("unrelated echo confirmed the photo")
	}
	reopened.addMessage(f.chatID, row)
	if got := reopened.SendImage("photo", f.chatID, "Recipient", f.input); got != "sent" {
		t.Fatal("exact own echo did not confirm invocation")
	}
	if len(reopened.chats[0].History) != 1 || reopened.chats[0].History[0].ID != "confirmed-photo" {
		t.Fatal("confirmed replay duplicated or lost its history entry")
	}
	reopened.Close()
	again, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer again.Close()
	again.chats = []chat{{ID: f.chatID, Title: "Recipient"}}
	if got := again.SendImage("photo", f.chatID, "Recipient", f.input); got != "sent" || len(again.chats[0].History) != 1 {
		t.Fatal("confirmed photo did not survive another reopen")
	}
	if strings.Contains(again.Snapshot(), key) || strings.Contains(again.Snapshot(), "object") {
		t.Fatal("photo key/locator leaked into snapshot")
	}
}

func TestSendImageHistoryFetchConfirmsAnAmbiguousUpload(t *testing.T) {
	f := newImageFixture(t, false)
	f.failure = "missing-ack"
	if got := f.send(); got != "unconfirmed" {
		t.Fatal(got)
	}
	// Exercise the history observation boundary independently of message sealing.
	f.message.Chunks = nil
	f.message.ID = "history-photo"
	if err := f.e.LoadHistory(f.chatID); err != nil {
		t.Fatal(err)
	}
	if f.e.stored.Sends["photo"].State != "sent" {
		t.Fatal("history did not reconcile the uploaded OID")
	}
	if got := f.e.SendImage("photo", f.chatID, "Recipient", f.input); got != "sent" {
		t.Fatal(got)
	}
	history := f.e.findChat(f.chatID).History
	if len(history) != 1 || !history[0].Mine || history[0].Media != "photo" || history[0].ID != "history-photo" {
		t.Fatal("history replay failed to retain a single own photo")
	}
}

func TestSendImageDoesNotDispatchIfJournalCannotBeSaved(t *testing.T) {
	f := newImageFixture(t, false)
	f.e.dir = filepath.Join(f.e.dir, "missing-directory")
	if got := f.send(); got != "not_sent" {
		t.Fatal(got)
	}
	if f.message != nil || len(f.e.stored.Sends) != 0 || len(f.e.stored.Order) != 0 {
		t.Fatal("failed journal write dispatched or retained a phantom invocation")
	}
}

func TestSendImageAcceptsTheExactSixMiBBoundBeforeKeyNegotiation(t *testing.T) {
	f := newImageFixture(t, false)
	var err error
	f.e.crypto, err = e2ee.NewManager()
	if err != nil {
		t.Fatal(err)
	}
	input := make([]byte, maxImageBytes)
	copy(input, f.input)
	if got := f.e.SendImage("photo", f.chatID, "Recipient", input); got != "not_sent" {
		t.Fatal("synthetic account without its own key unexpectedly sent")
	}
	if !reflect.DeepEqual(f.steps, []string{"negotiateE2EEPublicKey"}) {
		t.Fatal("exactly six MiB was rejected before key negotiation")
	}
}

func TestSendImageAckSaveFailureRetainsDurableAmbiguity(t *testing.T) {
	f := newImageFixture(t, false)
	dir := f.e.dir
	f.failure = "ack-save"
	if got := f.send(); got != "sent" {
		t.Fatal("observed acknowledgement was hidden")
	}
	if len(f.e.findChat(f.chatID).History) != 1 {
		t.Fatal("acknowledged photo not appended")
	}
	f.e.Close()
	reopened, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	if got := reopened.SendImage("photo", f.chatID, "Recipient", f.input); got != "unconfirmed" {
		t.Fatal("failed acknowledgement save did not retain durable ambiguity")
	}
}
