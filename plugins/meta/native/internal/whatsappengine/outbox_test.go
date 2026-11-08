package whatsappengine

import (
	"context"
	"errors"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func newOutbox(t *testing.T) *Outbox {
	t.Helper()
	o, err := OpenOutbox(filepath.Join(t.TempDir(), "outbox.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { o.Close() })
	return o
}
func TestConcurrentAdmissionAndConflicts(t *testing.T) {
	o := newOutbox(t)
	ctx := context.Background()
	in, err := o.CreateIntent(ctx, "client", "tap", "person")
	if err != nil {
		t.Fatal(err)
	}
	var dispatches atomic.Int32
	var wg sync.WaitGroup
	for i := 0; i < 40; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, dispatch, err := o.Admit(ctx, "client", "tap", in.Token, "person", "hello", "wa-id")
			if err != nil {
				t.Error(err)
			}
			if dispatch {
				dispatches.Add(1)
			}
		}()
	}
	wg.Wait()
	if dispatches.Load() != 1 {
		t.Fatalf("dispatches=%d", dispatches.Load())
	}
	for _, change := range []struct{ to, text string }{{"other", "hello"}, {"person", "changed"}} {
		_, dispatch, err := o.Admit(ctx, "client", "tap", in.Token, change.to, change.text, "other-id")
		if dispatch || !errors.Is(err, ErrConflict) {
			t.Fatalf("conflict admitted: %v", err)
		}
	}
}
func TestRestartNeverRedispatches(t *testing.T) {
	path := filepath.Join(t.TempDir(), "outbox.sqlite")
	ctx := context.Background()
	o, err := OpenOutbox(path)
	if err != nil {
		t.Fatal(err)
	}
	in, err := o.CreateIntent(ctx, "client", "tap", "person")
	if err != nil {
		t.Fatal(err)
	}
	_, dispatch, err := o.Admit(ctx, "client", "tap", in.Token, "person", "hello", "wa-id")
	if err != nil || !dispatch {
		t.Fatal(err)
	}
	o.Close()
	o, err = OpenOutbox(path)
	if err != nil {
		t.Fatal(err)
	}
	defer o.Close()
	out, dispatch, err := o.Admit(ctx, "client", "tap", in.Token, "person", "hello", "wa-id")
	if err != nil || dispatch || out.State != "unknown" {
		t.Fatalf("restart result=%+v dispatch=%v err=%v", out, dispatch, err)
	}
}
func TestExpiryPruningCapacityAndClientBinding(t *testing.T) {
	o := newOutbox(t)
	ctx := context.Background()
	now := time.Now()
	o.now = func() time.Time { return now }
	o.Capacity = 1
	in, err := o.CreateIntent(ctx, "client", "tap", "person")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = o.CreateIntent(ctx, "client", "other-tap", "person"); !errors.Is(err, ErrCapacity) {
		t.Fatal(err)
	}
	if _, _, err = o.Admit(ctx, "other-client", "tap", in.Token, "person", "hello", "wa-id"); !errors.Is(err, ErrIntent) {
		t.Fatal(err)
	}
	now = now.Add(IntentTTL)
	if _, _, err = o.Admit(ctx, "client", "tap", in.Token, "person", "hello", "wa-id"); !errors.Is(err, ErrExpired) {
		t.Fatal(err)
	}
	now = now.Add(ClockTolerance + time.Second)
	if _, err = o.CreateIntent(ctx, "client", "new-tap", "person"); err != nil {
		t.Fatal(err)
	}
	if _, dispatch, err := o.Admit(ctx, "client", "tap", in.Token, "person", "hello", "wa-id"); dispatch || !errors.Is(err, ErrIntent) {
		t.Fatalf("pruned replay=%v %v", dispatch, err)
	}
}
func TestAckedReplayDoesNotDispatch(t *testing.T) {
	o := newOutbox(t)
	ctx := context.Background()
	in, err := o.CreateIntent(ctx, "client", "tap", "person")
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = o.Admit(ctx, "client", "tap", in.Token, "person", "hello", "wa-id"); err != nil {
		t.Fatal(err)
	}
	if err = o.Finish(ctx, "client", "tap", Outcome{State: "acked", ServerAt: 123}); err != nil {
		t.Fatal(err)
	}
	out, dispatch, err := o.Admit(ctx, "client", "tap", in.Token, "person", "hello", "different-wa-id")
	if err != nil || dispatch || out.State != "acked" || out.WAMessageID != "wa-id" || out.ServerAt != 123 {
		t.Fatalf("replay=%+v %v %v", out, dispatch, err)
	}
}

func TestNativeRetentionNeverReissuesAnExpiredTap(t *testing.T) {
	o := newOutbox(t)
	o.RetainExpired = true
	ctx := context.Background()
	now := time.Now()
	o.now = func() time.Time { return now }
	in, err := o.CreateIntent(ctx, "account", "same-tap", "person")
	if err != nil {
		t.Fatal(err)
	}
	now = now.Add(IntentTTL + ClockTolerance + time.Hour)
	if _, err = o.CreateIntent(ctx, "account", "same-tap", "person"); !errors.Is(err, ErrExpired) {
		t.Fatalf("expired tap reissued: %v", err)
	}
	if _, dispatch, err := o.Admit(ctx, "account", "same-tap", in.Token, "person", "hello", "wa-id"); dispatch || !errors.Is(err, ErrExpired) {
		t.Fatalf("expired tap dispatched: %v %v", dispatch, err)
	}
}
