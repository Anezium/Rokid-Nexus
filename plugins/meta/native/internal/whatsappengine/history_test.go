package whatsappengine

import (
	"context"
	"path/filepath"
	"testing"
	"time"

	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/proto"
)

func info(chat types.JID, id string, mine bool, at int64) types.MessageInfo {
	return types.MessageInfo{MessageSource: types.MessageSource{Chat: chat, IsFromMe: mine}, ID: types.MessageID(id), Timestamp: time.UnixMilli(at)}
}

func TestHistoryListsRecentChatsWithTheirLatestMessagesOldestFirst(t *testing.T) {
	h, err := OpenHistory(filepath.Join(t.TempDir(), "messages.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer h.Close()
	anna := types.NewJID("33600000001", types.DefaultUserServer)
	bob := types.NewJID("33600000002", types.DefaultUserServer)
	h.Record(info(anna, "a1", false, 1), &waE2E.Message{Conversation: proto.String("hi")}, "Anna")
	h.Record(info(anna, "a2", true, 3), &waE2E.Message{ExtendedTextMessage: &waE2E.ExtendedTextMessage{Text: proto.String("hey")}}, "")
	h.Record(info(anna, "a3", false, 2), &waE2E.Message{ImageMessage: &waE2E.ImageMessage{Caption: proto.String("look")}}, "")
	h.Record(info(bob, "b1", false, 9), &waE2E.Message{ReactionMessage: &waE2E.ReactionMessage{}}, "Bob")
	h.Record(info(anna, "a1", false, 1), &waE2E.Message{Conversation: proto.String("duplicate")}, "")
	chats, err := h.Chats(context.Background(), 2, func(types.JID) string { return "" })
	if err != nil || len(chats) != 1 {
		t.Fatalf("reactions created a chat or listing failed: %v %+v", err, chats)
	}
	got := chats[0]
	if got.Name != "Anna" || !got.Supported || got.IsGroup || len(got.Messages) != 2 ||
		got.Messages[0].Text != "[Photo] look" || got.Messages[1].Text != "hey" || !got.Messages[1].Mine {
		t.Fatalf("unexpected chat %+v", got)
	}
	anchor, err := h.Oldest(context.Background(), anna)
	if err != nil || anchor.ID != "a1" || anchor.IsFromMe {
		t.Fatalf("wrong anchor %+v %v", anchor, err)
	}
	h.Clear()
	if chats, _ := h.Chats(context.Background(), 10, func(types.JID) string { return "" }); len(chats) != 0 {
		t.Fatal("unlink did not clear the stored messages")
	}
}

func TestOwnSendsAreStoredAndContactNamesWin(t *testing.T) {
	h, err := OpenHistory(filepath.Join(t.TempDir(), "messages.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer h.Close()
	anna := types.NewJID("33600000001", types.DefaultUserServer)
	h.RecordOwn(anna, "s1", "dictated", time.UnixMilli(5))
	chats, _ := h.Chats(context.Background(), 10, func(types.JID) string { return "Anna Contact" })
	if len(chats) != 1 || chats[0].Name != "Anna Contact" || len(chats[0].Messages) != 1 || !chats[0].Messages[0].Mine {
		t.Fatalf("unexpected chats %+v", chats)
	}
	group := types.NewJID("12345", types.GroupServer)
	h.Record(info(group, "g1", false, 6), &waE2E.Message{Conversation: proto.String("team")}, "Team")
	chats, _ = h.Chats(context.Background(), 10, func(types.JID) string { return "" })
	if chats[0].JID != group.String() || !chats[0].IsGroup || !chats[0].Supported || chats[0].Name != "Team" {
		t.Fatalf("group chats must be listed and sendable: %+v", chats[0])
	}
}

func TestGroupsSeenLiveAreNamedLater(t *testing.T) {
	h, err := OpenHistory(filepath.Join(t.TempDir(), "messages.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer h.Close()
	group := types.NewJID("12345", types.GroupServer)
	h.Record(info(group, "g1", false, 6), &waE2E.Message{Conversation: proto.String("hi")}, "")
	chats, _ := h.Chats(context.Background(), 10, func(types.JID) string { return "" })
	if h.Named(group) || chats[0].Name != "WhatsApp group" {
		t.Fatalf("an unnamed group must not show its bare id: %+v", chats[0])
	}
	h.recordEvent(nil, &events.GroupInfo{JID: group, Name: &types.GroupName{Name: "Family"}})
	chats, _ = h.Chats(context.Background(), 10, func(types.JID) string { return "" })
	if !h.Named(group) || chats[0].Name != "Family" {
		t.Fatalf("a group rename must reach the list: %+v", chats[0])
	}
	h.Record(info(group, "g2", false, 7), &waE2E.Message{Conversation: proto.String("again")}, "")
	if chats, _ = h.Chats(context.Background(), 10, func(types.JID) string { return "" }); chats[0].Name != "Family" {
		t.Fatalf("a later unnamed message must keep the group name: %+v", chats[0])
	}
}

func TestOnlyPlainUserAndGroupAddressesAreSendable(t *testing.T) {
	for raw, want := range map[string]bool{
		"33600000001@s.whatsapp.net":   true,
		"120363000000000001@g.us":      true,
		"120363000000000001@lid":       true,
		"status@broadcast":             false,
		"33600000001:3@s.whatsapp.net": false,
		"@g.us":                        false,
	} {
		if got := validRecipient(raw) || validGroup(raw); got != want {
			t.Errorf("%s: sendable=%v, want %v", raw, got, want)
		}
	}
}
