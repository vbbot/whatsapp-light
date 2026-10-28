package walib

import (
	"math"
	"testing"

	meowcaller "github.com/purpshell/meowcaller"
)

func sineFrame(amp float64, phase *float64) []float32 {
	f := make([]float32, meowcaller.FrameSamples)
	for i := range f {
		f[i] = float32(amp * math.Sin(*phase))
		*phase += 2 * math.Pi * 300 / 16000
	}
	return f
}

func frameStats(f []float32) (rms, peak float64) {
	var sq float64
	for _, v := range f {
		sq += float64(v) * float64(v)
		peak = math.Max(peak, math.Abs(float64(v)))
	}
	return math.Sqrt(sq / float64(len(f))), peak
}

func dBFS(v float64) float64 { return 20 * math.Log10(v) }

func TestMicGainBringsQuietSpeechToTheTargetLevel(t *testing.T) {
	g := newMicGain()
	var phase float64
	var rms float64
	for i := 0; i < 100; i++ { // 6 s of speech about -42 dBFS rms
		f := sineFrame(0.0112, &phase)
		g.process(f)
		rms, _ = frameStats(f)
	}
	if got := dBFS(rms); got < -23 || got > -17 {
		t.Fatalf("speech level after gain = %.1f dBFS; want about -20", got)
	}
}

func TestMicGainDoesNotClipLoudSpeech(t *testing.T) {
	g := newMicGain()
	var phase float64
	for i := 0; i < 50; i++ {
		f := sineFrame(0.99, &phase)
		g.process(f)
		if _, peak := frameStats(f); peak > float64(micPeakLimit)+1e-6 {
			t.Fatalf("frame %d peak = %.3f; want at most %.2f", i, peak, micPeakLimit)
		}
	}
	if g.gain != 1 {
		t.Fatalf("gain for loud speech = %.2f; want 1", g.gain)
	}
}

func TestMicGainHoldsThroughPausesInsteadOfRaisingNoise(t *testing.T) {
	g := newMicGain()
	var phase float64
	for i := 0; i < 20; i++ { // speech near the target: gain settles low
		g.process(sineFrame(0.1, &phase))
	}
	settled := g.gain
	for i := 0; i < 200; i++ { // 12 s of faint noise
		g.process(sineFrame(0.0005, &phase))
	}
	if g.gain != settled {
		t.Fatalf("gain moved during a pause: %.2f -> %.2f", settled, g.gain)
	}
}

func TestMicGainNeverGoesPastItsCap(t *testing.T) {
	g := newMicGain()
	var phase float64
	for i := 0; i < 300; i++ {
		g.process(sineFrame(0.004, &phase)) // barely above the speech threshold
	}
	if g.gain > micMaxGain {
		t.Fatalf("gain = %.2f; cap is %.0f", g.gain, micMaxGain)
	}
}
