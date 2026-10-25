package walib

import (
	"testing"

	"github.com/polymorfa/hypermeow/types"
)

func TestLinkPairNamesGroupAndCommunity(t *testing.T) {
	community := types.NewJID("111", types.GroupServer)
	group := types.NewJID("222", types.GroupServer)
	// Sent to the community: the change names the group.
	g, c, ok := linkPair(community, &types.GroupLinkChange{Type: types.GroupLinkChangeTypeSub, Group: types.GroupLinkTarget{JID: group}})
	if !ok || g != group || c != community {
		t.Errorf("sub change: %v %v %v", g, c, ok)
	}
	// Sent to the group: the change names the community.
	g, c, ok = linkPair(group, &types.GroupLinkChange{Type: types.GroupLinkChangeTypeParent, Group: types.GroupLinkTarget{JID: community}})
	if !ok || g != group || c != community {
		t.Errorf("parent change: %v %v %v", g, c, ok)
	}
	if _, _, ok = linkPair(group, &types.GroupLinkChange{Type: types.GroupLinkChangeTypeSibling, Group: types.GroupLinkTarget{JID: community}}); ok {
		t.Error("a sibling change isn't a link")
	}
	if _, _, ok = linkPair(group, &types.GroupLinkChange{Type: types.GroupLinkChangeTypeSub}); ok {
		t.Error("a change with no group isn't a link")
	}
}

func TestCommunityOfGroupInfo(t *testing.T) {
	parent := types.NewJID("111", types.GroupServer)
	info := &types.GroupInfo{GroupName: types.GroupName{Name: "News"}}
	info.LinkedParentJID = parent
	info.IsDefaultSubGroup = true
	if got := communityOf(info); got != (groupCommunity{Name: "News", Community: "111@g.us", Announcements: true}) {
		t.Errorf("subgroup: %+v", got)
	}
	info = &types.GroupInfo{GroupName: types.GroupName{Name: "Club"}}
	info.IsParent = true
	if got := communityOf(info); got != (groupCommunity{Name: "Club", IsCommunity: true}) {
		t.Errorf("community: %+v", got)
	}
	info = &types.GroupInfo{GroupName: types.GroupName{Name: "Solo"}}
	info.IsDefaultSubGroup = true
	if got := communityOf(info); got != (groupCommunity{Name: "Solo"}) {
		t.Errorf("plain group: %+v", got)
	}
}
