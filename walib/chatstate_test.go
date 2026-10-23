package walib

import (
	"math"
	"testing"
	"time"
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
