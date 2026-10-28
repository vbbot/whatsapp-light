package walib

import "math"

// Mic level control. Android hands over the mic at whatever level it captures, which on some
// phones is far below a normal voice-call level, so the other person hears us too quietly.
const (
	micTargetRMS   = 0.1   // about -20 dBFS: a usual voice-call speech level
	micMaxGain     = 16.0  // at most +24 dB
	micStartGain   = 4.0   // +12 dB until the first speech has been measured
	micSpeechRMS   = 0.003 // about -50 dBFS: quieter frames are pauses, and keep the gain
	micRisePerStep = 1.06  // about +0.5 dB per 60 ms frame, so quiet speech comes up in seconds
	micPeakLimit   = float32(0.9)
)

// micGain raises quiet speech towards micTargetRMS. It holds its gain through pauses so
// background noise isn't pumped up, drops at once for loud speech, and limits peaks.
type micGain struct {
	gain float64
}

func newMicGain() *micGain { return &micGain{gain: micStartGain} }

// process applies the gain to frame in place.
func (g *micGain) process(frame []float32) {
	if len(frame) == 0 {
		return
	}
	var sq float64
	for _, v := range frame {
		sq += float64(v) * float64(v)
	}
	if rms := math.Sqrt(sq / float64(len(frame))); rms > micSpeechRMS {
		want := math.Min(math.Max(micTargetRMS/rms, 1), micMaxGain)
		if want < g.gain {
			g.gain = want
		} else {
			g.gain = math.Min(want, g.gain*micRisePerStep)
		}
	}
	gain := float32(g.gain)
	var peak float32
	for i, v := range frame {
		v *= gain
		frame[i] = v
		if a := float32(math.Abs(float64(v))); a > peak {
			peak = a
		}
	}
	if peak > micPeakLimit {
		scale := micPeakLimit / peak
		for i := range frame {
			frame[i] *= scale
		}
		g.gain = math.Max(1, g.gain*float64(scale))
	}
}
