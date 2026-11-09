package mobile

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	lineapi "github.com/beeper/line/pkg/line"
)

func TestSendTombstonesPreventTextAndPhotoReplayAfterCapacityAndReopen(t *testing.T) {
	f := newImageFixture(t, false)
	e := f.e
	textInvocation, photoInvocation := "past-0", "past-1"
	for i := range maxSends {
		invocation := fmt.Sprintf("past-%d", i)
		record := sendRecord{Digest: sendDigest(f.chatID, invocation), State: "sent"}
		if i == 1 {
			record.Digest = imageDigest(f.chatID, f.input)
			record.Image = &sentImage{ChatID: f.chatID, OID: "private-upload", KeyMaterial: "private-media-key", MessageID: "old-photo"}
		}
		e.stored.Sends[invocation] = record
		e.stored.Order = append(e.stored.Order, invocation)
	}
	for i := range maxSends + 16 {
		if !e.makeSendRoom() {
			t.Fatal("confirmed journal could not make room")
		}
		invocation := fmt.Sprintf("later-%d", i)
		e.stored.Sends[invocation] = sendRecord{Digest: sendDigest(f.chatID, invocation), State: "sent"}
		e.stored.Order = append(e.stored.Order, invocation)
	}
	if len(e.stored.Sends) != maxSends || len(e.stored.Tombstones) != maxSends+16 {
		t.Fatal("full records and tombstones were not bounded separately")
	}
	assertReplay := func(engine *Engine) {
		if got := engine.Send(textInvocation, f.chatID, "Recipient", textInvocation); got != "sent" {
			t.Fatalf("retired text replay: %s", got)
		}
		if got := engine.SendImage(photoInvocation, f.chatID, "Recipient", f.input); got != "sent" {
			t.Fatalf("retired photo replay: %s", got)
		}
		if got := engine.Send(textInvocation, f.chatID, "Recipient", "changed"); got != "recipient_changed" {
			t.Fatal("changed tombstoned text was accepted")
		}
		if got := engine.SendImage(photoInvocation, f.chatID, "Recipient", append(bytes.Clone(f.input), 1)); got != "recipient_changed" {
			t.Fatal("changed tombstoned photo was accepted")
		}
	}
	assertReplay(e)
	if len(f.steps) != 0 || len(f.objects) != 0 {
		t.Fatal("retired invocation reached RPC or OBS")
	}
	if err := e.save(); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join(e.dir, "session.json"))
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(data, []byte("private-media-key")) || bytes.Contains(data, []byte("private-upload")) {
		t.Fatal("compact tombstone retained full image secrets")
	}
	dir := e.dir
	e.Close()
	reopened, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	assertReplay(reopened)
	if reopened.client != nil {
		t.Fatal("retired invocation started a client after reopening")
	}
}

func TestSendTombstonesRemainBoundedAndReopenWithEscapedInvocationIDs(t *testing.T) {
	e, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer e.Close()
	e.stored.Token, e.stored.Mid = "synthetic-token", "synthetic-mid"
	chatID := "u" + strings.Repeat("1", 32)
	for i := range maxSendTombstones {
		id := fmt.Sprintf("retired-%d", i)
		e.stored.Tombstones = append(e.stored.Tombstones, sendTombstone{InvocationHash: invocationHash(id), Digest: sendDigest(chatID, id), State: "sent"})
	}
	for i := range maxSends {
		id := strings.Repeat("\x00", 125) + fmt.Sprintf("%03d", i)
		e.stored.Sends[id] = sendRecord{Digest: sendDigest(chatID, id), State: "sent"}
		e.stored.Order = append(e.stored.Order, id)
	}
	lastRetired := e.stored.Order[0]
	if !e.makeSendRoom() {
		t.Fatal("could not retire a confirmed record")
	}
	if len(e.stored.Tombstones) != maxSendTombstones {
		t.Fatal("tombstone retention exceeded its limit")
	}
	if err := e.save(); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join(e.dir, "session.json"))
	if err != nil {
		t.Fatal(err)
	}
	if len(data) <= 256*1024 || len(data) > maxSessionBytes {
		t.Fatal("fixture did not exercise the expanded bounded session limit")
	}
	dir := e.dir
	e.Close()
	reopened, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	if got := reopened.Send(lastRetired, chatID, "Recipient", lastRetired); got != "sent" {
		t.Fatal("newly retired invocation was lost across bounded persistence")
	}
	if got := reopened.Send("retired-1", chatID, "Recipient", "retired-1"); got != "sent" {
		t.Fatal("retained old invocation was forgotten")
	}
}

func TestTextJournalFailureRollsBackAndAllowsSafeRetry(t *testing.T) {
	f := newImageFixture(t, false)
	e := f.e
	dir := e.dir
	text := "synthetic text"
	digest := sendDigest(f.chatID, text)
	msg := &lineapi.Message{From: e.stored.Mid, To: f.chatID, ContentType: 0,
		ContentMetadata: map[string]string{"e2eeVersion": "2"}, Chunks: []string{"synthetic sealed text"}}
	e.dir = filepath.Join(dir, "missing-directory")
	sequence := e.stored.Sequence
	if got := e.sendPreparedText("photo", f.chatID, text, digest, msg); got != "not_sent" {
		t.Fatal("failed pre-dispatch save did not report not_sent")
	}
	if _, exists := e.stored.Sends["photo"]; exists || len(e.stored.Order) != 0 || e.stored.Sequence != sequence {
		t.Fatal("failed text save left an unconfirmed invocation or sequence change")
	}
	if len(f.steps) != 0 {
		t.Fatal("text was dispatched without a durable journal")
	}
	e.dir = dir
	if got := e.Send("photo", f.chatID, "Recipient", text); got != "not_sent" {
		t.Fatal("replay returned a phantom ambiguous result")
	}
	if err := e.save(); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join(dir, "session.json"))
	if err != nil {
		t.Fatal(err)
	}
	var persisted storedSession
	if json.Unmarshal(data, &persisted) != nil || len(persisted.Sends) != 0 || len(persisted.Order) != 0 {
		t.Fatal("later save persisted the failed text invocation")
	}
	if got := e.sendPreparedText("photo", f.chatID, text, digest, msg); got != "sent" {
		t.Fatalf("safe retry after storage recovery: %s", got)
	}
	steps := len(f.steps)
	if got := e.Send("photo", f.chatID, "Recipient", text); got != "sent" || len(f.steps) != steps {
		t.Fatal("confirmed retry was dispatched again")
	}
}
