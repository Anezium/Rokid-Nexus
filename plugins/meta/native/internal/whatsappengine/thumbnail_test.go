package whatsappengine

import (
	"bytes"
	"context"
	"database/sql"
	"image"
	"image/color"
	"image/jpeg"
	"path/filepath"
	"strings"
	"testing"

	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"google.golang.org/protobuf/proto"
)

func TestPhotoThumbnailsAreStoredAndNamedByTheirPreview(t *testing.T) {
	h, err := OpenHistory(filepath.Join(t.TempDir(), "messages.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer h.Close()
	anna := types.NewJID("33600000001", types.DefaultUserServer)
	thumb := []byte{0xff, 0xd8, 0xff, 1, 2, 3}
	h.Record(info(anna, "p1", false, 1), &waE2E.Message{ImageMessage: &waE2E.ImageMessage{JPEGThumbnail: thumb}}, "Anna")
	h.Record(info(anna, "t1", false, 2), &waE2E.Message{Conversation: proto.String("hi")}, "")
	// A view-once photo keeps its preview to itself.
	h.Record(info(anna, "v1", false, 3), &waE2E.Message{ViewOnceMessage: &waE2E.FutureProofMessage{
		Message: &waE2E.Message{ImageMessage: &waE2E.ImageMessage{JPEGThumbnail: thumb}}}}, "")
	chats, err := h.Chats(context.Background(), 10, func(types.JID) string { return "" })
	if err != nil || len(chats) != 1 || len(chats[0].Messages) != 3 {
		t.Fatalf("listing failed: %v %+v", err, chats)
	}
	photo, text, once := chats[0].Messages[0], chats[0].Messages[1], chats[0].Messages[2]
	if !strings.HasPrefix(photo.Preview, ThumbnailPrefix) || text.Preview != "" || once.Preview != "" {
		t.Fatalf("unexpected previews %+v", chats[0].Messages)
	}
	got, err := h.Thumbnail(context.Background(), photo.Preview)
	if err != nil || !bytes.Equal(got, thumb) {
		t.Fatalf("thumbnail %v %v", got, err)
	}
	for _, bad := range []string{"https://example.com/x.jpg", ThumbnailPrefix + "nochat", ThumbnailPrefix + anna.String() + "/missing"} {
		if _, err := h.Thumbnail(context.Background(), bad); err == nil {
			t.Fatalf("%q answered", bad)
		}
	}
}

func TestAHistorySyncFillsAThumbnailTheLiveMessageLacked(t *testing.T) {
	h, err := OpenHistory(filepath.Join(t.TempDir(), "messages.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer h.Close()
	anna := types.NewJID("33600000001", types.DefaultUserServer)
	h.Record(info(anna, "p1", false, 1), &waE2E.Message{ImageMessage: &waE2E.ImageMessage{}}, "Anna")
	h.Record(info(anna, "p1", false, 1), &waE2E.Message{ImageMessage: &waE2E.ImageMessage{JPEGThumbnail: []byte{1}}}, "")
	chats, _ := h.Chats(context.Background(), 10, func(types.JID) string { return "" })
	if len(chats) != 1 || len(chats[0].Messages) != 1 || chats[0].Messages[0].Preview == "" {
		t.Fatalf("thumbnail not filled: %+v", chats)
	}
}

func TestAHistoryFromBeforeThumbnailsGainsTheColumn(t *testing.T) {
	path := filepath.Join(t.TempDir(), "messages.sqlite")
	db, err := sql.Open("sqlite3", "file:"+path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = db.Exec(`CREATE TABLE messages (chat TEXT NOT NULL, id TEXT NOT NULL, from_me INTEGER NOT NULL, sender TEXT NOT NULL,
		text TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY (chat, id));
		INSERT INTO messages VALUES ('33600000001@s.whatsapp.net', 'old', 0, '', '[Photo]', 1);`); err != nil {
		t.Fatal(err)
	}
	db.Close()
	h, err := OpenHistory(path)
	if err != nil {
		t.Fatalf("old history refused: %v", err)
	}
	defer h.Close()
	anna := types.NewJID("33600000001", types.DefaultUserServer)
	h.Record(info(anna, "new", false, 2), &waE2E.Message{ImageMessage: &waE2E.ImageMessage{JPEGThumbnail: []byte{1}}}, "Anna")
	chats, _ := h.Chats(context.Background(), 10, func(types.JID) string { return "" })
	if len(chats) != 1 || len(chats[0].Messages) != 2 || chats[0].Messages[0].Preview != "" || chats[0].Messages[1].Preview == "" {
		t.Fatalf("unexpected %+v", chats)
	}
}

func TestSentPhotosCarryASmallJPEGThumbnail(t *testing.T) {
	src := image.NewRGBA(image.Rect(0, 0, 640, 480))
	for y := 0; y < 480; y++ {
		for x := 0; x < 640; x++ {
			src.Set(x, y, color.RGBA{uint8(x), uint8(y), 80, 255})
		}
	}
	var photo bytes.Buffer
	if err := jpeg.Encode(&photo, src, nil); err != nil {
		t.Fatal(err)
	}
	thumb := makeThumbnail(photo.Bytes())
	decoded, err := jpeg.Decode(bytes.NewReader(thumb))
	if err != nil {
		t.Fatalf("thumbnail is not a JPEG: %v", err)
	}
	if b := decoded.Bounds(); b.Dx() != sentThumbnailEdge || b.Dy() != 72 {
		t.Fatalf("thumbnail is %dx%d", b.Dx(), b.Dy())
	}
	if makeThumbnail([]byte("not an image")) != nil {
		t.Fatal("garbage got a thumbnail")
	}
}
