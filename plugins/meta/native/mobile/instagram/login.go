package instagram

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"go.mau.fi/mautrix-meta/pkg/instameow"
	"go.mau.fi/mautrix-meta/pkg/messagix/httpclient"
	"go.mau.fi/mautrix-meta/pkg/messagix/types"
	"maunium.net/go/mautrix/bridgev2"
)

var (
	usernamePattern = regexp.MustCompile(`^[A-Za-z0-9._]{1,30}$`)
	codePattern     = regexp.MustCompile(`^[0-9A-Za-z-]{4,32}$`)
)

const loginLimit = 8 * time.Minute

type loginRun struct {
	cancel context.CancelFunc
	codes  chan string
	field  string
}

func accountBinding(username string) string {
	sum := sha256.Sum256([]byte(strings.ToLower(username)))
	return hex.EncodeToString(sum[:])
}

// classifyInput keeps provider strings inside the engine; only fixed labels leave it.
func classifyInput(field bridgev2.LoginInputDataField) (map[string]string, string) {
	if field.Type == bridgev2.LoginInputFieldType2FACode {
		switch field.ID {
		case "totp_code", "otp_code", "sms_code", "whatsapp_code", "verification_code":
			return nil, "code_required"
		default:
			return nil, "failed_unsupported_step"
		}
	}
	if field.Type != bridgev2.LoginInputFieldTypeSelect || field.ID != "mfatype" {
		return nil, "failed_unsupported_step"
	}
	// Prefer approval in the already signed-in app. Never pick an account,
	// contact point, backup code, passkey, or an unrecognized option.
	for _, wanted := range []string{"approval", "authenticator", "sms", "whatsapp", "email"} {
		for _, option := range field.Options {
			normalized := strings.ToLower(strings.Join(strings.Fields(option), " "))
			kind := ""
			switch normalized {
			case "approve from another device", "approve on another device", "notification on another device", "check your notifications on another device", "notification", "another device":
				kind = "approval"
			case "authentication app", "authenticator app":
				kind = "authenticator"
			case "text message", "text message (sms)", "sms":
				kind = "sms"
			case "whatsapp":
				kind = "whatsapp"
			case "email", "email code":
				kind = "email"
			}
			if kind == wanted {
				return map[string]string{field.ID: option}, "running"
			}
		}
	}
	return nil, "failed_unsupported_step"
}

func loginFailure(err error) string {
	switch {
	case errors.Is(err, instameow.ErrInstagramCAAUnsafeAccountStep):
		return "failed_account_mismatch"
	case errors.Is(err, context.Canceled):
		return "cancelled"
	case errors.Is(err, context.DeadlineExceeded):
		return "failed_timeout"
	case errors.Is(err, httpclient.ErrRateLimited):
		return "failed_rate_limited"
	case errors.Is(err, httpclient.ErrChallengeRequired), errors.Is(err, httpclient.ErrCheckpointRequired):
		return "failed_verification_in_instagram"
	default:
		return "failed"
	}
}

// StartLogin begins a TEST login in the background of the visible settings screen.
// Credentials are used once and never stored; only the resulting native session is.
func (e *Engine) StartLogin(username, password string) error {
	username = strings.TrimPrefix(strings.TrimSpace(username), "@")
	if !usernamePattern.MatchString(username) || password == "" {
		return errors.New("invalid username or password")
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.closed {
		return errors.New("closed")
	}
	if e.login != nil || e.conn != nil {
		return errors.New("busy")
	}
	if e.hasSession() {
		return errors.New("a session already exists")
	}
	var previous string
	if err := readPrivate(filepath.Join(e.dir, bindingFile), &previous); err == nil && previous != accountBinding(username) {
		return errors.New("a different account was used here before")
	}
	ctx, cancel := context.WithTimeout(e.ctx, loginLimit)
	run := &loginRun{cancel: cancel, codes: make(chan string, 1)}
	e.login = run
	e.loginState = "running"
	go e.runLogin(ctx, run, username, password)
	return nil
}

func (e *Engine) setLoginState(run *loginRun, state string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.login == run {
		e.loginState = state
	}
}

func (e *Engine) runLogin(ctx context.Context, run *loginRun, username, password string) {
	final := "failed"
	defer func() {
		if recover() != nil {
			final = "failed"
		}
		run.cancel()
		e.mu.Lock()
		if e.login == run {
			e.login = nil
			e.loginState = final
		}
		e.mu.Unlock()
	}()
	params := e.clientParams(nil)
	devicePath := filepath.Join(e.dir, installationFile)
	var device types.InstagramLoginDevice
	if readPrivate(devicePath, &device) == nil {
		params.MobileLoginDevice = &device
	}
	params.SaveMobileLoginDevice = func(_ context.Context, device types.InstagramLoginDevice) error {
		return writePrivate(devicePath, device)
	}
	client := instameow.NewClient(params)
	defer client.Disconnect()
	step, err := client.DoInstagramCAALoginStepsExactAccount(ctx, map[string]string{"username": username, "password": password}, username, "")
	password = ""
	for steps := 0; err == nil && step != nil; steps++ {
		if steps >= 8 {
			final = "failed_too_many_steps"
			return
		}
		if step.Type == bridgev2.LoginStepTypeDisplayAndWait {
			if step.DisplayAndWaitParams == nil || step.DisplayAndWaitParams.Type != "nothing" || !strings.HasSuffix(step.StepID, ".afad_wait") {
				final = "failed_unsupported_step"
				return
			}
			e.setLoginState(run, "approval_required")
			step, err = client.DoInstagramCAALoginStepsExactAccount(ctx, map[string]string{}, username, "")
			continue
		}
		if step.Type != bridgev2.LoginStepTypeUserInput || step.UserInputParams == nil || len(step.UserInputParams.Fields) != 1 {
			final = "failed_unsupported_step"
			return
		}
		field := step.UserInputParams.Fields[0]
		input, label := classifyInput(field)
		if input == nil && label != "code_required" {
			final = label
			return
		}
		if input == nil {
			e.setLoginState(run, "code_required")
			var code string
			select {
			case code = <-run.codes:
			case <-ctx.Done():
				err = ctx.Err()
				continue
			}
			input = map[string]string{field.ID: code}
			e.setLoginState(run, "running")
		}
		step, err = client.DoInstagramCAALoginStepsExactAccount(ctx, input, username, "")
	}
	if err != nil {
		final = loginFailure(err)
		return
	}
	session := client.GetInstagramNativeSession()
	if session == nil || session.Authorization == "" || session.UserID == "" || !strings.EqualFold(session.Username, username) {
		final = "failed_account_mismatch"
		return
	}
	if writePrivate(filepath.Join(e.dir, bindingFile), accountBinding(username)) != nil ||
		writePrivate(filepath.Join(e.dir, sessionFile), session) != nil {
		final = "failed_storage"
		return
	}
	final = "authenticated"
}

// SubmitCode hands one owner-typed verification code to the waiting login.
func (e *Engine) SubmitCode(code string) error {
	code = strings.TrimSpace(code)
	if !codePattern.MatchString(code) {
		return errors.New("invalid code")
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.login == nil || e.loginState != "code_required" {
		return errors.New("no code requested")
	}
	select {
	case e.login.codes <- code:
		e.loginState = "running"
		return nil
	default:
		return errors.New("code already submitted")
	}
}

func (e *Engine) AbortLogin() {
	e.mu.Lock()
	run := e.login
	e.mu.Unlock()
	if run != nil {
		run.cancel()
	}
}
