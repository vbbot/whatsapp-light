package rtp

import (
	"testing"
	"time"
)

func seqs(ps []OrderedPacket) []uint16 {
	out := make([]uint16, len(ps))
	for i, p := range ps {
		out[i] = p.Seq
	}
	return out
}

func TestReorderBufferReorders(t *testing.T) {
	var b SeqReorderBuffer
	now := time.Now()
	if got := seqs(b.Push(10, false, []byte{1}, now)); len(got) != 1 || got[0] != 10 {
		t.Fatalf("first: %v", got)
	}
	if got := b.Push(12, false, []byte{1}, now); len(got) != 0 {
		t.Fatalf("12 should wait for 11: %v", seqs(got))
	}
	got := seqs(b.Push(11, true, []byte{1}, now))
	if len(got) != 2 || got[0] != 11 || got[1] != 12 {
		t.Fatalf("want [11 12], got %v", got)
	}
	if b.Skipped != 0 || b.Late != 0 {
		t.Fatalf("no loss expected: %+v", b)
	}
}

func TestReorderBufferSkipsLossAfterWait(t *testing.T) {
	var b SeqReorderBuffer
	now := time.Now()
	b.Push(65534, false, []byte{1}, now) // wraps
	b.Push(0, false, []byte{1}, now)     // 65535 missing
	got := seqs(b.Push(1, false, []byte{1}, now.Add(250*time.Millisecond)))
	if len(got) != 2 || got[0] != 0 || got[1] != 1 || b.Skipped != 1 {
		t.Fatalf("want skip 65535 then [0 1], got %v skipped=%d", got, b.Skipped)
	}
	if late := b.Push(65535, false, []byte{1}, now); late != nil || b.Late != 1 {
		t.Fatalf("late packet should be dropped")
	}
}
