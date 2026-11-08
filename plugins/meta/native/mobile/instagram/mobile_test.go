package instagram

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"testing"

	"go.mau.fi/mautrix-meta/pkg/instameow/slidetypes"
	"maunium.net/go/mautrix/bridgev2"
)

type countingTransport struct{ count int }

func (c *countingTransport) RoundTrip(*http.Request) (*http.Response, error) {
	c.count++
	return &http.Response{StatusCode: 200}, nil
}

func sendRequest(name string) *http.Request {
	request, _ := http.NewRequest("POST", "https://www.instagram.com/api/graphql", nil)
	request.Header.Set("x-fb-friendly-name", name)
	return request
}

func TestSendMutationCrossesTheTransportOnlyOncePerArming(t *testing.T) {
	base := &countingTransport{}
	boundary := &sendBoundary{base: base}
	for _, name := range []string{"IGDirectTextSendMutation", "IGDirectMediaSendMutation"} {
		if response, _ := boundary.RoundTrip(sendRequest(name)); response.StatusCode != 400 {
			t.Fatal("unarmed send reached the transport")
		}
		boundary.armed.Store(true)
		boundary.RoundTrip(sendRequest(name))
		boundary.RoundTrip(sendRequest(name))
	}
	boundary.RoundTrip(sendRequest("LoadIndex"))
	if base.count != 3 {
		t.Fatalf("expected one dispatch per arming plus one non-send request, got %d", base.count)
	}
}

func TestAdmittedSendIsNeverReadmitted(t *testing.T) {
	path := filepath.Join(t.TempDir(), "send")
	if fresh, err := admit(path); !fresh || err != nil {
		t.Fatal("first admission refused")
	}
	if fresh, _ := admit(path); fresh {
		t.Fatal("second admission accepted")
	}
	if previousOutcome(path) != `{"state":"unknown"}` {
		t.Fatal("unfinished send not reported as unknown")
	}
	if writePrivate(path+".result", outcome{State: "acked"}) != nil || previousOutcome(path) != `{"state":"acked"}` {
		t.Fatal("acknowledged send not reported")
	}
}

func TestSelfThreadIsTheUniqueThreadKeyedByOwnFBID(t *testing.T) {
	mailbox := func(threads ...*slidetypes.ThreadInfo) *slidetypes.Mailbox {
		var box slidetypes.Mailbox
		for _, thread := range threads {
			edge := slidetypes.Node[slidetypes.WrappedThreadInfo]{Node: slidetypes.WrappedThreadInfo{AsIGDirectThread: thread}}
			box.ThreadsByFolder.Edges = append(box.ThreadsByFolder.Edges, edge)
		}
		return &box
	}
	self := &slidetypes.ThreadInfo{ID: "self", ThreadKey: 7}
	other := &slidetypes.ThreadInfo{ID: "other", ThreadKey: 8}
	if selfThread(mailbox(other, self), 7) != self {
		t.Fatal("self thread not found")
	}
	if selfThread(mailbox(self, &slidetypes.ThreadInfo{ID: "dup", ThreadKey: 7}), 7) != nil {
		t.Fatal("ambiguous self thread accepted")
	}
	withUser := &slidetypes.ThreadInfo{ID: "u", ThreadKey: 7, Users: []*slidetypes.User{{ID: "x"}}}
	group := &slidetypes.ThreadInfo{ID: "g", ThreadKey: 7, IsGroup: true}
	if selfThread(mailbox(withUser), 7) != nil || selfThread(mailbox(group), 7) != nil || selfThread(mailbox(other), 7) != nil {
		t.Fatal("thread with another participant accepted as self")
	}
}

func TestLoginChoosesApprovalAndNeverAnUnknownOption(t *testing.T) {
	selection := bridgev2.LoginInputDataField{Type: bridgev2.LoginInputFieldTypeSelect, ID: "mfatype",
		Options: []string{"Text message", "Approve from another device"}}
	if input, state := classifyInput(selection); state != "running" || input["mfatype"] != "Approve from another device" {
		t.Fatal("approval not preferred")
	}
	unknown := bridgev2.LoginInputDataField{Type: bridgev2.LoginInputFieldTypeSelect, ID: "mfatype", Options: []string{"Use backup code"}}
	if input, state := classifyInput(unknown); input != nil || state != "failed_unsupported_step" {
		t.Fatal("unknown verification option selected")
	}
	code := bridgev2.LoginInputDataField{Type: bridgev2.LoginInputFieldType2FACode, ID: "verification_code"}
	if input, state := classifyInput(code); input != nil || state != "code_required" {
		t.Fatal("code step not surfaced to the owner")
	}
	account := bridgev2.LoginInputDataField{Type: bridgev2.LoginInputFieldTypeSelect, ID: "account"}
	if input, _ := classifyInput(account); input != nil {
		t.Fatal("account selection answered automatically")
	}
}

func TestEngineIsExclusiveAndRefusesSendsWithoutAConnection(t *testing.T) {
	dir := t.TempDir()
	engine, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = Open(dir); err == nil {
		t.Fatal("second engine opened the same session")
	}
	if engine.SendSelfText("0123456789abcdef", "hello") != `{"state":"not_sent","reason":"not_connected"}` {
		t.Fatal("send accepted without a connection")
	}
	entries, _ := os.ReadDir(filepath.Join(dir, sendsDirectory))
	if len(entries) != 0 {
		t.Fatal("refused send left an admission")
	}
	if engine.StartLogin("bad name!", "x") == nil || engine.StartLogin("test_user", "") == nil {
		t.Fatal("invalid credentials accepted")
	}
	engine.Close()
	if reopened, err := Open(dir); err != nil {
		t.Fatal("lock not released on close")
	} else {
		reopened.Close()
	}
}

func TestStoredSessionMustMatchTheBoundAccount(t *testing.T) {
	dir := t.TempDir()
	engine, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer engine.Close()
	session := map[string]any{"username": "test_user", "user_id": "1", "authorization": "Bearer IGT:2:e30="}
	data, _ := json.Marshal(session)
	os.WriteFile(filepath.Join(dir, sessionFile), data, 0600)
	writePrivate(filepath.Join(dir, bindingFile), accountBinding("someone_else"))
	if _, _, err = engine.storedSession(); err == nil {
		t.Fatal("session for another account accepted")
	}
	if engine.StartLogin("test_user", "secret") == nil {
		t.Fatal("login allowed over an existing session")
	}
	if engine.Forget() != nil || engine.hasSession() {
		t.Fatal("forget did not remove the local session")
	}
}
