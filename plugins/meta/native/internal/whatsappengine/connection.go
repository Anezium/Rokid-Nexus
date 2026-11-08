package whatsappengine

import (
	"errors"
	"net"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/socket"
	"go.mau.fi/whatsmeow/store"
)

// Only an initial failed dial needs our retry: whatsmeow owns reconnection once
// the transport is established. Each open or explicit login query gets at most
// three attempts, without another service or a permanent retry loop.
func (w *WhatsApp) startStoredConnection() {
	w.startStoredConnectionWithDelays([]time.Duration{0, time.Second, 2 * time.Second})
}

func (w *WhatsApp) startStoredConnectionWithDelays(delays []time.Duration) {
	w.mu.Lock()
	if w.ctx.Err() != nil || w.account == "" || w.connectDone != nil ||
		(w.blocker != "" && w.blocker != "connect_failed") {
		w.mu.Unlock()
		return
	}
	cn := w.conn
	done := make(chan struct{})
	w.connectDone = done
	w.mu.Unlock()
	go func() {
		defer func() {
			w.mu.Lock()
			w.connectDone = nil
			close(done)
			w.mu.Unlock()
		}()
		for _, delay := range delays {
			timer := time.NewTimer(delay)
			select {
			case <-w.ctx.Done():
				timer.Stop()
				return
			case <-timer.C:
			}
			w.operation.Lock()
			w.mu.Lock()
			eligible := w.ctx.Err() == nil && w.conn == cn && w.account != "" &&
				(w.blocker == "" || w.blocker == "connect_failed")
			w.mu.Unlock()
			if !eligible || cn.IsConnected() {
				w.operation.Unlock()
				return
			}
			err := cn.ConnectContext(w.ctx)
			w.operation.Unlock()
			if err == nil || errors.Is(err, whatsmeow.ErrAlreadyConnected) || w.ctx.Err() != nil {
				return
			}
			retryable := retryableConnectionError(err)
			w.mu.Lock()
			if w.conn == cn && (w.blocker == "" || w.blocker == "connect_failed") {
				switch {
				case errors.Is(err, store.ErrDeviceDeleted):
					w.account, w.blocker = "", "logged_out"
				case retryable:
					w.blocker = "connect_failed"
				default:
					w.blocker = "connect_rejected"
				}
				w.notifyLocked()
			}
			w.mu.Unlock()
			if !retryable {
				return
			}
		}
	}()
}

func retryableConnectionError(err error) bool {
	var status socket.ErrWithStatusCode
	if errors.As(err, &status) {
		switch status.StatusCode {
		case 408, 500, 501, 502, 503, 504:
			return true
		default:
			return false
		}
	}
	var network net.Error
	return errors.As(err, &network) || errors.Is(err, socket.ErrDialFailed) ||
		errors.Is(err, whatsmeow.ErrHandshakeTimeout) || errors.Is(err, socket.ErrSocketClosed)
}
