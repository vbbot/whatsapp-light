package walib

import (
	"context"
	"errors"

	"github.com/polymorfa/hypermeow/proto/waE2E"
	"github.com/polymorfa/hypermeow/types"
	"github.com/polymorfa/hypermeow/types/events"
	"google.golang.org/protobuf/proto"
)

// deletedText is the body of a message deleted for everyone (as the app stores it).
const deletedText = "[deleted]"

// historyEdit is a message edit or a delete-for-everyone found in history: it changes an
// earlier message rather than being one. Text is the new text, or "" for a delete.
type historyEdit struct {
	Chat   string `json:"chat"`
	ID     string `json:"id"`
	Text   string `json:"text"`
	TsMs   int64  `json:"ts"`
	FromMe bool   `json:"fromMe"`
	Sender string `json:"sender"`
}

// change is what an edit or delete-for-everyone does to message ID of chat; ok is false for
// anything else. A delete has Text "".
func (c *Client) change(m *events.Message, mappings map[string]types.JID) (historyEdit, bool) {
	pm := m.Message.GetProtocolMessage()
	if pm == nil || pm.GetKey().GetID() == "" {
		return historyEdit{}, false
	}
	e := historyEdit{
		Chat:   c.canonical(m.Info.Chat, mappings).String(),
		ID:     pm.GetKey().GetID(),
		TsMs:   m.Info.Timestamp.UnixMilli(),
		FromMe: m.Info.IsFromMe,
		Sender: c.canonical(m.Info.Sender, mappings).String(),
	}
	switch pm.GetType() {
	case waE2E.ProtocolMessage_REVOKE:
		return e, true
	case waE2E.ProtocolMessage_MESSAGE_EDIT:
		e.Text = messageText(pm.GetEditedMessage())
		if pm.GetTimestampMS() > 0 {
			e.TsMs = pm.GetTimestampMS()
		}
		return e, e.Text != ""
	}
	return historyEdit{}, false
}

// handleChange reports an edit (OnMessageEdited) or a delete-for-everyone (OnMessageRevoked);
// false if m is neither.
func (c *Client) handleChange(m *events.Message) bool {
	e, ok := c.change(m, nil)
	if !ok {
		return false
	}
	if e.Text == "" {
		c.l.OnMessageRevoked(e.Chat, e.ID, e.FromMe, e.Sender)
	} else {
		c.l.OnMessageEdited(e.Chat, e.ID, e.Text, e.TsMs, e.FromMe, e.Sender)
	}
	return true
}

// EditMessage replaces the text of one of our own text messages, for everyone in the chat.
func (c *Client) EditMessage(chat, id, text string) error {
	if text == "" {
		return errors.New("empty text")
	}
	jid, err := parseChat(chat)
	if err != nil {
		return err
	}
	_, err = c.wa.SendMessage(context.Background(), jid, c.wa.BuildEdit(jid, id, &waE2E.Message{Conversation: proto.String(text)}))
	return err
}
