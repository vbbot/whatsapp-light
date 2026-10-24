package walib

import (
	"testing"
	"time"

	"github.com/polymorfa/hypermeow/proto/waCommon"
	"github.com/polymorfa/hypermeow/proto/waE2E"
	"github.com/polymorfa/hypermeow/types"
	"github.com/polymorfa/hypermeow/types/events"
	"google.golang.org/protobuf/proto"
)

func protocolEvent(pm *waE2E.ProtocolMessage) *events.Message {
	chat := types.NewJID("123", types.HiddenUserServer)
	return &events.Message{
		Info: types.MessageInfo{
			MessageSource: types.MessageSource{Chat: chat, Sender: chat},
			Timestamp:     time.UnixMilli(5_000),
		},
		Message: &waE2E.Message{ProtocolMessage: pm},
	}
}

func TestChangeReadsEditsAndRevokes(t *testing.T) {
	c := &Client{} // LID chats never reach the store
	edit := protocolEvent(&waE2E.ProtocolMessage{
		Type:          waE2E.ProtocolMessage_MESSAGE_EDIT.Enum(),
		Key:           &waCommon.MessageKey{ID: proto.String("orig")},
		EditedMessage: &waE2E.Message{Conversation: proto.String("fixed")},
		TimestampMS:   proto.Int64(9_000),
	})
	e, ok := c.change(edit, nil)
	if !ok || e.ID != "orig" || e.Text != "fixed" || e.TsMs != 9_000 || e.Chat != "123@lid" {
		t.Errorf("edit = %+v, %v", e, ok)
	}

	revoke := protocolEvent(&waE2E.ProtocolMessage{
		Type: waE2E.ProtocolMessage_REVOKE.Enum(),
		Key:  &waCommon.MessageKey{ID: proto.String("gone")},
	})
	e, ok = c.change(revoke, nil)
	if !ok || e.ID != "gone" || e.Text != "" || e.TsMs != 5_000 {
		t.Errorf("revoke = %+v, %v", e, ok)
	}

	other := protocolEvent(&waE2E.ProtocolMessage{Type: waE2E.ProtocolMessage_EPHEMERAL_SETTING.Enum(), Key: &waCommon.MessageKey{ID: proto.String("x")}})
	if _, ok := c.change(other, nil); ok {
		t.Error("other protocol messages are not changes")
	}
	if _, ok := c.change(&events.Message{Message: &waE2E.Message{Conversation: proto.String("hi")}}, nil); ok {
		t.Error("a plain message is not a change")
	}
}
