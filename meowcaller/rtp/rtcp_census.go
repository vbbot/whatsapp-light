package rtp

import (
	"encoding/binary"
	"fmt"
	"sort"
	"strings"
)

// wa-poc patch: RtcpCensus counts the RTCP packet types a peer sends (by PT and FMT), so we
// can see what feedback a real WhatsApp client expects the sender to act on: NACK
// (RTPFB/1), transport-wide CC (RTPFB/15), PLI (PSFB/1), FIR (PSFB/4), REMB/app (PSFB/15).
type RtcpCensus struct {
	counts      map[string]int
	NackedSeqs  int // sequence numbers asked for retransmission
	nackSamples []string
}

func rtcpName(pt, fmtv uint8) string {
	switch pt {
	case 200:
		return "SR"
	case 201:
		return "RR"
	case 202:
		return "SDES"
	case 203:
		return "BYE"
	case 204:
		return "APP"
	case 205:
		switch fmtv {
		case 1:
			return "NACK"
		case 15:
			return "TWCC"
		}
		return fmt.Sprintf("RTPFB/%d", fmtv)
	case 206:
		switch fmtv {
		case 1:
			return "PLI"
		case 4:
			return "FIR"
		case 15:
			return "PSFB-APP(REMB?)"
		}
		return fmt.Sprintf("PSFB/%d", fmtv)
	}
	return fmt.Sprintf("PT%d/%d", pt, fmtv)
}

// Add counts every packet in one (decrypted) compound RTCP packet.
func (c *RtcpCensus) Add(compound []byte) {
	if c.counts == nil {
		c.counts = make(map[string]int)
	}
	for off := 0; off+4 <= len(compound); {
		if (compound[off]>>6)&0x03 != 2 {
			c.counts["?non-v2"]++
			return
		}
		fmtv := compound[off] & 0x1f
		pt := compound[off+1]
		length := (int(binary.BigEndian.Uint16(compound[off+2:])) + 1) * 4
		if length < 4 || off+length > len(compound) {
			c.counts["?truncated"]++
			return
		}
		name := rtcpName(pt, fmtv)
		c.counts[name]++
		if pt == 205 && fmtv == 1 && length >= 16 {
			// Generic NACK FCI: PID (16) + BLP (16) bitmask of the following 16.
			for fci := compound[off+12 : off+length]; len(fci) >= 4; fci = fci[4:] {
				pid := binary.BigEndian.Uint16(fci)
				blp := binary.BigEndian.Uint16(fci[2:])
				n := 1
				for b := blp; b != 0; b &= b - 1 {
					n++
				}
				c.NackedSeqs += n
				if len(c.nackSamples) < 5 {
					c.nackSamples = append(c.nackSamples, fmt.Sprintf("%d+%04x", pid, blp))
				}
			}
		}
		off += length
	}
}

// String summarises the counts, e.g. "NACK=3 RR=12 SR=12 TWCC=40".
func (c *RtcpCensus) String() string {
	keys := make([]string, 0, len(c.counts))
	for k := range c.counts {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	parts := make([]string, 0, len(keys)+1)
	for _, k := range keys {
		parts = append(parts, fmt.Sprintf("%s=%d", k, c.counts[k]))
	}
	if c.NackedSeqs > 0 {
		parts = append(parts, fmt.Sprintf("nacked_seqs=%d e.g.%v", c.NackedSeqs, c.nackSamples))
	}
	return strings.Join(parts, " ")
}
