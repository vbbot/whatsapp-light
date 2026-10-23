package walib

import (
	"testing"

	"github.com/polymorfa/hypermeow/proto/waHistorySync"
	"github.com/polymorfa/hypermeow/types"
	"google.golang.org/protobuf/proto"
)

func TestBatchMappingsKeysByPhoneNumber(t *testing.T) {
	data := &waHistorySync.HistorySync{PhoneNumberToLidMappings: []*waHistorySync.PhoneNumberToLIDMapping{
		{PnJID: proto.String("15550001111@s.whatsapp.net"), LidJID: proto.String("99990001@lid")},
		{PnJID: proto.String("15550002222@s.whatsapp.net"), LidJID: proto.String("not-a-lid@s.whatsapp.net")},
	}}
	m := batchMappings(data)
	if got := m["15550001111"]; got.String() != "99990001@lid" {
		t.Errorf("mapping = %v", got)
	}
	if _, ok := m["15550002222"]; ok {
		t.Error("a mapping to a non-LID address should be ignored")
	}
}

func TestCanonicalPrefersTheBatchLIDAndPassesOthersThrough(t *testing.T) {
	c := &Client{} // these cases never reach the store
	extra := map[string]types.JID{"15550001111": types.NewJID("99990001", types.HiddenUserServer)}

	pn := types.NewADJID("15550001111", 0, 3) // a device address
	if got := c.canonical(pn, extra).String(); got != "99990001@lid" {
		t.Errorf("phone number with a known LID = %s", got)
	}
	group := types.NewJID("120363000000000000", types.GroupServer)
	if got := c.canonical(group, extra); got != group {
		t.Errorf("group = %s", got)
	}
	lid := types.NewADJID("99990001", 0, 2)
	lid.Server = types.HiddenUserServer
	if got := c.canonical(lid, extra).String(); got != "99990001@lid" {
		t.Errorf("LID device address = %s", got)
	}
}
