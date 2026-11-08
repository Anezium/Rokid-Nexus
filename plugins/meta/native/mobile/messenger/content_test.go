package messenger

import (
	"testing"

	"go.mau.fi/mautrix-meta/pkg/messagix/table"
)

func TestAttachmentsDescribeMessagesWithoutText(t *testing.T) {
	inbox := &table.LSTable{
		LSDeleteThenInsertThread: []*table.LSDeleteThenInsertThread{{ThreadKey: 11, ThreadType: table.ONE_TO_ONE, ThreadName: "Anna"}},
		LSUpsertMessage: []*table.LSUpsertMessage{
			{MessageId: "m1", ThreadKey: 11, SenderId: 11, TimestampMs: 1},
			{MessageId: "m2", ThreadKey: 11, SenderId: 11, Text: "watch this", TimestampMs: 2},
		},
		LSInsertAttachment:    []*table.LSInsertAttachment{{MessageId: "m1", AttachmentType: table.AttachmentTypeImage, PreviewUrl: "p1"}},
		LSInsertXmaAttachment: []*table.LSInsertXmaAttachment{{MessageId: "m2", AttachmentType: table.AttachmentTypeXMA, ActionUrl: "https://www.facebook.com/reel/1", TitleText: "Creator", SubtitleText: "caption"}},
	}
	got := inboxChats(inbox, 7)[0].Messages
	if len(got) != 2 || got[0].Text != "[Photo]" || got[0].Preview != "p1" || got[1].Text != "watch this\nReel · Creator — caption" {
		t.Fatalf("unexpected %+v", got)
	}
}
