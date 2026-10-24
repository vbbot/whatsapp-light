package walib

import (
	"context"
	"time"

	"github.com/polymorfa/hypermeow/appstate"
	"github.com/polymorfa/hypermeow/proto/waSyncAction"
	"github.com/polymorfa/hypermeow/types"
	"github.com/polymorfa/hypermeow/types/events"
	"google.golang.org/protobuf/proto"
)

// DeleteForMe deletes one message from this account's devices only (WhatsApp's "Delete for me"),
// with its media. sender is the author in groups ("" for our own messages and 1:1 chats); tsMs
// the message's time.
func (c *Client) DeleteForMe(chat, id, sender string, fromMe bool, tsMs int64) error {
	jid, err := parseChat(chat)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	return c.wa.SendAppState(ctx, buildDeleteForMe(jid, id, sender, fromMe, tsMs))
}

// buildDeleteForMe is the app-state change for "Delete for me". whatsmeow parses these
// (events.DeleteForMe) but has no builder; this mirrors its BuildStar. The index is chat,
// message ID, from-me flag, and the author ("0" for our own messages and 1:1 chats).
func buildDeleteForMe(chat types.JID, id, sender string, fromMe bool, tsMs int64) appstate.PatchInfo {
	isFromMe, author := "0", "0"
	if fromMe {
		isFromMe = "1"
	} else if chat.Server == types.GroupServer && sender != "" {
		author = sender
	}
	return appstate.PatchInfo{
		Type: appstate.WAPatchRegularHigh,
		Mutations: []appstate.MutationInfo{{
			Index:   []string{appstate.IndexDeleteMessageForMe, chat.String(), id, isFromMe, author},
			Version: 3,
			Value: &waSyncAction.SyncActionValue{
				DeleteMessageForMeAction: &waSyncAction.DeleteMessageForMeAction{
					DeleteMedia:      proto.Bool(true),
					MessageTimestamp: proto.Int64(tsMs / 1000),
				},
			},
		}},
	}
}

// RevokeMessage deletes one of our own messages for everyone in the chat ("Delete for everyone").
func (c *Client) RevokeMessage(chat, id string) error {
	jid, err := parseChat(chat)
	if err != nil {
		return err
	}
	_, err = c.wa.SendMessage(context.Background(), jid, c.wa.BuildRevoke(jid, types.EmptyJID, id))
	return err
}

// handleDeleteForMe reports a message deleted for this account on another device.
func (c *Client) handleDeleteForMe(e *events.DeleteForMe) {
	c.l.OnMessageDeleted(c.canonical(e.ChatJID, nil).String(), e.MessageID)
}
