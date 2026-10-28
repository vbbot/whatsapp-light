package walib

import (
	"fmt"
	"io"
	"sync"
	"time"

	meowcaller "github.com/purpshell/meowcaller"
)

// Mic audio for meowcaller's send loop.
//
// meowcaller sends one audio frame per 60 ms tick and calls ReadFrame on each tick. It must
// never wait: a ReadFrame that blocks until the phone's audio clock delivers the next frame
// makes packets leave unevenly (the two clocks drift), WhatsApp's receiver then grows its
// audio jitter buffer to about a second, and holds our video back by the same amount to keep
// lip sync (measured: ~1.2 s video delay with audio, near-live without it). So ReadFrame
// returns immediately: a queued frame if there is one, otherwise silence. After running dry
// it waits for a small cushion before playing again, so occasional jitter doesn't click.
const (
	maxQueuedMicFrames = 4 // 240 ms: beyond this the oldest frame is dropped
	refillMicFrames    = 2 // 120 ms cushion rebuilt after an underrun
)

type micSource struct {
	mu        sync.Mutex
	pending   []float32
	queue     [][]float32
	refilling bool
	closed    bool
	silence   []float32
	log       func(string)

	// stats since the last log line
	maxDepth  int
	drops     int
	underruns int
	lastStats time.Time
	gain      *micGain
}

func newMicSource(log func(string)) *micSource {
	return &micSource{silence: make([]float32, meowcaller.FrameSamples), refilling: true, log: log, gain: newMicGain()}
}

// push adds captured mic audio (16 kHz mono s16le, any chunk size).
func (m *micSource) push(pcm []byte) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for i := 0; i+1 < len(pcm); i += 2 {
		m.pending = append(m.pending, float32(int16(uint16(pcm[i])|uint16(pcm[i+1])<<8))/32768)
	}
	for len(m.pending) >= meowcaller.FrameSamples {
		f := make([]float32, meowcaller.FrameSamples)
		copy(f, m.pending)
		if m.gain != nil {
			m.gain.process(f)
		}
		m.pending = m.pending[meowcaller.FrameSamples:]
		m.queue = append(m.queue, f)
		if len(m.queue) > maxQueuedMicFrames {
			m.queue = m.queue[1:] // keep latency bounded: drop the oldest
			m.drops++
		}
		if len(m.queue) > m.maxDepth {
			m.maxDepth = len(m.queue)
		}
	}
	m.logStatsLocked()
}

// ReadFrame never blocks (see above): a queued frame, or silence.
func (m *micSource) ReadFrame() ([]float32, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return nil, io.EOF
	}
	if m.refilling {
		if len(m.queue) < refillMicFrames {
			return m.silence, nil
		}
		m.refilling = false
	}
	if len(m.queue) == 0 {
		m.underruns++
		m.refilling = true
		return m.silence, nil
	}
	f := m.queue[0]
	m.queue = m.queue[1:]
	return f, nil
}

func (m *micSource) Close() error {
	m.mu.Lock()
	m.closed = true
	m.mu.Unlock()
	return nil
}

func (m *micSource) logStatsLocked() {
	now := time.Now()
	if m.log == nil || now.Sub(m.lastStats) < 10*time.Second {
		return
	}
	if !m.lastStats.IsZero() {
		m.log(fmt.Sprintf("audio out: mic queue max %d x 60 ms, dropped %d, underruns %d", m.maxDepth, m.drops, m.underruns))
	}
	m.lastStats, m.maxDepth, m.drops, m.underruns = now, 0, 0, 0
}

func floatToS16(f []float32) []byte {
	out := make([]byte, len(f)*2)
	for i, s := range f {
		if s > 1 {
			s = 1
		} else if s < -1 {
			s = -1
		}
		v := int16(s * 32767)
		out[2*i], out[2*i+1] = byte(v), byte(v>>8)
	}
	return out
}
