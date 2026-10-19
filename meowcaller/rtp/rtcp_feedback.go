package rtp

import "encoding/binary"

// wa-poc patch: read the receiver's report block about one of our streams, so the sender can
// see loss and jitter as the peer experiences them.

// RtcpReportBlock is one RFC 3550 reception report block.
type RtcpReportBlock struct {
	FractionLost   uint8 // fraction of packets lost since the last report, out of 256
	CumulativeLost int32
	Jitter         uint32 // interarrival jitter in RTP timestamp units
}

// FindReportBlock scans a compound RTCP packet for a SR/RR report block about ssrc.
func FindReportBlock(compound []byte, ssrc uint32) (RtcpReportBlock, bool) {
	for off := 0; off+8 <= len(compound); {
		pt := compound[off+1]
		count := int(compound[off] & 0x1f)
		length := (int(binary.BigEndian.Uint16(compound[off+2:])) + 1) * 4
		if length <= 0 || off+length > len(compound) {
			break
		}
		blocks := -1
		switch pt {
		case 200: // SR: header 8 + sender info 20
			blocks = off + 28
		case 201: // RR: header 8
			blocks = off + 8
		}
		if blocks >= 0 {
			for i := 0; i < count && blocks+24*(i+1) <= off+length; i++ {
				b := compound[blocks+24*i:]
				if binary.BigEndian.Uint32(b) != ssrc {
					continue
				}
				lost := int32(uint32(b[5])<<16 | uint32(b[6])<<8 | uint32(b[7]))
				if lost&0x800000 != 0 {
					lost -= 1 << 24
				}
				return RtcpReportBlock{
					FractionLost:   b[4],
					CumulativeLost: lost,
					Jitter:         binary.BigEndian.Uint32(b[12:]),
				}, true
			}
		}
		off += length
	}
	return RtcpReportBlock{}, false
}
