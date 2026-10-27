package walib

import (
	"testing"

	"github.com/polymorfa/hypermeow/proto/waE2E"
	"google.golang.org/protobuf/proto"
)

func TestMentionsOfListsMentionedAddresses(t *testing.T) {
	ci := &waE2E.ContextInfo{MentionedJID: []string{"111@lid", "222:3@s.whatsapp.net", "not a jid@"}}
	text := &waE2E.Message{ExtendedTextMessage: &waE2E.ExtendedTextMessage{Text: proto.String("@111 @222 hi"), ContextInfo: ci}}
	if got := mentionsOf(text); got != "111@lid,222@s.whatsapp.net" {
		t.Errorf("got %q", got)
	}
	photo := &waE2E.Message{ImageMessage: &waE2E.ImageMessage{Caption: proto.String("@111"), ContextInfo: ci}}
	if got := mentionsOf(photo); got != "111@lid,222@s.whatsapp.net" {
		t.Errorf("photo: got %q", got)
	}
	if got := mentionsOf(&waE2E.Message{Conversation: proto.String("plain")}); got != "" {
		t.Errorf("plain: got %q", got)
	}
}
