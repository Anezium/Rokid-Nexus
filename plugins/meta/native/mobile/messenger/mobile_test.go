package messenger

import (
	"os"
	"path/filepath"
	"testing"

	"go.mau.fi/mautrix-meta/pkg/messagix/cookies"
	"go.mau.fi/mautrix-meta/pkg/messagix/table"
	"go.mau.fi/mautrix-meta/pkg/messagix/types"
	"maunium.net/go/mautrix/bridgev2"
)

func TestLoginAnswersOnlyTheCredentialFormAndKnownSteps(t *testing.T) {
	form := []bridgev2.LoginInputDataField{{ID: "username"}, {ID: "password"}}
	if input, state := classifyInput(form, "test@example.com", "secret"); state != "running" || input["username"] != "test@example.com" || input["password"] != "secret" {
		t.Fatal("credential form not answered")
	}
	code := []bridgev2.LoginInputDataField{{ID: "sms_code", Type: bridgev2.LoginInputFieldType2FACode}}
	if input, state := classifyInput(code, "", ""); input != nil || state != "code_required" {
		t.Fatal("code step not surfaced to the owner")
	}
	for _, field := range []bridgev2.LoginInputDataField{
		{ID: "contact_point", Type: bridgev2.LoginInputFieldTypeSelect, Options: []string{"Text message"}},
		{ID: "backup_code", Type: bridgev2.LoginInputFieldType2FACode},
		{ID: "captcha_code", Type: bridgev2.LoginInputFieldTypeCaptchaCode},
		{ID: "mfatype", Type: bridgev2.LoginInputFieldTypeSelect, Options: []string{"Use a passkey"}},
	} {
		if input, state := classifyInput([]bridgev2.LoginInputDataField{field}, "", ""); input != nil || state != "failed_unsupported_step" {
			t.Fatalf("unsafe step %s answered automatically", field.ID)
		}
	}
	selection := []bridgev2.LoginInputDataField{{ID: "mfatype", Type: bridgev2.LoginInputFieldTypeSelect,
		Options: []string{"Text message", "Approve from another device"}}}
	if input, _ := classifyInput(selection, "", ""); input["mfatype"] != "Approve from another device" {
		t.Fatal("approval not preferred")
	}
}

func TestAdmittedSendIsNeverReadmitted(t *testing.T) {
	path := filepath.Join(t.TempDir(), "send")
	if fresh, err := admit(path, 42); !fresh || err != nil {
		t.Fatal("first admission refused")
	}
	if fresh, _ := admit(path, 43); fresh {
		t.Fatal("second admission accepted")
	}
	if previousOutcome(path) != `{"state":"unknown"}` {
		t.Fatal("unfinished send not reported as unknown")
	}
	if writePrivate(path+".result", outcome{State: "acked"}) != nil || previousOutcome(path) != `{"state":"acked"}` {
		t.Fatal("acknowledged send not reported")
	}
}

func TestSelfTaskTargetsOnlyTheOwnThread(t *testing.T) {
	task := selfTask(7, 9)
	if task.ThreadId != 7 || task.Otid != 9 || task.SendType != table.TEXT || task.ReplyMetaData != nil || task.ForwardedMsgId != "" {
		t.Fatal("self task changed")
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
	if entries, _ := os.ReadDir(filepath.Join(dir, sendsDirectory)); len(entries) != 0 {
		t.Fatal("refused send left an admission")
	}
	if engine.StartLogin(" ", "x") == nil || engine.StartLogin("test@example.com", "") == nil {
		t.Fatal("invalid credentials accepted")
	}
	engine.Close()
	reopened, err := Open(dir)
	if err != nil {
		t.Fatal("lock not released on close")
	}
	reopened.Close()
}

func TestStoredSessionMustMatchTheBoundAccount(t *testing.T) {
	dir := t.TempDir()
	engine, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer engine.Close()
	jar := &cookies.Cookies{Platform: types.MessengerLiteAndroid}
	jar.UpdateValues(map[cookies.MetaCookieName]string{"c_user": "7"})
	session := storedSession{Cookies: jar, Native: &types.NativeSession{AccessToken: "t"}, FBID: 7, Name: "Test"}
	if writePrivate(filepath.Join(dir, sessionFile), session) != nil || writePrivate(filepath.Join(dir, bindingFile), accountBinding(8)) != nil {
		t.Fatal("fixture not written")
	}
	if _, err = engine.loadSession(); err == nil {
		t.Fatal("session for another account accepted")
	}
	if writePrivate(filepath.Join(dir, bindingFile), accountBinding(7)) != nil {
		t.Fatal("fixture not written")
	}
	if loaded, err := engine.loadSession(); err != nil || loaded.FBID != 7 {
		t.Fatal("matching session refused")
	}
	if engine.StartLogin("test@example.com", "secret") == nil {
		t.Fatal("login allowed over an existing session")
	}
	if engine.Forget() != nil || engine.hasSession() {
		t.Fatal("forget did not remove the local session")
	}
}
