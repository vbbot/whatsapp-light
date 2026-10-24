package walib

import (
	"testing"

	"github.com/polymorfa/hypermeow/types"
	"github.com/purpshell/meowcaller"
)

func TestGroupMembersPutsTheCallerFirstAndLeavesUsOut(t *testing.T) {
	self := types.NewJID("100", types.HiddenUserServer)
	caller := types.NewJID("200", types.HiddenUserServer)
	other := types.NewJID("300", types.HiddenUserServer)
	ringing := types.NewJID("400", types.HiddenUserServer)
	state := meowcaller.GroupCallState{Participants: []meowcaller.GroupCallParticipant{
		{JID: other, State: "connected"},
		{JID: self, State: "outgoing"},
		{JID: ringing, State: "outgoing"},
		{JID: types.JID{User: "200", Server: types.HiddenUserServer, Device: 3}, State: "connected"},
	}}
	canon := func(j types.JID) types.JID { return j.ToNonAD() }
	members, joined := groupMembers(state, caller, types.JID{User: "100", Server: types.HiddenUserServer, Device: 4}, canon)
	if members != "200@lid,300@lid,400@lid" {
		t.Fatalf("members = %q", members)
	}
	if joined != "200@lid,300@lid" {
		t.Fatalf("joined = %q", joined)
	}
}
