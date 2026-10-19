package rtp

import (
	"encoding/binary"
	"testing"
)

func TestFindReportBlockInRR(t *testing.T) {
	pkt := make([]byte, 8+24)
	pkt[0] = 0x81 // V=2, RC=1
	pkt[1] = 201
	binary.BigEndian.PutUint16(pkt[2:], uint16(len(pkt)/4-1))
	binary.BigEndian.PutUint32(pkt[4:], 0x1111)
	b := pkt[8:]
	binary.BigEndian.PutUint32(b, 0xabcd)
	b[4] = 64 // 25%
	b[5], b[6], b[7] = 0, 0, 7
	binary.BigEndian.PutUint32(b[12:], 900)
	got, ok := FindReportBlock(pkt, 0xabcd)
	if !ok || got.FractionLost != 64 || got.CumulativeLost != 7 || got.Jitter != 900 {
		t.Fatalf("got %+v ok=%v", got, ok)
	}
	if _, ok := FindReportBlock(pkt, 0x9999); ok {
		t.Fatal("unexpected match")
	}
}
