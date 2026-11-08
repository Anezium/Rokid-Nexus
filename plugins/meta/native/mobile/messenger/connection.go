package messenger

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"time"

	"go.mau.fi/mautrix-meta/pkg/messagix"
	"go.mau.fi/mautrix-meta/pkg/messagix/table"
)

type connection struct {
	client    *messagix.Client
	session   *storedSession
	table     *table.LSTable
	tableMu   sync.Mutex
	cancel    context.CancelFunc
	connected atomic.Bool
	failed    atomic.Bool
}

func (c *connection) isConnected() bool { return c.connected.Load() && !c.failed.Load() }

// merge adds messages from a later update (new or fetched ones) to the inbox table.
func (c *connection) merge(update *table.LSTable) {
	c.tableMu.Lock()
	defer c.tableMu.Unlock()
	if c.table == nil || update == nil {
		return
	}
	c.table.LSUpsertMessage = append(c.table.LSUpsertMessage, update.LSUpsertMessage...)
	c.table.LSInsertMessage = append(c.table.LSInsertMessage, update.LSInsertMessage...)
	c.table.LSInsertAttachment = append(c.table.LSInsertAttachment, update.LSInsertAttachment...)
	c.table.LSInsertXmaAttachment = append(c.table.LSInsertXmaAttachment, update.LSInsertXmaAttachment...)
	c.table.LSInsertBlobAttachment = append(c.table.LSInsertBlobAttachment, update.LSInsertBlobAttachment...)
	c.table.LSInsertStickerAttachment = append(c.table.LSInsertStickerAttachment, update.LSInsertStickerAttachment...)
}

// Connect restores the stored session, verifies the server identity on the messages
// page and opens the socket. Inbox contents are discarded.
func (e *Engine) Connect(timeoutMs int64) (string, error) {
	if timeoutMs < 1000 || timeoutMs > 60000 {
		return "", errors.New("invalid timeout")
	}
	e.mu.Lock()
	if e.closed || e.login != nil || e.conn != nil {
		e.mu.Unlock()
		return "", errors.New("busy")
	}
	session, err := e.loadSession()
	if err != nil {
		e.mu.Unlock()
		return "", errors.New("no session")
	}
	ctx, cancel := context.WithCancel(e.ctx)
	conn := &connection{session: session, cancel: cancel}
	conn.client = newClient(session.Cookies)
	conn.client.MessengerLite.SetNativeSession(session.Native)
	conn.client.SetEventHandler(func(_ context.Context, event any) {
		switch typed := event.(type) {
		case *messagix.ConnectedEvent, *messagix.ReconnectedEvent:
			conn.connected.Store(true)
		case *messagix.TransientDisconnectEvent:
			conn.connected.Store(false)
		case *messagix.PermanentErrorEvent:
			conn.connected.Store(false)
			conn.failed.Store(true)
		case *table.LSTable:
			conn.merge(typed)
		}
		// Message contents stay in memory only and are never logged.
	})
	e.conn = conn
	e.connState = "connecting"
	e.mu.Unlock()

	fail := func(state string) (string, error) {
		e.Disconnect()
		e.mu.Lock()
		e.connState = state
		e.mu.Unlock()
		return e.Status(), nil
	}
	deadline, stop := context.WithTimeout(ctx, time.Duration(timeoutMs)*time.Millisecond)
	defer stop()
	user, inbox, err := conn.client.LoadMessagesPage(deadline)
	if err != nil {
		return fail("failed_initialization")
	}
	if user == nil || user.GetFBID() != session.FBID {
		return fail("failed_identity")
	}
	// Kept in memory only to list conversations and their recent messages; never logged.
	conn.tableMu.Lock()
	conn.table = inbox
	conn.tableMu.Unlock()
	if err = conn.client.Connect(ctx); err != nil {
		return fail("failed_socket")
	}
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	for !conn.connected.Load() {
		select {
		case <-deadline.Done():
			return fail("failed_timeout")
		case <-ticker.C:
			if conn.failed.Load() {
				return fail("failed_authentication")
			}
		}
	}
	e.mu.Lock()
	e.connState = "connected"
	e.mu.Unlock()
	return e.Status(), nil
}

func (e *Engine) Disconnect() {
	e.mu.Lock()
	conn := e.conn
	e.mu.Unlock()
	if conn == nil {
		return
	}
	conn.cancel()
	conn.client.Disconnect()
	e.mu.Lock()
	if e.conn == conn {
		e.conn = nil
		e.connState = "disconnected"
	}
	e.mu.Unlock()
}
