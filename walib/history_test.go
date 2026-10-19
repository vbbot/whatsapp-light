package walib

import (
	"testing"

	"github.com/polymorfa/hypermeow/proto/waSyncAction"
	"github.com/polymorfa/hypermeow/proto/waWeb"
	"google.golang.org/protobuf/proto"
)

func TestHistoryStatus(t *testing.T) {
	cases := map[waWeb.WebMessageInfo_Status]int{
		waWeb.WebMessageInfo_PENDING:      0,
		waWeb.WebMessageInfo_SERVER_ACK:   1,
		waWeb.WebMessageInfo_DELIVERY_ACK: 2,
		waWeb.WebMessageInfo_READ:         3,
		waWeb.WebMessageInfo_PLAYED:       3,
	}
	for in, want := range cases {
		if got := historyStatus(in); got != want {
			t.Errorf("historyStatus(%v) = %d, want %d", in, got, want)
		}
	}
}

func TestHistoryCallOf(t *testing.T) {
	incoming := &waSyncAction.CallLogRecord{
		CallID:         proto.String("c1"),
		CallCreatorJID: proto.String("111@s.whatsapp.net"),
		IsIncoming:     proto.Bool(true),
		StartTime:      proto.Int64(1_700_000_000),
		CallResult:     waSyncAction.CallLogRecord_CANCELLED.Enum(),
	}
	got, ok := historyCallOf(incoming)
	if !ok || got.Peer != "111@s.whatsapp.net" || got.Result != "missed" || got.StartedMs != 1_700_000_000_000 {
		t.Errorf("incoming: %+v %v", got, ok)
	}

	outgoing := &waSyncAction.CallLogRecord{
		CallID:       proto.String("c2"),
		IsVideo:      proto.Bool(true),
		StartTime:    proto.Int64(1_700_000_000_123),
		CallResult:   waSyncAction.CallLogRecord_CONNECTED.Enum(),
		Duration:     proto.Int64(42),
		Participants: []*waSyncAction.CallLogRecord_ParticipantInfo{{UserJID: proto.String("222@s.whatsapp.net")}},
	}
	got, ok = historyCallOf(outgoing)
	if !ok || got.Peer != "222@s.whatsapp.net" || got.Result != "connected" || !got.Video || got.StartedMs != 1_700_000_000_123 || got.DurationS != 42 {
		t.Errorf("outgoing: %+v %v", got, ok)
	}

	group := &waSyncAction.CallLogRecord{CallID: proto.String("c3"), GroupJID: proto.String("1-2@g.us"), CallCreatorJID: proto.String("1@s.whatsapp.net")}
	if _, ok := historyCallOf(group); ok {
		t.Error("group call should be skipped")
	}
}
