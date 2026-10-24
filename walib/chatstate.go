package walib

import (
	"context"
	"fmt"
	"math"
	"time"

	"github.com/polymorfa/hypermeow/appstate"
	"github.com/polymorfa/hypermeow/proto/waCommon"
	"github.com/polymorfa/hypermeow/proto/waSyncAction"
	"github.com/polymorfa/hypermeow/types"
	"github.com/polymorfa/hypermeow/types/events"
	"google.golang.org/protobuf/proto"
)

// Mute state crosses to the app as a single "muted until" value: 0 not muted, -1 muted until
// unmuted, otherwise a Unix time in milliseconds after which the chat is no longer muted.
const mutedForever = -1

func (c *Client) handleMute(e *events.Mute) {
	until := int64(0)
	if e.Action.GetMuted() {
		until = e.Action.GetMuteEndTimestamp()
		if until <= 0 {
			until = mutedForever
		}
	}
	c.l.OnChatMuted(c.canonical(e.JID, nil).String(), until)
}

func (c *Client) handleArchive(e *events.Archive) {
	c.l.OnChatArchived(c.canonical(e.JID, nil).String(), e.Action.GetArchived())
}

// handleDeleteChat reports a chat deleted (or cleared, keeping the chat) on another device:
// messages up to the end of the action's range go.
func (c *Client) handleDeleteChat(jid types.JID, r *waSyncAction.SyncActionMessageRange, at time.Time, keepChat bool, fullSync bool) {
	chat := c.canonical(jid, nil).String()
	before := deletedBefore(r, at)
	c.l.OnLog(fmt.Sprintf("chat deleted elsewhere: %s (as %s) up to %d (range %d, %d msgs, at %d) keepChat=%v fullSync=%v",
		jid, chat, before, r.GetLastMessageTimestamp(), len(r.GetMessages()), at.UnixMilli(), keepChat, fullSync))
	c.l.OnChatDeleted(chat, before, keepChat)
}

// deletedBefore is the Unix ms up to which messages are deleted: the range's last message time
// (seconds, so the whole second counts), else the time of the deletion.
func deletedBefore(r *waSyncAction.SyncActionMessageRange, at time.Time) int64 {
	if s := r.GetLastMessageTimestamp(); s > 0 {
		return s*1000 + 999
	}
	if at.IsZero() {
		return time.Now().UnixMilli()
	}
	return at.UnixMilli()
}

func (c *Client) handlePin(e *events.Pin) {
	at := int64(0)
	if e.Action.GetPinned() {
		at = e.Timestamp.UnixMilli()
		if at <= 0 {
			at = time.Now().UnixMilli()
		}
	}
	c.l.OnChatPinned(c.canonical(e.JID, nil).String(), at)
}

// historyPinnedAt converts a history-sync conversation's pin time (Unix seconds, 0 if not
// pinned) to Unix ms.
func historyPinnedAt(pinned uint32) int64 { return int64(pinned) * 1000 }

// SetChatPinned pins or unpins chat on every linked device.
func (c *Client) SetChatPinned(chat string, pinned bool) error {
	jid, err := types.ParseJID(chat)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	return c.wa.SendAppState(ctx, appstate.BuildPin(jid, pinned))
}

// historyMutedUntil converts a history-sync conversation's muteEndTime. It's unsigned, so
// "until unmuted" arrives as a far-future time; seconds and milliseconds both occur.
func historyMutedUntil(muteEndTime uint64) int64 {
	switch {
	case muteEndTime == 0:
		return 0
	case muteEndTime > math.MaxInt64/2:
		return mutedForever
	case muteEndTime < 1e12: // seconds
		muteEndTime *= 1000
	}
	if muteEndTime > uint64(time.Now().AddDate(100, 0, 0).UnixMilli()) {
		return mutedForever
	}
	return int64(muteEndTime)
}

// SetChatMuted mutes chat until it's unmuted, or unmutes it, on every linked device.
func (c *Client) SetChatMuted(chat string, muted bool) error {
	jid, err := types.ParseJID(chat)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	return c.wa.SendAppState(ctx, appstate.BuildMute(jid, muted, 0))
}

// SetChatArchived archives or unarchives chat on every linked device. The chat's newest message
// (ID, sender, Unix ms, ours or not) tells WhatsApp what the archive covers; pass an empty ID if
// the chat has none.
func (c *Client) SetChatArchived(chat string, archived bool, lastID, lastSender string, lastTsMs int64, lastFromMe bool) error {
	jid, err := types.ParseJID(chat)
	if err != nil {
		return err
	}
	ts, key := lastMessage(jid, lastID, lastSender, lastTsMs, lastFromMe)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	return c.wa.SendAppState(ctx, appstate.BuildArchive(jid, archived, ts, key))
}

// DeleteChat deletes chat, with its media, on every linked device and the main phone. The last
// message (as for SetChatArchived) marks how far the deletion goes: anything newer stays.
func (c *Client) DeleteChat(chat, lastID, lastSender string, lastTsMs int64, lastFromMe bool) error {
	jid, err := types.ParseJID(chat)
	if err != nil {
		return err
	}
	ts, key := lastMessage(jid, lastID, lastSender, lastTsMs, lastFromMe)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	return c.wa.SendAppState(ctx, appstate.BuildDeleteChat(jid, ts, key, true))
}

// lastMessage is the newest message's time and key, which app-state changes to a chat carry so
// other devices know which messages they cover. Empty lastID: no messages known (zero values).
func lastMessage(jid types.JID, lastID, lastSender string, lastTsMs int64, lastFromMe bool) (time.Time, *waCommon.MessageKey) {
	if lastID == "" {
		return time.Time{}, nil
	}
	key := &waCommon.MessageKey{RemoteJID: proto.String(jid.String()), FromMe: proto.Bool(lastFromMe), ID: proto.String(lastID)}
	if jid.Server == types.GroupServer && !lastFromMe && lastSender != "" {
		key.Participant = proto.String(lastSender)
	}
	return time.UnixMilli(lastTsMs), key
}
