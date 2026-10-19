package rtp

import (
	"encoding/binary"
	"strings"
	"testing"
)

func TestRtcpCensusCountsNack(t *testing.T) {
	// RR (8 bytes, RC=0) + generic NACK with one FCI (pid 100, blp 0b101 -> 3 seqs).
	rr := []byte{0x80, 201, 0, 1, 0, 0, 0, 1}
	nack := make([]byte, 16)
	nack[0] = 0x81
	nack[1] = 205
	binary.BigEndian.PutUint16(nack[2:], 3)
	binary.BigEndian.PutUint16(nack[12:], 100)
	binary.BigEndian.PutUint16(nack[14:], 0b101)
	var c RtcpCensus
	c.Add(append(rr, nack...))
	s := c.String()
	if !strings.Contains(s, "NACK=1") || !strings.Contains(s, "RR=1") || c.NackedSeqs != 3 {
		t.Fatalf("census: %s nacked=%d", s, c.NackedSeqs)
	}
}
