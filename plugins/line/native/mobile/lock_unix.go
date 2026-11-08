//go:build !windows

package mobile

import (
	"os"
	"syscall"
)

func lockSession(file *os.File) error {
	return syscall.Flock(int(file.Fd()), syscall.LOCK_EX|syscall.LOCK_NB)
}
