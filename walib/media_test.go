package walib

import (
	"encoding/base64"
	"testing"

	"github.com/polymorfa/hypermeow/proto/waE2E"
	"google.golang.org/protobuf/proto"
)

func TestMediaRefKeepsPhotoDownloadInfoWithoutThumbnail(t *testing.T) {
	msg := &waE2E.Message{ImageMessage: &waE2E.ImageMessage{
		DirectPath:    proto.String("/v/t62/abc"),
		MediaKey:      []byte{1, 2, 3},
		JPEGThumbnail: make([]byte, 5000),
		Caption:       proto.String("hi"),
	}}
	ref := mediaRef(msg)
	raw, err := base64.StdEncoding.DecodeString(ref)
	if err != nil {
		t.Fatal(err)
	}
	img := &waE2E.ImageMessage{}
	if err := proto.Unmarshal(raw, img); err != nil {
		t.Fatal(err)
	}
	if img.GetDirectPath() != "/v/t62/abc" || len(img.GetMediaKey()) != 3 {
		t.Errorf("download info lost: %v", img)
	}
	if len(img.GetJPEGThumbnail()) != 0 || img.Caption != nil {
		t.Error("thumbnail and caption should be left out")
	}
	if msg.GetImageMessage().GetJPEGThumbnail() == nil {
		t.Error("original message must not be modified")
	}
	if mediaRef(&waE2E.Message{Conversation: proto.String("text")}) != "" {
		t.Error("text should have no ref")
	}
}

func TestMediaRefOfVideo(t *testing.T) {
	msg := &waE2E.Message{VideoMessage: &waE2E.VideoMessage{
		DirectPath:    proto.String("/v/t62/vid"),
		MediaKey:      []byte{4, 5},
		Seconds:       proto.Uint32(42),
		JPEGThumbnail: make([]byte, 5000),
		Caption:       proto.String("hi"),
	}}
	ref := mediaRef(msg)
	vid, err := videoOfRef(ref)
	if err != nil {
		t.Fatal(err)
	}
	if vid.GetDirectPath() != "/v/t62/vid" || len(vid.GetMediaKey()) != 2 {
		t.Errorf("download info lost: %v", vid)
	}
	if len(vid.GetJPEGThumbnail()) != 0 || vid.Caption != nil {
		t.Error("thumbnail and caption should be left out")
	}
	if got := VideoSeconds(ref); got != 42 {
		t.Errorf("VideoSeconds = %d, want 42", got)
	}
	if VideoSeconds("") != 0 || VideoSeconds("not base64!") != 0 {
		t.Error("unknown length should be 0")
	}
	if mediaRef(&waE2E.Message{VideoMessage: &waE2E.VideoMessage{}}) != "" {
		t.Error("a video with nowhere to download from should have no ref")
	}
}
