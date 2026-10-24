package walib

import (
	"testing"

	"github.com/polymorfa/hypermeow/types"
)

func TestBuildDeleteForMeIndex(t *testing.T) {
	dm := types.NewJID("123", types.HiddenUserServer)
	group := types.NewJID("456", types.GroupServer)
	cases := []struct {
		chat   types.JID
		sender string
		fromMe bool
		want   []string
	}{
		{dm, "123@lid", false, []string{"deleteMessageForMe", "123@lid", "ID1", "0", "0"}},
		{dm, "", true, []string{"deleteMessageForMe", "123@lid", "ID1", "1", "0"}},
		{group, "789@lid", false, []string{"deleteMessageForMe", "456@g.us", "ID1", "0", "789@lid"}},
		{group, "", true, []string{"deleteMessageForMe", "456@g.us", "ID1", "1", "0"}},
	}
	for _, tc := range cases {
		p := buildDeleteForMe(tc.chat, "ID1", tc.sender, tc.fromMe, 5_000)
		got := p.Mutations[0].Index
		if len(got) != len(tc.want) {
			t.Fatalf("got %v", got)
		}
		for i := range got {
			if got[i] != tc.want[i] {
				t.Errorf("index %v, want %v", got, tc.want)
				break
			}
		}
		if ts := p.Mutations[0].Value.GetDeleteMessageForMeAction().GetMessageTimestamp(); ts != 5 {
			t.Errorf("timestamp %d, want 5 (seconds)", ts)
		}
	}
}
