package whatsapp

import (
	"encoding/json"
	"path/filepath"
	"testing"
)

func TestUnlinkedEngineOpenCloseReopen(t *testing.T) {
	directory := filepath.Join(t.TempDir(), "private")
	e, err := Open(directory)
	if err != nil {
		t.Fatal(err)
	}
	raw, err := e.Status()
	if err != nil {
		e.Close()
		t.Fatal(err)
	}
	var status struct {
		Link      string `json:"link"`
		Connected bool   `json:"connected"`
		LoggedIn  bool   `json:"loggedIn"`
	}
	if err = json.Unmarshal([]byte(raw), &status); err != nil {
		e.Close()
		t.Fatal(err)
	}
	if status.Link != "unlinked" || status.Connected || status.LoggedIn {
		e.Close()
		t.Fatal("fresh engine must stay unlinked and disconnected")
	}
	e.Close()
	if _, err = e.Status(); err == nil {
		t.Fatal("closed engine accepted work")
	}
	e, err = Open(directory)
	if err != nil {
		t.Fatal(err)
	}
	defer e.Close()
}

func TestSessionLockRejectsSecondNativeEngine(t *testing.T) {
	directory := filepath.Join(t.TempDir(), "private")
	first, err := Open(directory)
	if err != nil {
		t.Fatal(err)
	}
	defer first.Close()
	second, err := Open(directory)
	if err == nil {
		second.Close()
		t.Fatal("session lock admitted two engines")
	}
}
