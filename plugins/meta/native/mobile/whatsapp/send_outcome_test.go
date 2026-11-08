package whatsapp

import (
	"context"
	"encoding/json"
	"errors"
	"path/filepath"
	"testing"

	engine "github.com/Anezium/Rokid-Nexus/plugins/meta/native/internal/whatsappengine"
	"go.mau.fi/whatsmeow/proto/waE2E"
)

type offlineBackend struct {
	engine.Backend
	scope        string
	status       engine.Status
	contacts     []engine.Contact
	contactError error
	sends        int
}

func (b *offlineBackend) Status() engine.Status { return b.status }
func (b *offlineBackend) AccountScope() string  { return b.scope }
func (b *offlineBackend) Contacts(context.Context) ([]engine.Contact, error) {
	return b.contacts, b.contactError
}
func (b *offlineBackend) MessageID() string                                 { return "synthetic-message-id" }
func (b *offlineBackend) Chats(context.Context, int) ([]engine.Chat, error) { return nil, nil }
func (b *offlineBackend) Thumbnail(context.Context, string) ([]byte, error) { return nil, nil }
func (b *offlineBackend) RequestHistory(context.Context, string, int) error { return nil }
func (b *offlineBackend) PrepareImage(context.Context, []byte) (*waE2E.Message, error) {
	return &waE2E.Message{}, nil
}
func (b *offlineBackend) SendPrepared(context.Context, string, *waE2E.Message, string) (int64, error) {
	return 0, errors.New("offline")
}
func (b *offlineBackend) Send(context.Context, string, string, string) (int64, error) {
	b.sends++
	return 0, errors.New("synthetic ambiguous transport failure")
}

func offlineEngine(t *testing.T) (*Engine, *offlineBackend) {
	t.Helper()
	outbox, err := engine.OpenOutbox(filepath.Join(t.TempDir(), "outbox.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { outbox.Close() })
	b := &offlineBackend{scope: "synthetic-account"}
	return &Engine{ctx: context.Background(), wa: b, outbox: outbox}, b
}
func outcomeState(t *testing.T, raw string, err error) string {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
	var outcome engine.Outcome
	if err := json.Unmarshal([]byte(raw), &outcome); err != nil {
		t.Fatal(err)
	}
	return outcome.State
}

func TestPreAdmissionRefusalsAreNotSentAndLeaveNoAdmission(t *testing.T) {
	for _, reason := range []string{"disconnected", "recipient_changed", "contacts_unavailable", "expired"} {
		t.Run(reason, func(t *testing.T) {
			e, b := offlineEngine(t)
			b.status = engine.Status{Link: "linked", Connected: true, LoggedIn: true}
			switch reason {
			case "disconnected":
				b.status.LoggedIn = false
			case "contacts_unavailable":
				b.contactError = errors.New("synthetic")
			case "expired":
				b.contacts = []engine.Contact{{JID: "synthetic", Name: "Test"}}
				ctx, cancel := context.WithCancel(context.Background())
				cancel()
				e.ctx = ctx
			}
			id := "synthetic-send-0001"
			raw, err := e.Send(id, "synthetic", "Test", "Synthetic text")
			if outcomeState(t, raw, err) != "not_sent" || b.sends != 0 {
				t.Fatal("refusal was dispatched or labeled ambiguous")
			}
			raw, err = e.Lookup(id)
			if outcomeState(t, raw, err) != "not_found" {
				t.Fatal("refusal consumed durable admission")
			}
		})
	}
}

func TestAmbiguousAdmissionSurvivesAccountChangeAndValidationFailure(t *testing.T) {
	e, b := offlineEngine(t)
	b.status = engine.Status{Link: "linked", Connected: true, LoggedIn: true}
	b.contacts = []engine.Contact{{JID: "synthetic", Name: "Test"}}
	id := "synthetic-send-0001"
	raw, err := e.Send(id, "synthetic", "Test", "Synthetic text")
	if outcomeState(t, raw, err) != "unknown" || b.sends != 1 {
		t.Fatal("ambiguous transport outcome not retained")
	}
	b.scope = "another-synthetic-account"
	raw, err = e.Lookup(id)
	if outcomeState(t, raw, err) != "unknown" {
		t.Fatal("account switch erased evidence of admission")
	}
	b.status.LoggedIn = false
	raw, err = e.Send(id, "synthetic", "Test", "Synthetic text")
	if outcomeState(t, raw, err) != "unknown" || b.sends != 1 {
		t.Fatal("old admission declared unsent or dispatched twice")
	}
}

func TestImageSendRefusalsLeaveNoAdmission(t *testing.T) {
	e, b := offlineEngine(t)
	jpeg := []byte{0xff, 0xd8, 0xff, 0xe0, 0, 0x10, 'J', 'F', 'I', 'F', 0}
	if imageState(t, func() (string, error) { return e.SendImage("0123456789abcdef", "1@s.whatsapp.net", "Anna", jpeg) }) != "not_sent" {
		t.Fatal("image accepted while disconnected")
	}
	b.status = engine.Status{Link: "linked", Connected: true, LoggedIn: true}
	if imageState(t, func() (string, error) { return e.SendImage("0123456789abcdef", "1@s.whatsapp.net", "Anna", jpeg) }) != "not_sent" {
		t.Fatal("image accepted for a conversation that is not listed")
	}
	if imageState(t, func() (string, error) { return e.SendImage("0123456789abcdef", "1@s.whatsapp.net", "Anna", nil) }) != "not_sent" {
		t.Fatal("empty image accepted")
	}
	if admitted, err := e.outbox.HasAdmission(context.Background(), "0123456789abcdef"); err != nil || admitted {
		t.Fatal("a refused image left an admission")
	}
}

func imageState(t *testing.T, send func() (string, error)) string {
	t.Helper()
	raw, err := send()
	return outcomeState(t, raw, err)
}
