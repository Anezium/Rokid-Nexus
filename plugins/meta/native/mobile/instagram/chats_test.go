package instagram

import (
	"testing"
	"time"

	"go.mau.fi/mautrix-meta/pkg/instameow/slidetypes"
	"go.mau.fi/util/jsontime"
)

func TestChatsAreRecentFirstAndNamedFromParticipants(t *testing.T) {
	thread := func(id, title string, at int64, users ...*slidetypes.User) slidetypes.Node[slidetypes.WrappedThreadInfo] {
		info := &slidetypes.ThreadInfo{ID: id, ThreadTitle: title, Users: users,
			LastActivityTimestampMS: jsontime.UnixMilliString{Time: time.UnixMilli(at)}}
		return slidetypes.Node[slidetypes.WrappedThreadInfo]{Node: slidetypes.WrappedThreadInfo{AsIGDirectThread: info}}
	}
	var box slidetypes.Mailbox
	box.ThreadsByFolder.Edges = append(box.ThreadsByFolder.Edges,
		thread("a", "", 1, &slidetypes.User{Username: "anna"}),
		thread("b", "Team", 3),
		thread("c", "", 2, &slidetypes.User{FullName: "Chris P"}),
		thread("a", "duplicate", 9))
	chats := mailboxChats(&box)
	if len(chats) != 3 || chats[0].Title != "Team" || chats[1].Title != "Chris P" || chats[2].Title != "anna" {
		t.Fatalf("unexpected chats %+v", chats)
	}
}

func TestBlankNamesFallBackToTheUsername(t *testing.T) {
	info := &slidetypes.ThreadInfo{ID: "a", ThreadTitle: "\u200b", Users: []*slidetypes.User{{FullName: "\u3164\u3164", Username: "anezium"}}}
	if title := threadTitle(info); title != "anezium" {
		t.Fatalf("title %q", title)
	}
	info.Users = []*slidetypes.User{{FullName: "\u3164"}}
	if title := threadTitle(info); title != "You" {
		t.Fatalf("title %q", title)
	}
}

func TestSendingToAChatNeedsAConnection(t *testing.T) {
	engine, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer engine.Close()
	if engine.SendText("0123456789abcdef", "a", "anna", "hi") != `{"state":"not_sent","reason":"not_connected"}` {
		t.Fatal("send accepted without a connection")
	}
	if _, err := engine.Chats(); err == nil {
		t.Fatal("chats listed without a connection")
	}
}

func TestThreadMessagesMarkOwnMessagesAndFallBackForMedia(t *testing.T) {
	thread := &slidetypes.ThreadInfo{SlideMessages: &slidetypes.SlideMessages{Edges: []slidetypes.Node[*slidetypes.Message]{
		{Node: &slidetypes.Message{ID: "b", SenderFBID: 7, TextBody: "mine", TimestampMS: jsontime.UnixMilliString{Time: time.UnixMilli(2)}}},
		{Node: &slidetypes.Message{ID: "a", SenderFBID: 8, TimestampMS: jsontime.UnixMilliString{Time: time.UnixMilli(1)}}},
	}}}
	got := threadMessages(thread, 7, 30)
	if len(got) != 2 || got[0].ID != "a" || got[0].Mine || got[0].Text != "[Attachment]" || !got[1].Mine || got[1].Text != "mine" {
		t.Fatalf("unexpected history %+v", got)
	}
}

func TestSendingAnImageToAChatNeedsAConnection(t *testing.T) {
	engine, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer engine.Close()
	if engine.SendImage("0123456789abcdef", "a", "anna", []byte{0xff, 0xd8, 0xff}) != `{"state":"not_sent","reason":"not_connected"}` {
		t.Fatal("image accepted without a connection")
	}
}
