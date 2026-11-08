package instagram

import (
	"testing"

	"go.mau.fi/mautrix-meta/pkg/instameow/slidetypes"
)

func TestSharedReelsReadAsKindAuthorAndCaption(t *testing.T) {
	x := &slidetypes.XMAContent{TargetURL: "https://www.instagram.com/reel/abc/", HeaderTitleText: "creator",
		CaptionBodyText: "a very\nfunny   clip", PreviewImage: &slidetypes.XMAPreviewImage{URL: "https://cdn/preview.jpg"}}
	line, preview := describeShare(x)
	if line != "Reel · @creator — a very funny clip" || preview != "https://cdn/preview.jpg" {
		t.Fatalf("unexpected %q %q", line, preview)
	}
	m := &slidetypes.Message{Content: slidetypes.MessageContentWrapper{Content: &slidetypes.MessageContentXMA{XMA: x, XMATextBody: "look"}}}
	if text, _ := describeContent(m); text != "look\nReel · @creator — a very funny clip" {
		t.Fatalf("shared text lost: %q", text)
	}
	photos := &slidetypes.Message{Content: slidetypes.MessageContentWrapper{Content: &slidetypes.MessageContentImage{
		Attachments: []*slidetypes.Attachment{{PreviewCDNURL: "p1"}, {PreviewCDNURL: "p2"}}}}}
	if text, preview := describeContent(photos); text != "[2 photos]" || preview != "p1" {
		t.Fatalf("unexpected %q %q", text, preview)
	}
	if text, _ := describeContent(&slidetypes.Message{Content: slidetypes.MessageContentWrapper{Content: &slidetypes.MessageContentAdminText{}}}); text != "" {
		t.Fatal("admin messages must be skipped")
	}
}
