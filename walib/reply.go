package walib

import (
	"context"

	"github.com/polymorfa/hypermeow/proto/waE2E"
	"github.com/polymorfa/hypermeow/types"
	"google.golang.org/protobuf/proto"
)

// quote is the message a reply answers: its ID, its author (canonical JID, "me" for our own, or
// "" when not said), and the text of the snapshot WhatsApp sends with the reply.
type quote struct {
	ID, Sender, Text string
}

// contextInfo is where a message carries its reply info: text, and media with captions.
func contextInfo(msg *waE2E.Message) *waE2E.ContextInfo {
	switch {
	case msg.GetExtendedTextMessage() != nil:
		return msg.GetExtendedTextMessage().GetContextInfo()
	case msg.GetImageMessage() != nil:
		return msg.GetImageMessage().GetContextInfo()
	case msg.GetVideoMessage() != nil:
		return msg.GetVideoMessage().GetContextInfo()
	case msg.GetAudioMessage() != nil:
		return msg.GetAudioMessage().GetContextInfo()
	case msg.GetDocumentMessage() != nil:
		return msg.GetDocumentMessage().GetContextInfo()
	case msg.GetStickerMessage() != nil:
		return msg.GetStickerMessage().GetContextInfo()
	}
	return nil
}

// quoteOf returns what msg replies to, or a zero quote when it isn't a reply.
func (c *Client) quoteOf(msg *waE2E.Message, mappings map[string]types.JID) quote {
	ci := contextInfo(msg)
	if ci.GetStanzaID() == "" {
		return quote{}
	}
	q := quote{ID: ci.GetStanzaID(), Text: messageText(ci.GetQuotedMessage())}
	if p := ci.GetParticipant(); p != "" {
		if jid, err := types.ParseJID(p); err == nil {
			if c.isOwn(jid) {
				q.Sender = "me"
			} else {
				q.Sender = c.canonical(jid, mappings).String()
			}
		}
	}
	return q
}

// isOwn reports whether jid is this account (by phone number or LID).
func (c *Client) isOwn(jid types.JID) bool {
	u := jid.ToNonAD().User
	if id := c.wa.Store.ID; id != nil && id.User == u {
		return true
	}
	return !c.wa.Store.LID.IsEmpty() && c.wa.Store.LID.User == u
}

// SendReply sends text as a reply to the message quotedID in chat. quotedSender is its author as
// stored here (canonical JID, or "me"), quotedText what it said (the snapshot other devices show
// if they don't have the original). id is the ID to send it with, as for SendText. Returns the
// new message's ID.
func (c *Client) SendReply(chat, id, text, quotedID, quotedSender, quotedText string) (string, error) {
	jid, err := parseChat(chat)
	if err != nil {
		return "", err
	}
	ci := &waE2E.ContextInfo{
		StanzaID:      proto.String(quotedID),
		QuotedMessage: &waE2E.Message{Conversation: proto.String(quotedText)},
	}
	if p := c.quotedParticipant(jid, quotedSender); !p.IsEmpty() {
		ci.Participant = proto.String(p.String())
	}
	msg := &waE2E.Message{ExtendedTextMessage: &waE2E.ExtendedTextMessage{Text: proto.String(text), ContextInfo: ci}}
	resp, err := c.wa.SendMessage(context.Background(), jid, msg, withID(id))
	if err != nil {
		return "", err
	}
	return resp.ID, nil
}

// quotedParticipant is the quoted message's author as WhatsApp addresses it in chat: our own LID
// in LID-addressed chats (else our number) for "me", otherwise the stored JID.
func (c *Client) quotedParticipant(chat types.JID, sender string) types.JID {
	if sender != "me" {
		jid, err := types.ParseJID(sender)
		if err != nil {
			return types.EmptyJID
		}
		return jid
	}
	if lid := c.wa.Store.LID; !lid.IsEmpty() && (chat.Server == types.HiddenUserServer || chat.Server == types.GroupServer) {
		return lid.ToNonAD()
	}
	if id := c.wa.Store.ID; id != nil {
		return id.ToNonAD()
	}
	return types.EmptyJID
}
