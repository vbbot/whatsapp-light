package rtp

import "time"

// wa-poc patch: SeqReorderBuffer puts RTP packets back into sequence order before they reach
// H264AccessUnitAssembler, which treats any out-of-order packet as loss and then discards
// every frame until the next IDR. Packets are held briefly; a missing sequence number is
// declared lost only when the buffer grows past MaxPending or has waited MaxWait.

// OrderedPacket is one RTP payload released in sequence order.
type OrderedPacket struct {
	Seq     uint16
	Marker  bool
	Payload []byte
}

// SeqReorderBuffer is a small RTP reorder stage. The zero value is ready to use.
type SeqReorderBuffer struct {
	next      uint16
	started   bool
	pending   map[uint16]OrderedPacket
	waitSince time.Time

	// Stats (cumulative).
	Late    uint64 // arrived after their slot was released or skipped: dropped
	Skipped uint64 // sequence numbers given up on as lost
	Held    uint64 // packets that had to wait for an earlier one
}

const (
	// Measured: at 80 ms many packets still arrived "late" once the peer ramped up its bitrate.
	reorderMaxPending = 96
	reorderMaxWait    = 200 * time.Millisecond
)

// Push accepts one packet and returns the packets now releasable, in order.
func (b *SeqReorderBuffer) Push(seq uint16, marker bool, payload []byte, now time.Time) []OrderedPacket {
	if b.pending == nil {
		b.pending = make(map[uint16]OrderedPacket)
	}
	if !b.started {
		b.started = true
		b.next = seq
	}
	if int16(seq-b.next) < 0 {
		b.Late++
		return nil
	}
	if _, dup := b.pending[seq]; dup {
		return nil
	}
	p := make([]byte, len(payload))
	copy(p, payload)
	b.pending[seq] = OrderedPacket{Seq: seq, Marker: marker, Payload: p}
	if seq != b.next {
		b.Held++
	}

	out := b.drain(nil)
	if len(b.pending) == 0 {
		b.waitSince = time.Time{}
		return out
	}
	if b.waitSince.IsZero() {
		b.waitSince = now
	}
	if len(b.pending) > reorderMaxPending || now.Sub(b.waitSince) > reorderMaxWait {
		// Give up on the gap: jump to the oldest packet we do have.
		oldest := b.next
		first := true
		for s := range b.pending {
			if first || int16(s-oldest) < 0 {
				oldest, first = s, false
			}
		}
		b.Skipped += uint64(uint16(oldest - b.next))
		b.next = oldest
		out = b.drain(out)
		if len(b.pending) == 0 {
			b.waitSince = time.Time{}
		} else {
			b.waitSince = now
		}
	}
	return out
}

func (b *SeqReorderBuffer) drain(out []OrderedPacket) []OrderedPacket {
	for {
		p, ok := b.pending[b.next]
		if !ok {
			return out
		}
		delete(b.pending, b.next)
		out = append(out, p)
		b.next++
	}
}
