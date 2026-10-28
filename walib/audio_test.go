package walib

import (
	"testing"
	"time"

	meowcaller "github.com/purpshell/meowcaller"
)

func frameBytes(v int16) []byte {
	b := make([]byte, meowcaller.FrameSamples*2)
	for i := 0; i < meowcaller.FrameSamples; i++ {
		b[2*i], b[2*i+1] = byte(v), byte(uint16(v)>>8)
	}
	return b
}

func TestMicReadFrameNeverBlocks(t *testing.T) {
	m := newMicSource(nil)
	done := make(chan struct{})
	go func() {
		f, err := m.ReadFrame() // nothing pushed: must return silence at once
		if err != nil || len(f) != meowcaller.FrameSamples || f[0] != 0 {
			t.Errorf("want silence, got len=%d err=%v", len(f), err)
		}
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(200 * time.Millisecond):
		t.Fatal("ReadFrame blocked")
	}
}

func TestMicRefillsCushionThenPlays(t *testing.T) {
	m := newMicSource(nil)
	m.push(frameBytes(1000))
	if f, _ := m.ReadFrame(); f[0] != 0 {
		t.Fatal("one frame queued: should still be refilling (silence)")
	}
	m.push(frameBytes(2000))
	if f, _ := m.ReadFrame(); f[0] == 0 {
		t.Fatal("cushion reached: should play the oldest frame")
	}
	if f, _ := m.ReadFrame(); f[0] == 0 {
		t.Fatal("should play the second frame")
	}
	if f, _ := m.ReadFrame(); f[0] != 0 || m.underruns != 1 {
		t.Fatalf("empty: want silence and one underrun, got underruns=%d", m.underruns)
	}
}

func TestMicDropsOldestBeyondMax(t *testing.T) {
	m := newMicSource(nil)
	m.gain = nil // frames are told apart by their sample values
	for i := 1; i <= maxQueuedMicFrames+2; i++ {
		m.push(frameBytes(int16(i * 100)))
	}
	if len(m.queue) != maxQueuedMicFrames || m.drops != 2 {
		t.Fatalf("queue=%d drops=%d", len(m.queue), m.drops)
	}
	f, _ := m.ReadFrame()
	if got := int(f[0]*32768 + 0.5); got != 300 {
		t.Fatalf("oldest kept frame should be #3 (300), got %d", got)
	}
}

func TestMicClosedReturnsEOF(t *testing.T) {
	m := newMicSource(nil)
	m.Close()
	if _, err := m.ReadFrame(); err == nil {
		t.Fatal("want EOF after Close")
	}
}
