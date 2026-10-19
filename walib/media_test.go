package walib

import (
	"encoding/base64"
	"testing"

	"github.com/polymorfa/hypermeow/proto/waE2E"
	"google.golang.org/protobuf/proto"
)

func TestPhotoRefKeepsDownloadInfoWithoutThumbnail(t *testing.T) {
	msg := &waE2E.Message{ImageMessage: &waE2E.ImageMessage{
		DirectPath:    proto.String("/v/t62/abc"),
		MediaKey:      []byte{1, 2, 3},
		JPEGThumbnail: make([]byte, 5000),
		Caption:       proto.String("hi"),
	}}
	ref := photoRef(msg)
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
	if photoRef(&waE2E.Message{Conversation: proto.String("text")}) != "" {
		t.Error("non-photo should have no ref")
	}
}
