package messenger

import (
	"strings"

	"go.mau.fi/mautrix-meta/pkg/messagix/table"
)

const maxShareCaption = 140

// attachmentLine is how a message without text reads, with a preview image URL when
// there is one. Contents and URLs are never logged.
type attachmentLine struct {
	text    string
	preview string
}

func clipText(s string, limit int) string {
	s = strings.Join(strings.Fields(s), " ")
	if r := []rune(s); len(r) > limit {
		return string(r[:limit-1]) + "…"
	}
	return s
}

func shareKind(url string) string {
	switch {
	case strings.Contains(url, "/reel/"), strings.Contains(url, "/reels/"):
		return "Reel"
	case strings.Contains(url, "/stories/"), strings.Contains(url, "/story"):
		return "Story"
	case strings.Contains(url, "/p/"), strings.Contains(url, "/posts/"), strings.Contains(url, "/permalink"):
		return "Post"
	case strings.Contains(url, "/watch"), strings.Contains(url, "/videos/"):
		return "Video"
	case url != "":
		return "Link"
	default:
		return "Shared"
	}
}

func describeAttachment(kind table.AttachmentType, filename, title, subtitle, actionURL string) string {
	switch kind {
	case table.AttachmentTypeImage:
		return "[Photo]"
	case table.AttachmentTypeVideo:
		return "[Video]"
	case table.AttachmentTypeAudio, table.AttachmentTypeSoundBite:
		return "[Voice message]"
	case table.AttachmentTypeAnimatedImage:
		return "[GIF]"
	case table.AttachmentTypeSticker, table.AttachmentTypeSelfieSticker, table.AttachmentTypeThirdPartySticker:
		return "[Sticker]"
	case table.AttachmentTypeEphemeralImage:
		return "[Disappearing photo]"
	case table.AttachmentTypeEphemeralVideo:
		return "[Disappearing video]"
	case table.AttachmentTypeFile:
		if name := strings.TrimSpace(filename); name != "" {
			return "[File] " + clipText(name, 60)
		}
		return "[File]"
	case table.AttachmentTypeXMA:
		line := shareKind(actionURL)
		if title = strings.TrimSpace(title); title != "" {
			line += " · " + clipText(title, 60)
		}
		if subtitle = strings.TrimSpace(subtitle); subtitle != "" {
			line += " — " + clipText(subtitle, maxShareCaption)
		}
		return line
	}
	return "[Attachment]"
}

// attachmentLines describes the first attachment of each message in the table.
func attachmentLines(inbox *table.LSTable) map[string]attachmentLine {
	lines := map[string]attachmentLine{}
	put := func(id string, line attachmentLine) {
		if id != "" {
			if _, seen := lines[id]; !seen {
				lines[id] = line
			}
		}
	}
	for _, a := range inbox.LSInsertXmaAttachment {
		if a != nil {
			put(a.MessageId, attachmentLine{describeAttachment(table.AttachmentTypeXMA, a.Filename, a.TitleText, a.SubtitleText, a.ActionUrl), a.PreviewUrl})
		}
	}
	for _, a := range inbox.LSInsertAttachment {
		if a != nil {
			put(a.MessageId, attachmentLine{describeAttachment(a.AttachmentType, a.Filename, a.TitleText, a.SubtitleText, a.ActionUrl), a.PreviewUrl})
		}
	}
	for _, a := range inbox.LSInsertBlobAttachment {
		if a != nil {
			put(a.MessageId, attachmentLine{describeAttachment(a.AttachmentType, a.Filename, a.TitleText, a.SubtitleText, ""), a.PreviewUrl})
		}
	}
	for _, a := range inbox.LSInsertStickerAttachment {
		if a != nil {
			put(a.MessageId, attachmentLine{"[Sticker]", a.PreviewUrl})
		}
	}
	return lines
}
