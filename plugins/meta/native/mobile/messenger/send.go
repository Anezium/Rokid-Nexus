package messenger

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

	"go.mau.fi/mautrix-meta/pkg/messagix/httpclient"
	"go.mau.fi/mautrix-meta/pkg/messagix/methods"
	"go.mau.fi/mautrix-meta/pkg/messagix/socket"
	"go.mau.fi/mautrix-meta/pkg/messagix/table"
)

const maxImageBytes = 8 << 20

type outcome struct {
	State  string `json:"state"`
	Reason string `json:"reason,omitempty"`
}

// admission is written before dispatch; its offline threading id is Meta's
// idempotency key, so an upstream retry of the same task cannot duplicate it.
type admission struct {
	Outcome string `json:"outcome"`
	OTID    int64  `json:"otid"`
}

func notSent(reason string) string { return encode(outcome{State: "not_sent", Reason: reason}) }

func (e *Engine) admissionPath(scope int64, id string) string {
	sum := sha256.Sum256([]byte(strconv.FormatInt(scope, 10) + "\x00" + id))
	return filepath.Join(e.dir, sendsDirectory, hex.EncodeToString(sum[:]))
}

// admit records the send durably before dispatch. It returns false when this id
// was already admitted: an earlier outcome is reported, never submitted again.
func admit(path string, otid int64) (bool, error) {
	file, err := os.OpenFile(path+".admitted", os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if errors.Is(err, os.ErrExist) {
		return false, nil
	} else if err != nil {
		return false, err
	}
	_, err = file.Write([]byte(encode(admission{Outcome: "admitted_unknown", OTID: otid})))
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

func (e *Engine) target(id string) (*connection, string) {
	if !validSendID(id) {
		return nil, "invalid_request"
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.conn == nil || !e.conn.isConnected() || e.connState != "connected" {
		return nil, "not_connected"
	}
	return e.conn, ""
}

// selfTask addresses the account's own one-to-one thread, whose key is its own FBID.
func selfTask(fbid, otid int64) *socket.SendMessageTask {
	return &socket.SendMessageTask{
		ThreadId: fbid, Otid: otid, Source: table.MESSENGER_INBOX_IN_THREAD,
		InitiatingSource: table.FACEBOOK_INBOX, SendType: table.TEXT, SyncGroup: 1,
	}
}

func (e *Engine) dispatch(conn *connection, id string, task *socket.SendMessageTask) string {
	e.sendMu.Lock()
	defer e.sendMu.Unlock()
	path := e.admissionPath(conn.session.FBID, id)
	fresh, err := admit(path, task.Otid)
	if err != nil {
		return notSent("admission_unavailable")
	}
	if !fresh {
		return previousOutcome(path)
	}
	ctx, cancel := context.WithTimeout(e.ctx, 30*time.Second)
	defer cancel()
	if conn.client.WaitUntilCanSendMessages(ctx, 15*time.Second) != nil {
		// Nothing was written to the socket, but the admission stays: never resubmit.
		return encode(outcome{State: "unknown"})
	}
	response, err := conn.client.ExecuteTasks(ctx, task)
	if err != nil || response == nil {
		return encode(outcome{State: "unknown"})
	}
	otid := strconv.FormatInt(task.Otid, 10)
	acked := false
	for _, replace := range response.LSReplaceOptimsiticMessage {
		if replace.OfflineThreadingId == otid && replace.MessageId != "" {
			acked = true
		}
	}
	if !acked {
		if len(response.LSMarkOptimisticMessageFailed) > 0 {
			return encode(outcome{State: "not_sent", Reason: "rejected"})
		}
		return encode(outcome{State: "unknown"})
	}
	out := outcome{State: "acked"}
	if writePrivate(path+".result", out) != nil {
		return encode(outcome{State: "unknown"})
	}
	return encode(out)
}

// SendSelfText sends owner-confirmed text to the account's own thread only. The
// recipient is derived from the verified identity, never taken as input.
func (e *Engine) SendSelfText(id, text string) string {
	conn, reason := e.target(id)
	if conn == nil {
		return notSent(reason)
	}
	if strings.TrimSpace(text) == "" || !utf8.ValidString(text) || utf8.RuneCountInString(text) > 1000 {
		return notSent("invalid_text")
	}
	if _, err := os.Stat(e.admissionPath(conn.session.FBID, id) + ".admitted"); err == nil {
		return previousOutcome(e.admissionPath(conn.session.FBID, id))
	}
	task := selfTask(conn.session.FBID, methods.GenerateEpochID())
	task.Text = text
	return e.dispatch(conn, id, task)
}

// SendSelfImage sends one owner-confirmed JPEG or PNG to the account's own thread.
func (e *Engine) SendSelfImage(id string, data []byte) string {
	conn, reason := e.target(id)
	if conn == nil {
		return notSent(reason)
	}
	mime := http.DetectContentType(data)
	if len(data) == 0 || len(data) > maxImageBytes || (mime != "image/jpeg" && mime != "image/png") {
		return notSent("invalid_image")
	}
	return e.sendImageTo(conn, id, conn.session.FBID, data, mime)
}

// SendImage sends one owner-confirmed JPEG or PNG to a conversation the owner picked,
// still listed, sendable and under the title it was shown with.
func (e *Engine) SendImage(id, chatID, title string, data []byte) string {
	conn, reason := e.target(id)
	if conn == nil {
		return notSent(reason)
	}
	mime := http.DetectContentType(data)
	if len(data) == 0 || len(data) > maxImageBytes || (mime != "image/jpeg" && mime != "image/png") {
		return notSent("invalid_image")
	}
	conn.tableMu.Lock()
	listed := inboxChats(conn.table, conn.session.FBID)
	conn.tableMu.Unlock()
	for _, c := range listed {
		if c.ID == chatID && c.Title == title {
			if !c.Supported {
				return notSent("encrypted_chat_unsupported")
			}
			key, err := strconv.ParseInt(c.ID, 10, 64)
			if err != nil {
				break
			}
			return e.sendImageTo(conn, id, key, data, mime)
		}
	}
	return notSent("recipient_changed")
}

// sendImageTo uploads first, since an upload creates no message, then admits and sends once.
func (e *Engine) sendImageTo(conn *connection, id string, threadKey int64, data []byte, mime string) string {
	path := e.admissionPath(conn.session.FBID, id)
	if _, err := os.Stat(path + ".admitted"); err == nil {
		return previousOutcome(path)
	}
	ctx, cancel := context.WithTimeout(e.ctx, 30*time.Second)
	mediaID, err := conn.client.GetHTTP().SendRUploadRequest(ctx, conn.session.Native, &httpclient.RUploadMedia{
		Filename: "nexus-image" + map[string]string{"image/jpeg": ".jpg", "image/png": ".png"}[mime], MimeType: mime, MediaData: data,
	})
	cancel()
	if err != nil || mediaID == 0 {
		return notSent("upload_failed")
	}
	task := selfTask(threadKey, methods.GenerateEpochID())
	task.SendType = table.MEDIA
	task.AttachmentFBIds = []int64{mediaID}
	return e.dispatch(conn, id, task)
}

// Lookup reports a previous send outcome without contacting Messenger.
func (e *Engine) Lookup(id string) string {
	if !validSendID(id) {
		return notSent("invalid_request")
	}
	session, err := e.loadSession()
	if err != nil {
		return encode(outcome{State: "unknown"})
	}
	path := e.admissionPath(session.FBID, id)
	if _, err := os.Stat(path + ".admitted"); err != nil {
		return encode(outcome{State: "not_found"})
	}
	return previousOutcome(path)
}
