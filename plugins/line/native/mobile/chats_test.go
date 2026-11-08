package mobile

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"
)

func TestInboxUsesActiveConversationRequestAndResolvesContactName(t *testing.T) {
	e, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer e.Close()
	e.client = e.newClient("synthetic-token")
	mid := "u0123456789abcdef0123456789abcdef"
	e.client.HTTPClient.Transport = roundTripFunc(func(req *http.Request) (*http.Response, error) {
		response := ""
		switch {
		case strings.HasSuffix(req.URL.Path, "getMessageBoxes"):
			var args []json.RawMessage
			if err := json.NewDecoder(req.Body).Decode(&args); err != nil {
				t.Fatal(err)
			}
			var options struct {
				ActiveOnly bool `json:"activeOnly"`
				Limit      int  `json:"messageBoxCountLimit"`
				History    int  `json:"lastMessagesPerMessageBoxCount"`
			}
			if len(args) != 2 || json.Unmarshal(args[0], &options) != nil || !options.ActiveOnly || options.Limit != 100 || options.History != 0 {
				t.Fatal("inbox request differs from the reference LINE client")
			}
			response = `{"code":0,"data":{"messageBoxes":[{"id":"` + mid + `"}],"hasNext":false}}`
		case strings.HasSuffix(req.URL.Path, "getContactsV2"):
			response = `{"code":0,"data":{"contacts":{"` + mid + `":{"contact":{"mid":"` + mid + `","displayName":"Test contact"}}}}}`
		default:
			t.Fatalf("unexpected request: %s", req.URL.Path)
		}
		return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(response))}, nil
	})
	if err = e.loadChats(); err != nil {
		t.Fatal(err)
	}
	if len(e.chats) != 1 || e.chats[0].Title != "Test contact" || e.chats[0].Group {
		t.Fatal("conversation name was not resolved")
	}
}

func TestEmptyRecentInboxFallsBackToUnblockedContacts(t *testing.T) {
	e, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer e.Close()
	e.client = e.newClient("synthetic-token")
	e.stored.Mid = "u00000000000000000000000000000000"
	allowed := "u11111111111111111111111111111111"
	blocked := "u22222222222222222222222222222222"
	e.client.HTTPClient.Transport = roundTripFunc(func(req *http.Request) (*http.Response, error) {
		response := ""
		switch {
		case strings.HasSuffix(req.URL.Path, "getMessageBoxes"):
			response = `{"code":0,"data":{"messageBoxes":[]}}`
		case strings.HasSuffix(req.URL.Path, "getAllContactIds"):
			response = `{"code":0,"data":["` + e.stored.Mid + `","` + allowed + `","` + blocked + `","` + allowed + `","invalid"]}`
		case strings.HasSuffix(req.URL.Path, "getBlockedContactIds"):
			response = `{"code":0,"data":["` + blocked + `"]}`
		case strings.HasSuffix(req.URL.Path, "getContactsV2"):
			var args []json.RawMessage
			if err := json.NewDecoder(req.Body).Decode(&args); err != nil {
				t.Fatal(err)
			}
			if !strings.Contains(string(args[0]), allowed) || strings.Contains(string(args[0]), blocked) {
				t.Fatal("blocked or wrong contact requested")
			}
			response = `{"code":0,"data":{"contacts":{"` + allowed + `":{"contact":{"displayName":"Test contact"}}}}}`
		default:
			t.Fatalf("unexpected request: %s", req.URL.Path)
		}
		return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(response))}, nil
	})
	if err = e.loadChats(); err != nil {
		t.Fatal(err)
	}
	if len(e.chats) != 1 || e.chats[0].ID != allowed || e.chats[0].Title != "Test contact" {
		t.Fatal("contact fallback was not filtered or resolved")
	}
}
