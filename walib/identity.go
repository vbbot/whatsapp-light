package walib

import (
	"context"

	"github.com/polymorfa/hypermeow/proto/waHistorySync"
	"github.com/polymorfa/hypermeow/types"
)

// canonical is the one address a person's chat is kept under: their private ID (LID) when it's
// known, else their phone number. WhatsApp uses either for the same person, and storing both
// would split their conversation into two chats. Groups and other servers pass through.
// extra holds phone number → LID mappings that came with a history batch and may not be in
// the store yet (the library saves them in the background); nil elsewhere.
func (c *Client) canonical(j types.JID, extra map[string]types.JID) types.JID {
	j = j.ToNonAD()
	if j.Server != types.DefaultUserServer {
		return j
	}
	if lid, ok := extra[j.User]; ok {
		return lid
	}
	if lid, err := c.wa.Store.LIDs.GetLIDForPN(context.Background(), j); err == nil && !lid.IsEmpty() {
		return lid.ToNonAD()
	}
	return j
}

// canonicalString is canonical for an address given as a string; unparseable input is returned as is.
func (c *Client) canonicalString(s string, extra map[string]types.JID) string {
	j, err := types.ParseJID(s)
	if err != nil {
		return s
	}
	return c.canonical(j, extra).String()
}

// batchMappings collects a history batch's phone number → LID mappings, keyed by phone number.
func batchMappings(data *waHistorySync.HistorySync) map[string]types.JID {
	out := map[string]types.JID{}
	for _, m := range data.GetPhoneNumberToLidMappings() {
		pn, err1 := types.ParseJID(m.GetPnJID())
		lid, err2 := types.ParseJID(m.GetLidJID())
		if err1 == nil && err2 == nil && lid.Server == types.HiddenUserServer {
			out[pn.User] = lid.ToNonAD()
		}
	}
	return out
}
