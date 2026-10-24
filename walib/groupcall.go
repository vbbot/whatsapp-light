package walib

import (
	"strings"

	"github.com/polymorfa/hypermeow/types"
	"github.com/purpshell/meowcaller"
)

// groupMembers lists a group call's other participants (canonical JIDs, comma-separated), the
// call's creator first, and those of them already in the call. canon maps an address to the one
// the app's chats use; self is our own account, left out.
func groupMembers(state meowcaller.GroupCallState, creator, self types.JID, canon func(types.JID) types.JID) (members, joined string) {
	creator = canon(creator)
	self = canon(self)
	var all, in []string
	seen := map[types.JID]bool{self: true}
	add := func(j types.JID, connected bool) {
		if j.IsEmpty() || seen[j] {
			return
		}
		seen[j] = true
		all = append(all, j.String())
		if connected {
			in = append(in, j.String())
		}
	}
	creatorIn := false
	for _, p := range state.Participants {
		if canon(p.JID) == creator {
			creatorIn = p.State == "connected"
		}
	}
	add(creator, creatorIn)
	for _, p := range state.Participants {
		add(canon(p.JID), p.State == "connected")
	}
	return strings.Join(all, ","), strings.Join(in, ",")
}

// reportGroup tells the app a group call's group and members, now and as they change.
func (c *Client) reportGroup(call *meowcaller.Call) {
	creator := call.Peer()
	canon := func(j types.JID) types.JID { return c.canonical(j, nil) }
	report := func(s meowcaller.GroupCallState) {
		members, joined := groupMembers(s, creator, c.wa.Store.GetLID(), canon)
		group := ""
		if !s.GroupJID.IsEmpty() {
			group = s.GroupJID.String()
		}
		c.l.OnGroupCall(call.ID(), group, members, joined)
	}
	if s, ok := call.GroupState(); ok {
		report(s)
	}
	call.OnGroupState(report)
}

// receiveGroupVideo sends a group call's video to the renderer per participant (see
// VideoRenderer.ParticipantFrame) rather than all of it into one stream.
func (c *Client) receiveGroupVideo(call *meowcaller.Call) {
	id := call.ID()
	c.mu.Lock()
	c.groupVideo = id
	c.mu.Unlock()
	call.OnParticipantVideoFrame(func(f meowcaller.ParticipantVideoFrame) {
		c.mu.Lock()
		r := c.renderer
		c.mu.Unlock()
		if r == nil {
			return
		}
		r.ParticipantFrame(participantKey(f, c.canonical), max(f.Orientation, 0), f.AccessUnit)
	})
}

// participantKey names a video frame's sender: their canonical JID, else the device's, else
// the call's own participant ID.
func participantKey(f meowcaller.ParticipantVideoFrame, canonical func(types.JID, map[string]types.JID) types.JID) string {
	switch {
	case !f.Sender.IsEmpty():
		return canonical(f.Sender, nil).String()
	case !f.Device.IsEmpty():
		return canonical(f.Device, nil).String()
	default:
		return "participant:" + f.ParticipantID
	}
}
