package walib

import (
	"strings"

	"github.com/polymorfa/hypermeow/proto/waE2E"
	"github.com/polymorfa/hypermeow/types"
)

// mentionsOf lists the people msg @mentions, comma-separated, as WhatsApp addresses them (the
// text carries "@" and each address's user part). Addresses are kept as sent, not made
// canonical: the text's token is that address's user. "" when it mentions no one.
func mentionsOf(msg *waE2E.Message) string {
	var out []string
	for _, m := range contextInfo(msg).GetMentionedJID() {
		if jid, err := types.ParseJID(m); err == nil && jid.User != "" && jid.Server != "" {
			out = append(out, jid.ToNonAD().String())
		}
	}
	return strings.Join(out, ",")
}
