package mobile

import (
	"bytes"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	lineapi "github.com/beeper/line/pkg/line"
)

func newMediaFetchEngine(t *testing.T, sources ...*mediaSource) *Engine {
	t.Helper()
	e, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(e.Close)
	e.client = e.newClient("synthetic-token")
	e.chats = []chat{{ID: "chat"}}
	for i, source := range sources {
		e.chats[0].History = append(e.chats[0].History, message{ID: fmt.Sprint(i), Media: "photo", source: source})
	}
	return e
}

func awaitMediaSignal[T any](t *testing.T, signals <-chan T) T {
	t.Helper()
	select {
	case value := <-signals:
		return value
	case <-time.After(3 * time.Second):
		t.Fatal("media operation did not complete")
		var empty T
		return empty
	}
}

func TestMediaDownloadsAreConcurrentBoundedAndUseResolvedSources(t *testing.T) {
	ciphertext, key, err := encryptFileData([]byte("media fixture"))
	if err != nil {
		t.Fatal(err)
	}
	const count = 7
	sources := make([]*mediaSource, count)
	for i := range sources {
		sources[i] = &mediaSource{publicPath: fmt.Sprintf("/media/%d", i), encrypted: true, keys: []string{key}}
	}
	e := newMediaFetchEngine(t, sources...)
	started := make(chan string, count)
	release := make(chan struct{})
	releaseAll := sync.OnceFunc(func() { close(release) })
	defer releaseAll()
	var active, peak atomic.Int32
	e.client.OBSClient.Transport = roundTripFunc(func(req *http.Request) (*http.Response, error) {
		now := active.Add(1)
		defer active.Add(-1)
		for previous := peak.Load(); now > previous; previous = peak.Load() {
			if peak.CompareAndSwap(previous, now) {
				break
			}
		}
		deadline, ok := req.Context().Deadline()
		if !ok || time.Until(deadline) > 12*time.Second {
			t.Error("media request lost its twelve-second deadline")
		}
		started <- strings.TrimPrefix(req.URL.Path, "/media/")
		select {
		case <-release:
			return imageRPC(string(ciphertext)), nil
		case <-req.Context().Done():
			return nil, req.Context().Err()
		}
	})
	results := make(chan error, count)
	for i := range count {
		go func() {
			data, err := e.Media("chat", fmt.Sprint(i))
			if err == nil && string(data) != "media fixture" {
				err = fmt.Errorf("resolved media key changed during download")
			}
			results <- err
		}()
	}
	first := awaitMediaSignal(t, started)
	awaitMediaSignal(t, started)
	awaitMediaSignal(t, started)
	select {
	case <-started:
		t.Fatal("more than three downloads ran concurrently")
	case <-time.After(100 * time.Millisecond):
	}
	updated := make(chan struct{})
	go func() {
		e.op.Lock()
		e.mu.Lock()
		for _, row := range e.chats[0].History {
			if row.ID == first {
				row.source.keys[0] = "changed-after-resolution"
			}
		}
		e.mu.Unlock()
		e.op.Unlock()
		close(updated)
	}()
	awaitMediaSignal(t, updated)
	releaseAll()
	for range count {
		if err := awaitMediaSignal(t, results); err != nil {
			t.Fatal(err)
		}
	}
	if peak.Load() != 3 {
		t.Fatalf("peak concurrent downloads = %d", peak.Load())
	}
}

func TestMediaFailuresAreCachedUntilHistoryReload(t *testing.T) {
	for _, failure := range []string{"download", "authentication"} {
		t.Run(failure, func(t *testing.T) {
			ciphertext, key, err := encryptFileData([]byte("media fixture"))
			if err != nil {
				t.Fatal(err)
			}
			ciphertext[0] ^= 1
			e := newMediaFetchEngine(t, &mediaSource{publicPath: "/media/photo", encrypted: true, keys: []string{key}})
			calls, recovered := 0, false
			e.client.OBSClient.Transport = roundTripFunc(func(req *http.Request) (*http.Response, error) {
				calls++
				if recovered {
					return imageRPC("recovered preview"), nil
				}
				if failure == "download" {
					response := imageRPC("private protocol failure")
					response.StatusCode = http.StatusNotFound
					return response, nil
				}
				return imageRPC(string(ciphertext)), nil
			})
			_, firstError := e.Media("chat", "0")
			if firstError == nil || calls != 1 || strings.Contains(firstError.Error(), "private") {
				t.Fatal("first media failure was missing or exposed protocol details")
			}
			for range 8 {
				if data, err := e.Media("chat", "0"); data != nil || err != firstError || calls != 1 {
					t.Fatal("cached failure downloaded again or changed its error")
				}
			}
			e.client.HTTPClient.Transport = roundTripFunc(func(req *http.Request) (*http.Response, error) {
				if !strings.HasSuffix(req.URL.Path, "/getRecentMessagesV2") {
					t.Fatal("unexpected history request")
				}
				rows := []*lineapi.Message{{ID: "0", ContentType: 1, ContentMetadata: map[string]string{"DOWNLOAD_URL": "/media/photo"}}}
				return imageRPC(encode(map[string]any{"code": 0, "data": rows})), nil
			})
			if err := e.LoadHistory("chat"); err != nil {
				t.Fatal(err)
			}
			recovered = true
			if data, err := e.Media("chat", "0"); err != nil || string(data) != "recovered preview" || calls != 2 {
				t.Fatalf("history reload did not permit a fresh download: %v", err)
			}
		})
	}
}

func TestMediaCloseCancelsActiveAndQueuedDownloads(t *testing.T) {
	sources := make([]*mediaSource, 7)
	for i := range sources {
		sources[i] = &mediaSource{publicPath: fmt.Sprintf("/media/%d", i)}
	}
	e := newMediaFetchEngine(t, sources...)
	started := make(chan struct{}, len(sources))
	e.client.OBSClient.Transport = roundTripFunc(func(req *http.Request) (*http.Response, error) {
		started <- struct{}{}
		<-req.Context().Done()
		return nil, req.Context().Err()
	})
	results := make(chan error, len(sources))
	for i := range sources {
		go func() {
			data, err := e.Media("chat", fmt.Sprint(i))
			if data != nil || err == nil {
				err = fmt.Errorf("closed owner returned media")
			}
			results <- err
		}()
	}
	for range 3 {
		awaitMediaSignal(t, started)
	}
	closed := make(chan struct{})
	go func() { e.Close(); close(closed) }()
	awaitMediaSignal(t, closed)
	for range sources {
		if err := awaitMediaSignal(t, results); err == nil || err.Error() == "closed owner returned media" {
			t.Fatal("owner cancellation did not fail a media call")
		}
	}
	if data, err := e.Media("chat", "0"); data != nil || err == nil {
		t.Fatal("closed engine admitted another download")
	}
}

func TestMediaOldFailureCannotPoisonReloadedHistory(t *testing.T) {
	e := newMediaFetchEngine(t, &mediaSource{publicPath: "/media/old"})
	started := make(chan struct{})
	release := make(chan struct{})
	releaseOld := sync.OnceFunc(func() { close(release) })
	defer releaseOld()
	e.client.OBSClient.Transport = roundTripFunc(func(req *http.Request) (*http.Response, error) {
		if req.URL.Path == "/media/old" {
			close(started)
			select {
			case <-release:
				response := imageRPC("")
				response.StatusCode = http.StatusNotFound
				return response, nil
			case <-req.Context().Done():
				return nil, req.Context().Err()
			}
		}
		return imageRPC("new preview"), nil
	})
	e.client.HTTPClient.Transport = roundTripFunc(func(req *http.Request) (*http.Response, error) {
		rows := []*lineapi.Message{{ID: "0", ContentType: 1, ContentMetadata: map[string]string{"DOWNLOAD_URL": "/media/new"}}}
		return imageRPC(encode(map[string]any{"code": 0, "data": rows})), nil
	})
	oldResult := make(chan error, 1)
	go func() { _, err := e.Media("chat", "0"); oldResult <- err }()
	awaitMediaSignal(t, started)
	reloaded := make(chan error, 1)
	go func() { reloaded <- e.LoadHistory("chat") }()
	if err := awaitMediaSignal(t, reloaded); err != nil {
		t.Fatal(err)
	}
	releaseOld()
	if err := awaitMediaSignal(t, oldResult); err == nil {
		t.Fatal("old request unexpectedly succeeded")
	}
	if data, err := e.Media("chat", "0"); err != nil || string(data) != "new preview" {
		t.Fatalf("old failure poisoned the reloaded source: %v", err)
	}
}

func TestMediaPrefersDeclaredPreviewAndSkipsLargeOriginals(t *testing.T) {
	for _, test := range []struct {
		name, fileSize, contentInfo string
		preview, missing, wantError bool
		wantOriginal                bool
	}{
		{name: "preview", fileSize: "6000000", preview: true},
		{name: "large fallback", fileSize: "3000000", preview: true, missing: true, wantError: true},
		{name: "content info size", contentInfo: `{"fileSize":3000000}`, preview: true, missing: true, wantError: true},
		{name: "no preview large original", fileSize: "3000000", wantError: true},
		{name: "small fallback", fileSize: "1000", preview: true, missing: true, wantOriginal: true},
		{name: "unknown size fallback", preview: true, missing: true, wantOriginal: true},
	} {
		t.Run(test.name, func(t *testing.T) {
			original, key, err := encryptFileData([]byte("original"))
			if err != nil {
				t.Fatal(err)
			}
			preview, err := encryptThumbnail([]byte("preview"), key)
			if err != nil {
				t.Fatal(err)
			}
			metadata := map[string]string{"OID": "photo", "FILE_SIZE": test.fileSize, "MEDIA_CONTENT_INFO": test.contentInfo}
			if test.preview {
				metadata["MEDIA_THUMB_INFO"] = `{"width":64,"height":48}`
			}
			_, kind, source := decodeMedia(&lineapi.Message{ID: "0", ContentType: 1, ContentMetadata: metadata}, encode(map[string]string{"keyMaterial": key}))
			if kind != "photo" || source == nil {
				t.Fatal("photo fixture did not decode")
			}
			e := newMediaFetchEngine(t, source)
			e.client.HTTPClient.Transport = roundTripFunc(func(req *http.Request) (*http.Response, error) {
				return imageRPC(`{"code":0,"data":"3600\u001esynthetic-obs-token"}`), nil
			})
			originalRequests := 0
			e.client.OBSClient.Transport = roundTripFunc(func(req *http.Request) (*http.Response, error) {
				isPreview := strings.Contains(req.URL.Path, "photo__ud-preview")
				if !isPreview {
					originalRequests++
				}
				if isPreview && test.missing {
					response := imageRPC("")
					response.StatusCode = http.StatusNotFound
					return response, nil
				}
				if strings.HasSuffix(req.URL.Path, "/object_info.obs") {
					return imageRPC(`{"status":"exist"}`), nil
				}
				if isPreview {
					return imageRPC(string(preview)), nil
				}
				return imageRPC(string(original)), nil
			})
			data, err := e.Media("chat", "0")
			if (err != nil) != test.wantError || (originalRequests > 0) != test.wantOriginal {
				t.Fatalf("media error=%v, original requests=%d", err, originalRequests)
			}
			if err == nil && !bytes.Equal(data, []byte("preview")) && !test.wantOriginal {
				t.Fatal("declared preview was not preferred")
			}
			if test.wantOriginal && string(data) != "original" {
				t.Fatal("small/unknown original fallback was lost")
			}
		})
	}
}
