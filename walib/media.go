package walib

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"

	whatsmeow "github.com/polymorfa/hypermeow"
	"github.com/polymorfa/hypermeow/proto/waE2E"
	"google.golang.org/protobuf/proto"
)

// photoRef is the download information of a photo message (where it is stored and its key),
// base64-encoded for the app to keep, or "" for anything else. The embedded thumbnail is left
// out: the app already has it as a file.
func photoRef(msg *waE2E.Message) string {
	img := msg.GetImageMessage()
	if img == nil || img.GetDirectPath() == "" && img.GetURL() == "" {
		return ""
	}
	img = proto.Clone(img).(*waE2E.ImageMessage)
	img.JPEGThumbnail = nil
	img.Caption = nil
	b, err := proto.Marshal(img)
	if err != nil {
		return ""
	}
	return base64.StdEncoding.EncodeToString(b)
}

// ErrMediaExpired is returned by DownloadPhoto when WhatsApp's servers no longer have the file.
var ErrMediaExpired = errors.New("no longer on WhatsApp's servers")

// DownloadPhoto fetches the full photo of message id and returns the saved file's path. ref is
// the message's mediaRef. Old photos may have expired from the server (ErrMediaExpired). Call
// from a background thread.
func (c *Client) DownloadPhoto(id, ref string) (string, error) {
	raw, err := base64.StdEncoding.DecodeString(ref)
	if err != nil {
		return "", fmt.Errorf("bad media ref: %w", err)
	}
	img := &waE2E.ImageMessage{}
	if err := proto.Unmarshal(raw, img); err != nil {
		return "", fmt.Errorf("bad media ref: %w", err)
	}
	c.mu.Lock()
	dir := c.mediaDir
	c.mu.Unlock()
	if dir == "" {
		return "", errors.New("no media directory")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	data, err := c.wa.Download(ctx, img)
	if errors.Is(err, whatsmeow.ErrMediaDownloadFailedWith403) ||
		errors.Is(err, whatsmeow.ErrMediaDownloadFailedWith404) ||
		errors.Is(err, whatsmeow.ErrMediaDownloadFailedWith410) {
		return "", ErrMediaExpired
	}
	if err != nil {
		return "", err
	}
	path := filepath.Join(dir, safeFileName(id)+".jpg")
	if err := os.WriteFile(path, data, 0o600); err != nil {
		return "", err
	}
	return path, nil
}
