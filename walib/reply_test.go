package walib

import (
	"testing"

	"github.com/polymorfa/hypermeow/proto/waE2E"
	"google.golang.org/protobuf/proto"
)

func TestContextInfoFromTextAndMedia(t *testing.T) {
	ci := &waE2E.ContextInfo{StanzaID: proto.String("ABC"), QuotedMessage: &waE2E.Message{Conversation: proto.String("see you at three")}}
	text := &waE2E.Message{ExtendedTextMessage: &waE2E.ExtendedTextMessage{Text: proto.String("ok"), ContextInfo: ci}}
	photo := &waE2E.Message{ImageMessage: &waE2E.ImageMessage{Caption: proto.String("this one"), ContextInfo: ci}}
	for name, m := range map[string]*waE2E.Message{"text": text, "photo": photo} {
		got := contextInfo(m)
		if got.GetStanzaID() != "ABC" || messageText(got.GetQuotedMessage()) != "see you at three" {
			t.Errorf("%s: got %q / %q", name, got.GetStanzaID(), messageText(got.GetQuotedMessage()))
		}
	}
	if contextInfo(&waE2E.Message{Conversation: proto.String("plain")}).GetStanzaID() != "" {
		t.Error("a plain message isn't a reply")
	}
	if messageText(nil) != "" {
		t.Error("no quoted message means no text")
	}
}
