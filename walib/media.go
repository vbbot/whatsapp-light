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

// mediaRef is the download information of a photo or video message (where it is stored and
// its key), base64-encoded for the app to keep, or "" for anything else. The embedded thumbnail
// and caption are left out: the app already has them. The app knows which kind it is from the
// message text, and passes it to DownloadPhoto or DownloadVideo.
func mediaRef(msg *waE2E.Message) string {
	var m proto.Message
	switch {
	case msg.GetImageMessage() != nil:
		img := msg.GetImageMessage()
		if img.GetDirectPath() == "" && img.GetURL() == "" {
			return ""
		}
		img = proto.Clone(img).(*waE2E.ImageMessage)
		img.JPEGThumbnail = nil
		img.Caption = nil
		m = img
	case msg.GetVideoMessage() != nil:
		vid := msg.GetVideoMessage()
		if vid.GetDirectPath() == "" && vid.GetURL() == "" {
			return ""
		}
		vid = proto.Clone(vid).(*waE2E.VideoMessage)
		vid.JPEGThumbnail = nil
		vid.Caption = nil
		m = vid
	default:
		return ""
	}
	b, err := proto.Marshal(m)
	if err != nil {
		return ""
	}
	return base64.StdEncoding.EncodeToString(b)
}

func videoOfRef(ref string) (*waE2E.VideoMessage, error) {
	raw, err := base64.StdEncoding.DecodeString(ref)
	if err != nil {
		return nil, fmt.Errorf("bad media ref: %w", err)
	}
	vid := &waE2E.VideoMessage{}
	if err := proto.Unmarshal(raw, vid); err != nil {
		return nil, fmt.Errorf("bad media ref: %w", err)
	}
	return vid, nil
}

// VideoSeconds is the length of a video message from its mediaRef, or 0 if not known.
func VideoSeconds(ref string) int {
	vid, err := videoOfRef(ref)
	if err != nil {
		return 0
	}
	return int(vid.GetSeconds())
}

// ErrMediaExpired is returned by DownloadPhoto and DownloadVideo when WhatsApp's servers no longer have the file.
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
	if expired(err) {
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

// DownloadVideo fetches the video of message id to "<id>.mp4" in the media directory and returns
// its path. It streams to a file rather than holding the video in memory. ref is the message's
// mediaRef. Old videos may have expired from the server (ErrMediaExpired). Call from a
// background thread.
func (c *Client) DownloadVideo(id, ref string) (string, error) {
	vid, err := videoOfRef(ref)
	if err != nil {
		return "", err
	}
	c.mu.Lock()
	dir := c.mediaDir
	c.mu.Unlock()
	if dir == "" {
		return "", errors.New("no media directory")
	}
	path := filepath.Join(dir, safeFileName(id)+".mp4")
	part := path + ".part"
	f, err := os.OpenFile(part, os.O_RDWR|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()
	err = c.wa.DownloadToFile(ctx, vid, f)
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		os.Remove(part)
		if expired(err) {
			return "", ErrMediaExpired
		}
		return "", err
	}
	if err := os.Rename(part, path); err != nil {
		os.Remove(part)
		return "", err
	}
	return path, nil
}

// expired reports whether a download failed because the server no longer has the file.
func expired(err error) bool {
	return errors.Is(err, whatsmeow.ErrMediaDownloadFailedWith403) ||
		errors.Is(err, whatsmeow.ErrMediaDownloadFailedWith404) ||
		errors.Is(err, whatsmeow.ErrMediaDownloadFailedWith410)
}
