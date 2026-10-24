package walib

import (
	"context"

	"github.com/polymorfa/hypermeow/proto/waCommon"
	"github.com/polymorfa/hypermeow/proto/waWeb"
	"github.com/polymorfa/hypermeow/types"
	"github.com/polymorfa/hypermeow/types/events"
)

// historyReaction is one person's reaction on a history message. Reactor is a canonical JID, or
// "me"; Emoji "" never appears (removed reactions aren't sent).
type historyReaction struct {
	Reactor string `json:"reactor"`
	Emoji   string `json:"emoji"`
	TsMs    int64  `json:"ts"`
}

// handleReaction reports a reaction message (added, changed, or removed when its text is empty)
// and reports whether m was one.
func (c *Client) handleReaction(m *events.Message) bool {
	r := m.Message.GetReactionMessage()
	if r == nil {
		return false
	}
	reactor := "me"
	if !m.Info.IsFromMe {
		reactor = c.canonical(m.Info.Sender, nil).String()
	}
	ts := r.GetSenderTimestampMS()
	if ts <= 0 {
		ts = m.Info.Timestamp.UnixMilli()
	}
	c.l.OnReaction(c.canonical(m.Info.Chat, nil).String(), r.GetKey().GetID(), reactor, r.GetText(), ts)
	return true
}

// historyReactions are the reactions history sync stores on a message. Each carries the
// reactor's own message key: from us, from a group participant, or (1:1) from the chat.
func (c *Client) historyReactions(chat types.JID, web *waWeb.WebMessageInfo, mappings map[string]types.JID) []historyReaction {
	var out []historyReaction
	for _, r := range web.GetReactions() {
		if r.GetText() == "" {
			continue
		}
		out = append(out, historyReaction{Reactor: c.reactorOf(chat, r.GetKey(), mappings), Emoji: r.GetText(), TsMs: r.GetSenderTimestampMS()})
	}
	return out
}

func (c *Client) reactorOf(chat types.JID, key *waCommon.MessageKey, mappings map[string]types.JID) string {
	if key.GetFromMe() {
		return "me"
	}
	if p := key.GetParticipant(); p != "" {
		if jid, err := types.ParseJID(p); err == nil {
			if c.isOwn(jid) {
				return "me"
			}
			return c.canonical(jid, mappings).String()
		}
	}
	return c.canonical(chat, mappings).String()
}

// SendReaction reacts to the message targetID in chat with emoji ("" removes our reaction).
// targetSender is its author as stored here ("me" for our own messages).
func (c *Client) SendReaction(chat, targetID, targetSender, emoji string) error {
	jid, err := parseChat(chat)
	if err != nil {
		return err
	}
	sender := types.EmptyJID
	if targetSender != "me" && targetSender != "" {
		if sender, err = types.ParseJID(targetSender); err != nil {
			return err
		}
	} else if targetSender == "" && jid.Server != types.GroupServer {
		// Not said, in a 1:1 chat: theirs.
		sender = jid
	}
	_, err = c.wa.SendMessage(context.Background(), jid, c.wa.BuildReaction(jid, sender, targetID, emoji))
	return err
}
