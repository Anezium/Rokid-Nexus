package instagram

import (
	"fmt"
	"strings"

	"go.mau.fi/mautrix-meta/pkg/instameow/slidetypes"
)

const maxShareCaption = 140

func clipText(s string, limit int) string {
	s = strings.Join(strings.Fields(s), " ")
	if r := []rune(s); len(r) > limit {
		return string(r[:limit-1]) + "…"
	}
	return s
}

func countLabel(n int, one, many string) string {
	if n <= 1 {
		return "[" + one + "]"
	}
	return fmt.Sprintf("[%d %s]", n, many)
}

func firstPreview(attachments []*slidetypes.Attachment) string {
	for _, a := range attachments {
		if a == nil {
			continue
		}
		for _, url := range []string{a.PreviewCDNURL, a.PreviewCDNFallbackURL, a.AttachmentCDNURL} {
			if url != "" {
				return url
			}
		}
	}
	return ""
}

// shareKind names what a shared link points at, from its Instagram URL.
func shareKind(url string) string {
	switch {
	case strings.Contains(url, "/reel/"), strings.Contains(url, "/reels/"):
		return "Reel"
	case strings.Contains(url, "/stories/"):
		return "Story"
	case strings.Contains(url, "/p/"):
		return "Post"
	case url != "":
		return "Link"
	default:
		return "Shared"
	}
}

// describeShare turns a shared reel, post, story or link into one readable line.
func describeShare(x *slidetypes.XMAContent) (string, string) {
	if x == nil {
		return "[Shared]", ""
	}
	line := shareKind(x.TargetURL)
	author := strings.TrimSpace(x.HeaderTitleText)
	if author == "" {
		if title, ok := x.XMAHeaderTitle.(string); ok {
			author = strings.TrimSpace(title)
		}
	}
	if author != "" {
		if !strings.HasPrefix(author, "@") && !strings.Contains(author, " ") {
			author = "@" + author
		}
		line += " · " + author
	}
	caption := strings.TrimSpace(x.CaptionBodyText)
	if caption == "" {
		caption = strings.TrimSpace(x.TitleText)
	}
	if caption == "" {
		caption = strings.TrimSpace(x.XMATitle)
	}
	if caption != "" {
		line += " — " + clipText(caption, maxShareCaption)
	}
	preview := ""
	for _, image := range []*slidetypes.XMAPreviewImage{x.PreviewImage, x.XMAPreviewImage} {
		if image != nil && image.URL != "" {
			preview = image.URL
			break
		}
	}
	return line, preview
}

// describeContent returns the text shown for a message without a text body, and a
// preview image URL when the content has one. Empty text means "do not show".
func describeContent(m *slidetypes.Message) (string, string) {
	switch c := m.Content.Content.(type) {
	case *slidetypes.MessageContentImage:
		return countLabel(len(c.Attachments), "Photo", "photos"), firstPreview(c.Attachments)
	case *slidetypes.MessageContentVideo:
		return countLabel(len(c.Videos), "Video", "videos"), firstPreview(c.Videos)
	case *slidetypes.MessageContentMultiMedia:
		return countLabel(len(c.Attachments), "Photo", "photos and videos"), firstPreview(c.Attachments)
	case *slidetypes.MessageContentRavenImage:
		return "[Disappearing photo]", ""
	case *slidetypes.MessageContentRavenVideo:
		return "[Disappearing video]", ""
	case *slidetypes.MessageContentAudio:
		return "[Voice message]", ""
	case *slidetypes.MessageContentAnimatedMedia:
		return "[GIF]", ""
	case *slidetypes.MessageContentSticker:
		return "[Sticker]", ""
	case *slidetypes.MessageContentMusicSticker:
		return "[Music]", ""
	case *slidetypes.MessageContentXMA:
		line, preview := describeShare(c.XMA)
		if body := strings.TrimSpace(c.XMATextBody); body != "" {
			line = clipText(body, maxShareCaption) + "\n" + line
		}
		return line, preview
	case *slidetypes.MessageContentAdminText:
		return "", ""
	}
	if snippet := strings.TrimSpace(m.IGDSnippet); snippet != "" {
		return snippet, ""
	}
	return "[Attachment]", ""
}
