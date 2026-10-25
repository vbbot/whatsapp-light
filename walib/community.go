package walib

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/polymorfa/hypermeow/types"
	"github.com/polymorfa/hypermeow/types/events"
)

// groupCommunity is what GroupCommunity returns, as JSON.
type groupCommunity struct {
	// Name is the group's name.
	Name string `json:"name"`
	// Community is the community the group belongs to, or "".
	Community string `json:"community"`
	// IsCommunity: the group is itself a community (its parent group).
	IsCommunity bool `json:"isCommunity"`
	// Announcements: the group is its community's announcements group.
	Announcements bool `json:"announcements"`
}

// GroupCommunity looks up group (an @g.us JID) on the server: its name, and whether it is a
// community or belongs to one. For groups history sync didn't describe (joined later). Returns
// JSON; see groupCommunity. Call from a background thread.
func (c *Client) GroupCommunity(group string) (string, error) {
	jid, err := types.ParseJID(group)
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	info, err := c.wa.GetGroupInfo(ctx, jid)
	if err != nil {
		return "", fmt.Errorf("group info: %w", err)
	}
	out, err := json.Marshal(communityOf(info))
	if err != nil {
		return "", err
	}
	return string(out), nil
}

func communityOf(info *types.GroupInfo) groupCommunity {
	g := groupCommunity{Name: info.Name, IsCommunity: info.IsParent}
	if !info.LinkedParentJID.IsEmpty() {
		g.Community = info.LinkedParentJID.String()
		g.Announcements = info.IsDefaultSubGroup
	}
	return g
}

// handleGroupLink reports a group added to or removed from a community. The event comes either
// to the community (the change names the group) or to the group (it names the community).
func (c *Client) handleGroupLink(e *events.GroupInfo) {
	for _, ch := range []struct {
		change *types.GroupLinkChange
		linked bool
	}{{e.Link, true}, {e.Unlink, false}} {
		if ch.change == nil {
			continue
		}
		group, community, ok := linkPair(e.JID, ch.change)
		if !ok {
			continue
		}
		if !ch.linked {
			community = types.EmptyJID
		}
		// Only a change naming the group says what it's called and whether it's the announcements group.
		name, announcements := "", false
		if ch.change.Type == types.GroupLinkChangeTypeSub {
			name, announcements = ch.change.Group.Name, ch.change.Group.IsDefaultSubGroup
		}
		c.l.OnCommunityLink(group.String(), jidString(community), announcements, name)
	}
}

// linkPair names the group and community of a link change sent to subject.
func linkPair(subject types.JID, change *types.GroupLinkChange) (group, community types.JID, ok bool) {
	switch change.Type {
	case types.GroupLinkChangeTypeSub:
		return change.Group.JID, subject, !change.Group.JID.IsEmpty()
	case types.GroupLinkChangeTypeParent:
		return subject, change.Group.JID, !change.Group.JID.IsEmpty()
	}
	return types.EmptyJID, types.EmptyJID, false
}

func jidString(j types.JID) string {
	if j.IsEmpty() {
		return ""
	}
	return j.String()
}
