package walib

import (
	"context"
	"math"
	"time"

	"github.com/polymorfa/hypermeow/appstate"
	"github.com/polymorfa/hypermeow/proto/waCommon"
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
	var key *waCommon.MessageKey
	var ts time.Time
	if lastID != "" {
		key = &waCommon.MessageKey{RemoteJID: proto.String(jid.String()), FromMe: proto.Bool(lastFromMe), ID: proto.String(lastID)}
		if jid.Server == types.GroupServer && !lastFromMe && lastSender != "" {
			key.Participant = proto.String(lastSender)
		}
		ts = time.UnixMilli(lastTsMs)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	return c.wa.SendAppState(ctx, appstate.BuildArchive(jid, archived, ts, key))
}
