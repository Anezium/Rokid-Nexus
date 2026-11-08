package whatsappengine

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"sync"
	"time"

	_ "github.com/mattn/go-sqlite3"
)

var (
	ErrConflict = errors.New("id_conflict")
	ErrExpired  = errors.New("intent_expired")
	ErrCapacity = errors.New("capacity_reached")
	ErrIntent   = errors.New("invalid_intent")
)

const IntentTTL = 120 * time.Second
const ClockTolerance = 30 * time.Second

type Outcome struct {
	State       string `json:"state"`
	Reason      string `json:"reason,omitempty"`
	WAMessageID string `json:"waMessageId,omitempty"`
	ServerAt    int64  `json:"serverAt,omitempty"`
}

type Intent struct {
	Token     string `json:"intent"`
	ExpiresAt int64  `json:"expiresAt"`
}

type Outbox struct {
	db            *sql.DB
	mu            sync.Mutex
	now           func() time.Time
	Capacity      int
	RetainExpired bool
}

func randomID() string {
	var b [32]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic("entropy unavailable")
	}
	return hex.EncodeToString(b[:])
}

func OpenOutbox(path string) (*Outbox, error) {
	db, err := sql.Open("sqlite3", "file:"+path+"?_foreign_keys=on&_busy_timeout=5000&_journal_mode=WAL&_synchronous=FULL&_txlock=immediate")
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	_, err = db.Exec(`CREATE TABLE IF NOT EXISTS intents (
 token TEXT PRIMARY KEY, client TEXT NOT NULL, id TEXT NOT NULL, recipient TEXT NOT NULL,
 expires INTEGER NOT NULL, UNIQUE(client,id));
 CREATE TABLE IF NOT EXISTS sends (
 client TEXT NOT NULL, id TEXT NOT NULL, token TEXT NOT NULL, recipient TEXT NOT NULL,
 digest TEXT NOT NULL, state TEXT NOT NULL, waid TEXT NOT NULL, server_at INTEGER NOT NULL DEFAULT 0,
 expires INTEGER NOT NULL, PRIMARY KEY(client,id));
 UPDATE sends SET state='unknown' WHERE state='dispatching';`)
	if err != nil {
		db.Close()
		return nil, err
	}
	return &Outbox{db: db, now: time.Now, Capacity: 10000}, nil
}

func (o *Outbox) Close() error { return o.db.Close() }

// Pruning never turns an expired intent into new authority: tokens are random,
// persisted, and must still exist with a future expiry for every admission.
func (o *Outbox) CreateIntent(ctx context.Context, client, id, recipient string) (Intent, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	tx, err := o.db.BeginTx(ctx, nil)
	if err != nil {
		return Intent{}, err
	}
	defer tx.Rollback()
	now := o.now().Unix()
	if !o.RetainExpired {
		if _, err = tx.ExecContext(ctx, "DELETE FROM sends WHERE expires < ? AND state <> 'dispatching'", now-int64(ClockTolerance.Seconds())); err != nil {
			return Intent{}, err
		}
		if _, err = tx.ExecContext(ctx, "DELETE FROM intents WHERE expires < ?", now-int64(ClockTolerance.Seconds())); err != nil {
			return Intent{}, err
		}
	}
	var in Intent
	var to string
	err = tx.QueryRowContext(ctx, "SELECT token,expires,recipient FROM intents WHERE client=? AND id=?", client, id).Scan(&in.Token, &in.ExpiresAt, &to)
	if err == nil {
		if to != recipient {
			return Intent{}, ErrConflict
		}
		if in.ExpiresAt <= now {
			return Intent{}, ErrExpired
		}
		return in, tx.Commit()
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return Intent{}, err
	}
	var count int
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM intents").Scan(&count); err != nil {
		return Intent{}, err
	}
	if count >= o.Capacity {
		return Intent{}, ErrCapacity
	}
	in = Intent{randomID(), now + int64(IntentTTL.Seconds())}
	_, err = tx.ExecContext(ctx, "INSERT INTO intents VALUES(?,?,?,?,?)", in.Token, client, id, recipient, in.ExpiresAt)
	if err != nil {
		return Intent{}, err
	}
	return in, tx.Commit()
}

// Admit commits dispatch ownership before any network call. A crash from this
// point on is uncertain, including a crash before the first socket write.
func (o *Outbox) Admit(ctx context.Context, client, id, token, to, text, waid string) (Outcome, bool, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	tx, err := o.db.BeginTx(ctx, nil)
	if err != nil {
		return Outcome{}, false, err
	}
	defer tx.Rollback()
	digestBytes := sha256.Sum256([]byte(text))
	digest := hex.EncodeToString(digestBytes[:])
	var old Outcome
	var oldDigest, oldTo, oldToken string
	err = tx.QueryRowContext(ctx, "SELECT state,waid,server_at,digest,recipient,token FROM sends WHERE client=? AND id=?", client, id).Scan(&old.State, &old.WAMessageID, &old.ServerAt, &oldDigest, &oldTo, &oldToken)
	if err == nil {
		if oldDigest != digest || oldTo != to || oldToken != token {
			return Outcome{}, false, ErrConflict
		}
		if old.State == "dispatching" {
			old.State = "unknown"
		}
		return old, false, tx.Commit()
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return Outcome{}, false, err
	}
	var expires int64
	err = tx.QueryRowContext(ctx, "SELECT expires FROM intents WHERE token=? AND client=? AND id=? AND recipient=?", token, client, id, to).Scan(&expires)
	if errors.Is(err, sql.ErrNoRows) {
		return Outcome{}, false, ErrIntent
	}
	if err != nil {
		return Outcome{}, false, err
	}
	if expires <= o.now().Unix() {
		return Outcome{}, false, ErrExpired
	}
	_, err = tx.ExecContext(ctx, "INSERT INTO sends(client,id,token,recipient,digest,state,waid,expires) VALUES(?,?,?,?,?,'dispatching',?,?)", client, id, token, to, digest, waid, expires)
	if err != nil {
		return Outcome{}, false, err
	}
	err = tx.Commit()
	return Outcome{State: "unknown", WAMessageID: waid}, err == nil, err
}

func (o *Outbox) Finish(ctx context.Context, client, id string, out Outcome) error {
	_, err := o.db.ExecContext(ctx, "UPDATE sends SET state=?,server_at=? WHERE client=? AND id=? AND state='dispatching'", out.State, out.ServerAt, client, id)
	return err
}
func (o *Outbox) Lookup(ctx context.Context, client, id string) (Outcome, error) {
	var out Outcome
	err := o.db.QueryRowContext(ctx, "SELECT state,waid,server_at FROM sends WHERE client=? AND id=?", client, id).Scan(&out.State, &out.WAMessageID, &out.ServerAt)
	if out.State == "dispatching" {
		out.State = "unknown"
	}
	return out, err
}

func (o *Outbox) HasAdmission(ctx context.Context, id string) (bool, error) {
	var exists bool
	err := o.db.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM sends WHERE id=?)", id).Scan(&exists)
	return exists, err
}
