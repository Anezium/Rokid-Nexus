package instagram

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"go.mau.fi/mautrix-meta/pkg/instameow/slidetypes"
	"go.mau.fi/mautrix-meta/pkg/messagix/httpclient"
	"go.mau.fi/mautrix-meta/pkg/messagix/methods"
)

const maxImageBytes = 8 << 20

type outcome struct {
	State  string `json:"state"`
	Reason string `json:"reason,omitempty"`
}

func notSent(reason string) string { return encode(outcome{State: "not_sent", Reason: reason}) }

func (e *Engine) admissionPath(scope, id string) string {
	sum := sha256.Sum256([]byte(scope + "\x00" + id))
	return filepath.Join(e.dir, sendsDirectory, hex.EncodeToString(sum[:]))
}

// admit records the send durably before dispatch. It returns false when this id
// was already admitted: an earlier outcome is reported, never submitted again.
func admit(path string) (bool, error) {
	file, err := os.OpenFile(path+".admitted", os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if errors.Is(err, os.ErrExist) {
		return false, nil
	} else if err != nil {
		return false, err
	}
	_, err = file.Write([]byte(`{"outcome":"admitted_unknown"}`))
	if err == nil {
		err = file.Sync()
	}
	if closeErr := file.Close(); err == nil {
		err = closeErr
	}
	return err == nil, err
}

func previousOutcome(path string) string {
	var out outcome
	if readPrivate(path+".result", &out) == nil && out.State == "acked" {
		return encode(out)
	}
	return encode(outcome{State: "unknown"})
}

func validSendID(id string) bool {
	if len(id) < 16 || len(id) > 128 {
		return false
	}
	for _, c := range id {
		if !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-') {
			return false
		}
	}
	return true
}

type selfTarget struct {
	conn  *connection
	scope string
}

func (e *Engine) target(id string) (*selfTarget, string) {
	if !validSendID(id) {
		return nil, "invalid_request"
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.conn == nil || !e.conn.isConnected() || e.connState != "connected" {
		return nil, "not_connected"
	}
	return &selfTarget{conn: e.conn, scope: e.conn.session.UserID}, ""
}

// dispatch runs exactly one armed send for an admitted id and records the result.
func (e *Engine) dispatch(target *selfTarget, path string, send func(ctx context.Context, otid string) (slidetypes.SentMessage, error)) string {
	e.sendMu.Lock()
	defer e.sendMu.Unlock()
	fresh, err := admit(path)
	if err != nil {
		return notSent("admission_unavailable")
	}
	if !fresh {
		return previousOutcome(path)
	}
	otid := strconv.FormatInt(methods.GenerateEpochID(), 10)
	ctx, cancel := context.WithTimeout(e.ctx, 20*time.Second)
	defer cancel()
	target.conn.boundary.armed.Store(true)
	message, err := send(ctx, otid)
	target.conn.boundary.armed.Store(false)
	if err != nil || message.ID == "" || message.MessageID == "" {
		return encode(outcome{State: "unknown"})
	}
	out := outcome{State: "acked"}
	if writePrivate(path+".result", out) != nil {
		return encode(outcome{State: "unknown"})
	}
	return encode(out)
}

// SendSelfText sends owner-confirmed text to the authenticated account's own
// profile only. The recipient is derived from the verified identity, never input.
func (e *Engine) SendSelfText(id, text string) string {
	target, reason := e.target(id)
	if target == nil {
		return notSent(reason)
	}
	if strings.TrimSpace(text) == "" || !utf8.ValidString(text) || utf8.RuneCountInString(text) > 1000 {
		return notSent("invalid_text")
	}
	client := target.conn.client
	return e.dispatch(target, e.admissionPath(target.scope, id), func(ctx context.Context, otid string) (slidetypes.SentMessage, error) {
		response, err := client.SendMessage(ctx, &slidetypes.SendTextRequest{
			RecipientIGIDs: []string{target.scope}, OfflineThreadingID: otid,
			Text:     slidetypes.SensitiveString{Value: text},
			Mentions: []slidetypes.InputMention{}, MentionedUserIDs: []string{}, Commands: []slidetypes.InputCommand{},
		})
		if err != nil || response == nil {
			return slidetypes.SentMessage{}, errors.New("unconfirmed")
		}
		return response.Message, nil
	})
}

// selfThread is the unique one-to-one thread keyed by the owner's own FBID.
func selfThread(mailbox *slidetypes.Mailbox, ownFBID int64) *slidetypes.ThreadInfo {
	if mailbox == nil || ownFBID <= 0 {
		return nil
	}
	var found *slidetypes.ThreadInfo
	for _, edge := range mailbox.ThreadsByFolder.Edges {
		thread := edge.Node.AsIGDirectThread
		if thread == nil || thread.IsGroup || thread.ThreadKey != ownFBID {
			continue
		}
		if found != nil || thread.ID == "" || len(thread.Users) != 0 {
			return nil
		}
		found = thread
	}
	return found
}

// SendSelfImage sends one owner-confirmed JPEG or PNG to the existing self thread.
func (e *Engine) SendSelfImage(id string, data []byte) string {
	target, reason := e.target(id)
	if target == nil {
		return notSent(reason)
	}
	mime := http.DetectContentType(data)
	if len(data) == 0 || len(data) > maxImageBytes || (mime != "image/jpeg" && mime != "image/png") {
		return notSent("invalid_image")
	}
	thread := selfThread(target.conn.mailbox, target.conn.client.GetOwnFBID())
	if thread == nil {
		return notSent("self_thread_unavailable")
	}
	return e.sendImageTo(target, id, thread, data, mime)
}

// SendImage sends one owner-confirmed JPEG or PNG to a conversation the owner picked,
// still listed under the title it was shown with.
func (e *Engine) SendImage(id, chatID, title string, data []byte) string {
	target, reason := e.target(id)
	if target == nil {
		return notSent(reason)
	}
	mime := http.DetectContentType(data)
	if len(data) == 0 || len(data) > maxImageBytes || (mime != "image/jpeg" && mime != "image/png") {
		return notSent("invalid_image")
	}
	var thread *slidetypes.ThreadInfo
	if target.conn.mailbox != nil {
		for _, edge := range target.conn.mailbox.ThreadsByFolder.Edges {
			if candidate := edge.Node.AsIGDirectThread; candidate != nil && candidate.ID == chatID && threadTitle(candidate) == title {
				thread = candidate
				break
			}
		}
	}
	if thread == nil || thread.ThreadKey == 0 {
		return notSent("recipient_changed")
	}
	return e.sendImageTo(target, id, thread, data, mime)
}

// sendImageTo uploads first, since an upload creates no message, then admits and sends once.
func (e *Engine) sendImageTo(target *selfTarget, id string, thread *slidetypes.ThreadInfo, data []byte, mime string) string {
	client := target.conn.client
	path := e.admissionPath(target.scope, id)
	if _, err := os.Stat(path + ".admitted"); err == nil {
		return previousOutcome(path)
	}
	// Uploading creates no message, so it happens before the irreversible admission.
	uploadContext, cancel := context.WithTimeout(e.ctx, 30*time.Second)
	upload, err := client.GetHTTP().SendMercuryUploadRequest(uploadContext, thread.ThreadKey, &httpclient.MercuryUploadMedia{
		Filename: "nexus-image" + map[string]string{"image/jpeg": ".jpg", "image/png": ".png"}[mime], MimeType: mime, MediaData: data,
	})
	cancel()
	if err != nil || upload == nil || upload.Payload.RealMetadata == nil || upload.Payload.RealMetadata.GetFbId() == 0 {
		return notSent("upload_failed")
	}
	attachment := strconv.FormatInt(upload.Payload.RealMetadata.GetFbId(), 10)
	return e.dispatch(target, path, func(ctx context.Context, otid string) (slidetypes.SentMessage, error) {
		response, err := client.SendMedia(ctx, &slidetypes.SendMediaRequest{AttachmentFBID: attachment, ThreadID: thread.ID, OfflineThreadingID: otid})
		if err != nil || response == nil {
			return slidetypes.SentMessage{}, errors.New("unconfirmed")
		}
		return response.Message, nil
	})
}

// Lookup reports a previous send outcome without contacting Instagram.
func (e *Engine) Lookup(id string) string {
	if !validSendID(id) {
		return notSent("invalid_request")
	}
	session, _, err := e.storedSession()
	if err != nil {
		return encode(outcome{State: "unknown"})
	}
	path := e.admissionPath(session.UserID, id)
	if _, err := os.Stat(path + ".admitted"); err != nil {
		return encode(outcome{State: "not_found"})
	}
	return previousOutcome(path)
}
