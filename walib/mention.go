package walib

import (
	"context"
	"strings"

	"github.com/polymorfa/hypermeow/proto/waE2E"
	"github.com/polymorfa/hypermeow/types"
	"google.golang.org/protobuf/proto"
)

// mentionsOf lists the people msg @mentions, comma-separated, as WhatsApp addresses them (the
// text carries "@" and each address's user part). Addresses are kept as sent, not made
// canonical: the text's token is that address's user. "" when it mentions no one.
func mentionsOf(msg *waE2E.Message) string {
	var out []string
	for _, m := range contextInfo(msg).GetMentionedJID() {
		if jid, err := types.ParseJID(m); err == nil && jid.User != "" && jid.Server != "" {
			out = append(out, jid.ToNonAD().String())
		}
	}
	return strings.Join(out, ",")
}

// withMentions is a text message saying text that @mentions the people in mentions
// (comma-separated addresses, whose user parts the text carries as "@<user>"), with reply info
// ci (nil for none). Plain text without either, as WhatsApp sends it.
func withMentions(text, mentions string, ci *waE2E.ContextInfo) *waE2E.Message {
	var jids []string
	for _, m := range strings.Split(mentions, ",") {
		if jid, err := types.ParseJID(strings.TrimSpace(m)); err == nil && jid.User != "" && jid.Server != "" {
			jids = append(jids, jid.ToNonAD().String())
		}
	}
	if len(jids) == 0 && ci == nil {
		return &waE2E.Message{Conversation: proto.String(text)}
	}
	if ci == nil {
		ci = &waE2E.ContextInfo{}
	}
	ci.MentionedJID = jids
	return &waE2E.Message{ExtendedTextMessage: &waE2E.ExtendedTextMessage{Text: proto.String(text), ContextInfo: ci}}
}

// GroupMembers lists group's members other than this account, comma-separated, each as the
// group addresses them (the address a mention of them uses).
func (c *Client) GroupMembers(group string) (string, error) {
	jid, err := types.ParseJID(group)
	if err != nil {
		return "", err
	}
	info, err := c.wa.GetGroupInfo(context.Background(), jid)
	if err != nil {
		return "", err
	}
	var out []string
	for _, p := range info.Participants {
		if !p.JID.IsEmpty() && !c.isOwn(p.JID) && !(p.LID.User != "" && c.isOwn(p.LID)) {
			out = append(out, p.JID.ToNonAD().String())
		}
	}
	return strings.Join(out, ","), nil
}
