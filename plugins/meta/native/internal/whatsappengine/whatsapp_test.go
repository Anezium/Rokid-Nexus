package whatsappengine

import (
	"context"
	"errors"
	"net/http"
	"net/url"
	"testing"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/store"
)

func TestPairingCompletionDoesNotCancelLoginConnection(t *testing.T) {
	owner, closeOwner := context.WithCancel(context.Background())
	defer closeOwner()
	client := whatsmeow.NewClient(&store.Device{}, nil)
	requests := make(chan context.Context, 1)
	release := make(chan struct{})
	client.SetProxy(func(request *http.Request) (*url.URL, error) {
		requests <- request.Context()
		<-release
		return nil, errors.New("test transport stopped before network access")
	})
	defer client.Disconnect()
	defer close(release)
	w := &WhatsApp{ctx: owner, client: client}
	if _, err := w.StartLink(context.Background(), "qr", ""); err != nil {
		t.Fatal(err)
	}
	defer w.cancel()
	var connection context.Context
	select {
	case connection = <-requests:
	case <-time.After(5 * time.Second):
		t.Fatal("connection did not reach the local transport boundary")
	}
	w.cancel()
	if connection.Err() != nil {
		t.Fatal("completing the pairing challenge canceled the login connection")
	}
	closeOwner()
	select {
	case <-connection.Done():
	case <-time.After(time.Second):
		t.Fatal("closing the owner did not cancel its connection")
	}
}
