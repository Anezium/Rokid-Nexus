package messenger

import (
	"testing"

	"go.mau.fi/mautrix-meta/pkg/messagix/table"
)

func TestChatsNameOneToOneThreadsAndFlagEncryptedOnes(t *testing.T) {
	inbox := &table.LSTable{
		LSVerifyContactRowExists: []*table.LSVerifyContactRowExists{{ContactId: 11, Name: "Anna"}},
		LSDeleteThenInsertThread: []*table.LSDeleteThenInsertThread{
			{ThreadKey: 11, ThreadType: table.ONE_TO_ONE, LastActivityTimestampMs: 1},
			{ThreadKey: 7, ThreadType: table.ONE_TO_ONE, LastActivityTimestampMs: 2},
			{ThreadKey: 12, ThreadType: table.ENCRYPTED_OVER_WA_ONE_TO_ONE, ThreadName: "Bob", LastActivityTimestampMs: 3},
			{ThreadKey: 13, ThreadType: table.ONE_TO_ONE, LastActivityTimestampMs: 4},
			{ThreadKey: 14, ThreadType: table.GROUP_THREAD, ThreadName: "Team", LastActivityTimestampMs: 5},
		},
	}
	chats := inboxChats(inbox, 7)
	if len(chats) != 4 {
		t.Fatalf("unnamed thread not skipped: %+v", chats)
	}
	if chats[0].Title != "Team" || !chats[0].Group || chats[1].Title != "Bob" || chats[1].Supported ||
		chats[2].Title != "You" || chats[3].Title != "Anna" || !chats[3].Supported {
		t.Fatalf("unexpected chats %+v", chats)
	}
}

func TestChatsCarryTheirLoadedMessagesOldestFirst(t *testing.T) {
	inbox := &table.LSTable{
		LSVerifyContactRowExists: []*table.LSVerifyContactRowExists{{ContactId: 11, Name: "Anna"}},
		LSDeleteThenInsertThread: []*table.LSDeleteThenInsertThread{{ThreadKey: 14, ThreadType: table.GROUP_THREAD, ThreadName: "Team"}},
		LSUpsertMessage: []*table.LSUpsertMessage{
			{MessageId: "m2", ThreadKey: 14, SenderId: 7, Text: "second", TimestampMs: 2},
			{MessageId: "m1", ThreadKey: 14, SenderId: 11, Text: "first", TimestampMs: 1},
			{MessageId: "m3", ThreadKey: 14, SenderId: 11, Text: "unsent", TimestampMs: 3, UnsentTimestampMs: 4},
		},
	}
	got := inboxChats(inbox, 7)[0].Messages
	if len(got) != 2 || got[0].Text != "first" || got[0].Mine || got[0].Sender != "Anna" || got[1].Text != "second" || !got[1].Mine || got[1].Sender != "" {
		t.Fatalf("unexpected history %+v", got)
	}
}

func TestSendingAnImageToAChatNeedsAConnection(t *testing.T) {
	engine, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer engine.Close()
	if engine.SendImage("0123456789abcdef", "7", "Anna", []byte{0xff, 0xd8, 0xff}) != `{"state":"not_sent","reason":"not_connected"}` {
		t.Fatal("image accepted without a connection")
	}
}
