package messenger

import (
	"context"
	"errors"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"go.mau.fi/mautrix-meta/pkg/messagix/bloks"
	"go.mau.fi/mautrix-meta/pkg/messagix/cookies"
	"go.mau.fi/mautrix-meta/pkg/messagix/httpclient"
	"go.mau.fi/mautrix-meta/pkg/messagix/loginerrors"
	"go.mau.fi/mautrix-meta/pkg/messagix/types"
	"maunium.net/go/mautrix/bridgev2"
)

var codePattern = regexp.MustCompile(`^[0-9A-Za-z-]{4,32}$`)

const loginLimit = 8 * time.Minute

type loginRun struct {
	cancel context.CancelFunc
	codes  chan string
}

// classifyInput keeps provider strings inside the engine; only fixed labels leave it.
// It answers the credential form itself and never picks an account, a contact point,
// a backup code, a captcha or an unrecognized option.
func classifyInput(fields []bridgev2.LoginInputDataField, identifier, password string) (map[string]string, string) {
	if len(fields) == 2 && fields[0].ID == "username" && fields[1].ID == "password" {
		return map[string]string{"username": identifier, "password": password}, "running"
	}
	if len(fields) != 1 {
		return nil, "failed_unsupported_step"
	}
	field := fields[0]
	if field.ID == "password" && field.Type == bridgev2.LoginInputFieldTypePassword {
		return map[string]string{"password": password}, "running"
	}
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
	// Prefer approval in the already signed-in app.
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
	var checkpoint bloks.CheckpointError
	switch {
	case errors.As(err, &checkpoint), errors.Is(err, httpclient.ErrCheckpointRequired), errors.Is(err, httpclient.ErrChallengeRequired):
		return "failed_verification_in_instagram"
	case errors.Is(err, loginerrors.MissingCookies):
		return "failed_incomplete_session"
	case errors.Is(err, context.Canceled):
		return "cancelled"
	case errors.Is(err, context.DeadlineExceeded):
		return "failed_timeout"
	case errors.Is(err, httpclient.ErrRateLimited):
		return "failed_rate_limited"
	default:
		return "failed"
	}
}

// StartLogin begins a TEST login while the service page is visible. Credentials
// are used only by this run and never stored; only the resulting session is.
func (e *Engine) StartLogin(identifier, password string) error {
	identifier = strings.TrimSpace(identifier)
	if identifier == "" || len(identifier) > 254 || password == "" {
		return errors.New("invalid account or password")
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
	ctx, cancel := context.WithTimeout(e.ctx, loginLimit)
	run := &loginRun{cancel: cancel, codes: make(chan string, 1)}
	e.login = run
	e.loginState = "running"
	go e.runLogin(ctx, run, identifier, password)
	return nil
}

func (e *Engine) setLoginState(run *loginRun, state string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.login == run {
		e.loginState = state
	}
}

func (e *Engine) runLogin(ctx context.Context, run *loginRun, identifier, password string) {
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
	auth := newClient(&cookies.Cookies{Platform: types.MessengerLiteAndroid})
	step, jar, err := auth.MessengerLite.DoLoginSteps(ctx, nil)
	for steps := 0; err == nil && step != nil; steps++ {
		if steps >= 10 {
			final = "failed_too_many_steps"
			return
		}
		if step.Type == bridgev2.LoginStepTypeDisplayAndWait {
			if step.DisplayAndWaitParams == nil || step.DisplayAndWaitParams.Type != bridgev2.LoginDisplayTypeNothing {
				final = "failed_unsupported_step"
				return
			}
			e.setLoginState(run, "approval_required")
			step, jar, err = auth.MessengerLite.DoLoginSteps(ctx, nil)
			continue
		}
		if step.Type != bridgev2.LoginStepTypeUserInput || step.UserInputParams == nil {
			final = "failed_unsupported_step"
			return
		}
		input, label := classifyInput(step.UserInputParams.Fields, identifier, password)
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
			input = map[string]string{step.UserInputParams.Fields[0].ID: code}
		}
		e.setLoginState(run, "running")
		step, jar, err = auth.MessengerLite.DoLoginSteps(ctx, input)
	}
	password = ""
	if err != nil {
		final = loginFailure(err)
		return
	}
	native := auth.MessengerLite.GetNativeSession()
	if jar == nil || native == nil || native.AccessToken == "" || len(jar.GetMissingCookieNames()) > 0 {
		final = "failed_incomplete_session"
		return
	}
	// The messages page names the account the server actually signed in.
	verify := newClient(&cookies.Cookies{Platform: types.MessengerLiteAndroid})
	verify.GetCookies().UpdateValues(jar.GetAll())
	verify.MessengerLite.SetNativeSession(native)
	user, _, err := verify.LoadMessagesPage(ctx)
	if err != nil || user == nil || user.GetFBID() <= 0 || user.GetFBID() != jar.GetUserID() {
		final = "failed_account_mismatch"
		return
	}
	var previous string
	if readPrivate(filepath.Join(e.dir, bindingFile), &previous) == nil && previous != accountBinding(user.GetFBID()) {
		final = "failed_account_mismatch"
		return
	}
	session := storedSession{Cookies: verify.GetCookies(), Native: native, FBID: user.GetFBID(), Name: user.GetName()}
	if writePrivate(filepath.Join(e.dir, bindingFile), accountBinding(session.FBID)) != nil ||
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
