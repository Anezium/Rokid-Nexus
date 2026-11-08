package mobile

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/beeper/line/pkg/e2ee"
)

func TestSessionExcludesOtherOwnersAndReleasesLock(t *testing.T) {
	dir := t.TempDir()
	e, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	if other, err := Open(dir); err == nil {
		other.Close()
		t.Fatal("session opened by two owners")
	}
	if err = Forget(dir); err == nil {
		t.Fatal("forgot a live session")
	}
	e.Close()
	other, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	other.Close()
}

func TestForgetCanRemoveCorruptSession(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "session.json"), []byte("invalid"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := Open(dir); err == nil {
		t.Fatal("corrupt session accepted")
	}
	if err := Forget(dir); err != nil {
		t.Fatal(err)
	}
	e, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer e.Close()
	var state struct {
		State string `json:"state"`
	}
	if err = json.Unmarshal([]byte(e.Snapshot()), &state); err != nil || state.State != "signed_out" {
		t.Fatal(e.Snapshot())
	}
}

func TestAmbiguousSendSurvivesReopenWithoutResending(t *testing.T) {
	dir := t.TempDir()
	e, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	id := "u0123456789abcdef0123456789abcdef"
	e.stored.Token = "synthetic-token"
	e.stored.Mid = id
	e.stored.Sends["invocation"] = sendRecord{Digest: sendDigest(id, "hello"), State: "unconfirmed"}
	e.stored.Order = []string{"invocation"}
	if err = e.save(); err != nil {
		t.Fatal(err)
	}
	e.Close()
	e, err = Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer e.Close()
	if got := e.Send("invocation", id, "Contact", "hello"); got != "unconfirmed" {
		t.Fatalf("replay: %s", got)
	}
	if got := e.Send("invocation", id, "Contact", "different"); got != "recipient_changed" {
		t.Fatalf("changed content accepted: %s", got)
	}
	if e.client != nil {
		t.Fatal("replay started a network client")
	}
}

func TestCancelInterruptsOwnedHTTPRequests(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	previous := http.DefaultTransport
	defer func() { http.DefaultTransport = previous }()
	started := make(chan struct{})
	http.DefaultTransport = roundTripFunc(func(req *http.Request) (*http.Response, error) {
		close(started)
		<-req.Context().Done()
		return nil, req.Context().Err()
	})
	transport := requestTransport{ctx: ctx}
	req, _ := http.NewRequest(http.MethodGet, "https://example.invalid", nil)
	finished := make(chan error, 1)
	go func() { _, err := transport.RoundTrip(req); finished <- err }()
	<-started
	cancel()
	select {
	case err := <-finished:
		if err == nil {
			t.Fatal("cancelled request succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("request outlived owner")
	}
}

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(req *http.Request) (*http.Response, error) { return f(req) }

func TestGroupKeyFailureNeverSendsPlaintext(t *testing.T) {
	e, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer e.Close()
	e.crypto, err = e2ee.NewManager()
	if err != nil {
		t.Fatal(err)
	}
	e.stored.Mid = "u0123456789abcdef0123456789abcdef"
	id := "c0123456789abcdef0123456789abcdef"
	e.chats = []chat{{ID: id, Title: "Group", Group: true}}
	e.state = "ready"
	e.client = e.newClient("synthetic")
	requests := 0
	e.client.HTTPClient.Transport = roundTripFunc(func(req *http.Request) (*http.Response, error) {
		requests++
		if !strings.HasSuffix(req.URL.Path, "getLastE2EEGroupSharedKey") {
			t.Errorf("unexpected request: %s", req.URL.Path)
		}
		return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(`{"code":1,"message":"key unavailable"}`))}, nil
	})
	if got := e.Send("new", id, "Group", "secret"); got != "not_sent" {
		t.Fatalf("encryption failure: %s", got)
	}
	if requests != 1 {
		t.Fatalf("expected only key negotiation, got %d requests", requests)
	}
	if len(e.stored.Sends) != 0 {
		t.Fatal("failed encryption was dispatched")
	}
}

func TestPrivateSnapshotDoesNotExposeLoginSecrets(t *testing.T) {
	e, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer e.Close()
	e.setLogin(loginStatus{Phase: "pin", PIN: "123456"})
	var snapshot map[string]any
	if err = json.Unmarshal([]byte(e.Snapshot()), &snapshot); err != nil {
		t.Fatal(err)
	}
	if _, ok := snapshot["login"]; ok {
		t.Fatal("login leaked in conversation snapshot")
	}
	if _, ok := snapshot["token"]; ok {
		t.Fatal("token leaked in conversation snapshot")
	}
}

func TestRemoteUnsendRemovesOnlyTheMatchingChatMessage(t *testing.T) {
	e, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer e.Close()
	e.chats = []chat{
		{ID: "first", History: []message{{ID: "remove", Text: "secret"}, {ID: "keep", Text: "other"}}},
		{ID: "second", History: []message{{ID: "remove", Text: "unrelated"}}},
	}
	e.removeMessage("first", "remove")
	if len(e.chats[0].History) != 1 || e.chats[0].History[0].ID != "keep" {
		t.Fatal("unsent text remained")
	}
	if len(e.chats[1].History) != 1 {
		t.Fatal("another chat was affected")
	}
}
