package mobile

import (
	"context"
	"testing"

	lineapi "github.com/beeper/line/pkg/line"
)

type fakeEmailLogin struct {
	t          *testing.T
	initial    *lineapi.LoginResult
	final      *lineapi.LoginResult
	beforeWait func()
	waits      int
}

func (f *fakeEmailLogin) LoginContext(ctx context.Context, email, password, certificate string) (*lineapi.LoginResult, error) {
	if email != "test@example.invalid" || password != "synthetic-password" || certificate != "" {
		f.t.Fatal("unexpected credentials or retained certificate")
	}
	return f.initial, nil
}
func (f *fakeEmailLogin) WaitForLogin(verifier string, noE2EE bool) (*lineapi.LoginResult, error) {
	f.waits++
	if verifier != "synthetic-verifier" || noE2EE {
		f.t.Fatal("wrong challenge or unsealed login")
	}
	if f.beforeWait != nil {
		f.beforeWait()
	}
	return f.final, nil
}

func TestEmailPINChallengeWaitsForPhoneApproval(t *testing.T) {
	fake := &fakeEmailLogin{t: t, initial: &lineapi.LoginResult{Type: 3, Verifier: "synthetic-verifier", PinCode: "123456"}, final: &lineapi.LoginResult{AuthToken: "synthetic-token"}}
	displayed := false
	fake.beforeWait = func() {
		if !displayed {
			t.Fatal("wait started before PIN was exposed to phone UI")
		}
	}
	result, err := approveEmailLogin(context.Background(), fake, "test@example.invalid", "synthetic-password", func(status loginStatus) {
		if status.Phase != "pin" || status.PIN != "123456" {
			t.Fatal("PIN challenge not displayed")
		}
		displayed = true
	})
	if err != nil || result == nil || result.AuthToken != "synthetic-token" || fake.waits != 1 {
		t.Fatal("phone approval was not required")
	}
}

func TestUnsealedEmailLoginIsRejectedBeforeApproval(t *testing.T) {
	fake := &fakeEmailLogin{t: t, initial: &lineapi.LoginResult{NoE2EE: true, Verifier: "synthetic-verifier"}}
	result, err := approveEmailLogin(context.Background(), fake, "test@example.invalid", "synthetic-password", func(loginStatus) { t.Fatal("unsealed account offered approval") })
	if err == nil || result != nil || fake.waits != 0 {
		t.Fatal("unsealed login accepted")
	}
}

func TestCancelledEmailApprovalCannotAdmitToken(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	fake := &fakeEmailLogin{t: t, initial: &lineapi.LoginResult{Type: 3, Verifier: "synthetic-verifier", Pin: "123456"}}
	result, err := approveEmailLogin(ctx, fake, "test@example.invalid", "synthetic-password", func(loginStatus) { cancel() })
	if err == nil || result != nil || fake.waits != 0 {
		t.Fatal("cancelled login continued")
	}
}

func TestPhoneApprovalWithoutTokenIsNotAuthentication(t *testing.T) {
	fake := &fakeEmailLogin{t: t, initial: &lineapi.LoginResult{Type: 0, Verifier: "synthetic-verifier"}, final: &lineapi.LoginResult{}}
	result, err := approveEmailLogin(context.Background(), fake, "test@example.invalid", "synthetic-password", func(loginStatus) {})
	if err == nil || result != nil {
		t.Fatal("missing token accepted")
	}
}
