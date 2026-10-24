package walib

import (
	"math"
	"testing"
	"time"

	"github.com/polymorfa/hypermeow/proto/waCommon"
	"github.com/polymorfa/hypermeow/proto/waSyncAction"
	"google.golang.org/protobuf/proto"
)

func TestHistoryMutedUntil(t *testing.T) {
	soon := time.Now().Add(8 * time.Hour)
	cases := []struct {
		in   uint64
		want int64
	}{
		{0, 0},
		{uint64(soon.Unix()), soon.Unix() * 1000},          // seconds
		{uint64(soon.UnixMilli()), soon.UnixMilli()},       // milliseconds
		{math.MaxUint64, mutedForever},                     // "-1" read as unsigned
		{uint64(time.Now().AddDate(500, 0, 0).Unix()), -1}, // far future means until unmuted
	}
	for _, c := range cases {
		if got := historyMutedUntil(c.in); got != c.want {
			t.Errorf("historyMutedUntil(%d) = %d, want %d", c.in, got, c.want)
		}
	}
}

func TestHistoryPinnedAt(t *testing.T) {
	if historyPinnedAt(0) != 0 || historyPinnedAt(1_700_000_000) != 1_700_000_000_000 {
		t.Error("pin time should be seconds converted to ms, 0 for not pinned")
	}
}

func TestDeletedBefore(t *testing.T) {
	at := time.UnixMilli(5_000_000)
	r := &waSyncAction.SyncActionMessageRange{LastMessageTimestamp: proto.Int64(1_000)}
	if got := deletedBefore(r, at); got != 1_000_999 {
		t.Errorf("range end: got %d, want 1000999 (the whole last second)", got)
	}
	if got := deletedBefore(nil, at); got != 5_000_000 {
		t.Errorf("no range: got %d, want the deletion time", got)
	}
}

func TestRangeIDs(t *testing.T) {
	r := &waSyncAction.SyncActionMessageRange{Messages: []*waSyncAction.SyncActionMessage{
		{Key: &waCommon.MessageKey{ID: proto.String("A1")}},
		{Key: &waCommon.MessageKey{}},
	}}
	if got := rangeIDs(r); len(got) != 1 || got[0] != "A1" {
		t.Errorf("got %v, want [A1]", got)
	}
	if rangeIDs(nil) != nil {
		t.Error("no range, no IDs")
	}
}
