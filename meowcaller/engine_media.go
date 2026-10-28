package meowcaller

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"io"
	"math"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/purpshell/meowcaller/diag"
	"github.com/purpshell/meowcaller/mlow"
	"github.com/purpshell/meowcaller/relay"
	"github.com/purpshell/meowcaller/rtp"
	"github.com/purpshell/meowcaller/srtp"
	"github.com/purpshell/meowcaller/stun"
	"github.com/rs/zerolog"
)

// The live-relay media loop: connect+allocate to the elected relay, then run the
// per-frame send/recv loop. Outbound pulls frames from the Call's Player (silence when
// idle), encodes via MLow + ProtectAudio, and sends to the relay; inbound classifies
// relay packets, unprotects+decodes RTP, and writes to the Call's sink.

const (
	groupRegistryInstallMaxAttempts = 32
	groupRegistryInstallBackoff     = 5 * time.Millisecond
	videoPLIInterval                = 300 * time.Millisecond
)

type videoReceiveState struct {
	assembler   rtp.H264AccessUnitAssembler
	orientation int
	// wa-poc patch: reorder stage + receive stats.
	reorder   rtp.SeqReorderBuffer
	packets   uint64
	frames    uint64
	plis      uint64
	lastStats time.Time
}

// maybeStartMedia launches the media loop for callID once both the callKey and the relay
// endpoint are known. It is idempotent — the loop starts exactly once per call.
func (e *engine) maybeStartMedia(callID string) {
	e.mu.Lock()
	m := e.calls[callID]
	if m == nil || m.started {
		e.mu.Unlock()
		return
	}
	if m.group && (!m.hasGroupEpoch || len(m.groupRawEpoch) != 32 ||
		m.groupUpdate == nil || m.groupUpdate.Relay == nil) {
		e.c.log.Debug().
			Str("call_id", callID).
			Bool("has_group_epoch", m.hasGroupEpoch).
			Int("group_epoch_bytes", len(m.groupRawEpoch)).
			Bool("has_group_update", m.groupUpdate != nil).
			Bool("has_group_relay", m.groupUpdate != nil && m.groupUpdate.Relay != nil).
			Msg("group media prerequisites incomplete")
		e.mu.Unlock()
		return
	}
	if !m.group && m.relay == nil {
		e.mu.Unlock()
		return
	}
	if !m.group && len(m.callKey) == 0 {
		e.mu.Unlock()
		return
	}
	rd := m.relay
	if m.group {
		groupRelay, err := groupRelayData(*m.groupUpdate, m.direction == CallDirectionIncoming)
		if err != nil {
			e.mu.Unlock()
			e.c.log.Debug().Err(err).Str("call_id", callID).Msg("group media is waiting for an authoritative relay")
			return
		}
		rd = groupRelay
	}
	if rd == nil {
		e.mu.Unlock()
		return
	}
	m.started = true
	mctx, cancel := context.WithCancel(context.Background())
	m.cancel = cancel
	call := m.call
	var callKey []byte
	if m.group {
		callKey = append([]byte(nil), m.groupRawEpoch...)
	} else {
		callKey = append([]byte(nil), m.callKey...)
	}
	selfLID, peerLID := m.selfLID, m.peerLID
	inbound := m.direction == CallDirectionIncoming
	e.mu.Unlock()

	if call != nil {
		call.setPhase(CallPhaseConnecting)
	}
	e.c.log.Info().Str("call_id", callID).Msg("starting media")
	go func() {
		defer clear(callKey)
		if err := e.runMedia(mctx, callID, call, callKey, selfLID, peerLID, rd, inbound); err != nil {
			e.c.log.Warn().Err(err).Str("call_id", callID).Msg("media ended")
			// wa-poc patch: a 1:1 call whose media can't run is over; end it on both phones
			// instead of leaving it open and silent.
			if mctx.Err() == nil && call != nil && call.State() != CallPhaseEnded && !e.isGroupCall(callID) {
				if hangErr := e.hangup(call); hangErr != nil {
					e.c.log.Warn().Err(hangErr).Str("call_id", callID).Msg("ending call after media failure")
				}
			}
		}
	}()
}

// connectAndAllocate opens the relay DataChannel and sends the STUN allocate, returning
// the channel and the allocate bytes (re-sent by the keepalive).
//
// NOT VALIDATED: live-relay only.
func (e *engine) connectAndAllocate(ctx context.Context, rd *relayData, streamSsrcs [9]uint32, inbound bool) (*relay.RelayMediaChannel, []byte, error) {
	return e.connectAndAllocateTo(ctx, rd, getMediaRelayEndpoint(rd, inbound), streamSsrcs, nil, 0)
}

// connectAndAllocateTo is connectAndAllocate to a chosen endpoint (wa-poc patch: a reconnect
// may try the allocation's other relays).
//
// subscribePIDs, when set, makes the allocate subscribe to those participants' media the way a
// group call's does (wa-poc patch, experimental: a 1:1 reconnect asks for the peer explicitly).
func (e *engine) connectAndAllocateTo(ctx context.Context, rd *relayData, ep *relayEndpoint, streamSsrcs [9]uint32, subscribePIDs []uint32, appDataSSRC uint32) (*relay.RelayMediaChannel, []byte, error) {
	// wa-poc patch: a reconnect gives up on a hung handshake sooner and tries again.
	connectTimeout := 12 * time.Second
	if subscribePIDs != nil || appDataSSRC != 0 {
		connectTimeout = relayReconnectConnectTimeout
	}
	log := e.c.log
	if ep == nil || len(ep.addresses) == 0 {
		return nil, nil, fmt.Errorf("relay has no usable endpoint")
	}
	addr := &net.UDPAddr{IP: net.ParseIP(ep.addresses[0].ipv4), Port: int(ep.addresses[0].port)}
	log.Info().Str("relay_name", ep.relayName).Str("addr", addr.String()).Msg("connecting media transport to relay")
	e.c.diag.Emit("relay", map[string]any{
		"event": "endpoint", "relay_name": ep.relayName,
		"ipv4": ep.addresses[0].ipv4, "port": ep.addresses[0].port, "token_id": ep.tokenID,
	})

	type result struct {
		ch  *relay.RelayMediaChannel
		err error
	}
	done := make(chan result, 1)
	go func() {
		ch, err := relay.ConnectRelayMedia(addr, relay.WithLogger(log))
		done <- result{ch, err}
	}()
	var ch *relay.RelayMediaChannel
	select {
	case r := <-done:
		if r.err != nil {
			return nil, nil, fmt.Errorf("relay connect: %w", r.err)
		}
		ch = r.ch
	case <-time.After(connectTimeout):
		return nil, nil, fmt.Errorf("relay connect timed out (DTLS didn't complete)")
	case <-ctx.Done():
		return nil, nil, ctx.Err()
	}
	log.Info().Str("relay_name", ep.relayName).Msg("relay DataChannel open")

	if int(ep.tokenID) >= len(rd.relayTokens) || rd.relayTokens[ep.tokenID] == nil {
		ch.Close()
		return nil, nil, fmt.Errorf("no relay token #%d", ep.tokenID)
	}
	if len(rd.relayKeyASCII) == 0 {
		ch.Close()
		return nil, nil, fmt.Errorf("relay has no <key>")
	}
	e.c.diag.Emit("relay", map[string]any{
		"event": "keying", "token_id": ep.tokenID, "token_count": len(rd.relayTokens),
		"relay_key_bytes": len(rd.relayKeyASCII),
		"token_bytes":     len(rd.relayTokens[ep.tokenID]),
	})
	endpointXor, ok := stun.EncodeXorRelayEndpoint(ep.addresses[0].ipv4, ep.addresses[0].port, log)
	if !ok {
		ch.Close()
		return nil, nil, fmt.Errorf("bad endpoint XOR")
	}
	var tx [12]byte
	_, _ = rand.Read(tx[:])
	allocate := stun.BuildWasmStunAllocateRequestWithStreamSsrcs(tx, rd.relayTokens[ep.tokenID], endpointXor, streamSsrcs, rd.relayKeyASCII, log)
	if len(subscribePIDs) > 0 {
		allocate = stun.BuildWasmStunAllocateRequestWithGroupSubscriptions(tx, rd.relayTokens[ep.tokenID], endpointXor, streamSsrcs, appDataSSRC, subscribePIDs, rd.relayKeyASCII, log)
	}
	if _, err := ch.Send(allocate); err != nil {
		ch.Close()
		return nil, nil, fmt.Errorf("allocate send: %w", err)
	}
	log.Info().Int("bytes", len(allocate)).Msg("sent STUN allocate")
	e.c.diag.Emit("stun", map[string]any{
		"event": "allocate_sent", "bytes": len(allocate),
		"tx_id_hex":    hex.EncodeToString(tx[:]),
		"stream_ssrcs": streamSsrcs,
	})
	return ch, allocate, nil
}

// groupRelaySnapshotWait is how long group media waits for the relay snapshot (wa-poc patch).
const groupRelaySnapshotWait = 5 * time.Second

// wa-poc patch: reconnecting a call's relay link after a network dropout.
const (
	// relaySilenceLimit is how long the relay may say nothing before the link is treated as
	// dead. The relay answers the 1 Hz keepalive ping, so a healthy link is never this quiet.
	relaySilenceLimit = 6 * time.Second
	// relayReconnectWindow is how long a lost link is retried before the call is ended.
	relayReconnectWindow     = 30 * time.Second
	relayReconnectMaxBackoff = 4 * time.Second
	// relayRedialAfter and relayRedialLimit: how long a reconnected link may carry no peer media
	// before it's dialled again, and how many times in a row.
	relayReconnectConnectTimeout = 4 * time.Second
	relayRedialAfter             = 3 * time.Second
	relayRedialLimit             = 8
)

// wa-poc patch: an answered call whose first relay never carries the caller's media.
const (
	// initialRelayWait is how long an incoming 1:1 call waits on a relay for any of the
	// caller's media before moving to the call's next relay. Media normally arrives
	// within half a second of joining.
	initialRelayWait = 2500 * time.Millisecond
	// initialRelayMoves caps those moves, so a call with every relay silent gives up.
	initialRelayMoves = 4
)

// isGroupCall reports whether callID is a group call.
func (e *engine) isGroupCall(callID string) bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	m := e.calls[callID]
	return m != nil && m.group
}

// relaySender is what media senders write to: the call's relayLink.
type relaySender interface {
	Send(data []byte) (int, error)
}

// relayLink holds a call's current relay channel. When the channel fails mid-call it is
// replaced, while the media pipelines (and their SRTP state) carry on over the new one.
type relayLink struct {
	mu     sync.Mutex
	ch     *relay.RelayMediaChannel
	lastRx atomic.Int64
}

func newRelayLink(ch *relay.RelayMediaChannel) *relayLink {
	l := &relayLink{ch: ch}
	l.lastRx.Store(time.Now().UnixNano())
	return l
}

func (l *relayLink) current() *relay.RelayMediaChannel {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.ch
}

// Send writes to the current channel. A failed or missing channel drops the packet without
// an error, so the send loops keep running while the receive loop reconnects.
func (l *relayLink) Send(data []byte) (int, error) {
	ch := l.current()
	if ch == nil {
		return 0, nil
	}
	n, err := ch.Send(data)
	if err != nil {
		return 0, nil
	}
	return n, nil
}

// Recv reads from the current channel; an error means the channel is gone.
func (l *relayLink) Recv(buf []byte) (int, error) {
	ch := l.current()
	if ch == nil {
		return 0, fmt.Errorf("relay link is down")
	}
	n, err := ch.Recv(buf)
	if err == nil {
		l.lastRx.Store(time.Now().UnixNano())
	}
	return n, err
}

// silentFor is how long it has been since the relay last sent anything.
func (l *relayLink) silentFor(now time.Time) time.Duration {
	return now.Sub(time.Unix(0, l.lastRx.Load()))
}

// swap installs ch (nil to drop the link) and closes the channel it replaces.
func (l *relayLink) swap(ch *relay.RelayMediaChannel) {
	l.mu.Lock()
	old := l.ch
	l.ch = ch
	l.mu.Unlock()
	l.lastRx.Store(time.Now().UnixNano())
	if old != nil {
		_ = old.Close()
	}
}

// kill drops and closes the current channel, which unblocks a pending Recv. The link reads as
// down from here until the reconnect installs a new channel.
func (l *relayLink) kill() {
	l.swap(nil)
}

// reconnectCensus counts the peer's media in the first seconds after a reconnect, to tell a
// link the relay bridges from one it doesn't.
type reconnectCensus struct {
	since       time.Time
	rtcp, audio int
	redialed    bool
	checked     bool
}

// reconnectCensusWindow is how long after a reconnect the census runs.
const reconnectCensusWindow = 20 * time.Second

func (c *reconnectCensus) start(now time.Time) {
	*c = reconnectCensus{since: now}
}

func (c *reconnectCensus) active() bool {
	return !c.since.IsZero() && time.Since(c.since) < reconnectCensusWindow
}

// reconnectEndpoint picks the endpoint for reconnect attempt n: the call's usual endpoint
// first, then the allocation's other relays in turn.
func reconnectEndpoint(rd *relayData, inbound bool, n int) *relayEndpoint {
	return reconnectEndpointFrom(rd, inbound, "", n)
}

// reconnectEndpointFrom is reconnectEndpoint starting from the relay called from, when the
// allocation has it: the relay that last carried the call (wa-poc patch).
func reconnectEndpointFrom(rd *relayData, inbound bool, from string, n int) *relayEndpoint {
	primary := getMediaRelayEndpoint(rd, inbound)
	candidates := []*relayEndpoint{primary}
	for i := range rd.endpoints {
		if ep := &rd.endpoints[i]; ep != primary && len(ep.addresses) > 0 && ep.isFNA == (primary != nil && primary.isFNA) {
			candidates = append(candidates, ep)
		}
	}
	start := 0
	for i, ep := range candidates {
		if ep != nil && ep.relayName == from {
			start = i
			break
		}
	}
	return candidates[(start+n)%len(candidates)]
}

// runMedia runs the per-frame media loop over the relay DataChannel: the Player's frames
// (or silence) → MLow → E2E-SRTP protect → DataChannel, and DataChannel → classify →
// unprotect → MLow decode → the Call's sink. A 1 Hz allocate+ping keepalive holds the
// relay's consent freshness; the relay's binding-requests are answered with
// binding-success. The working recipe is preserved exactly: a consent ping (0x0801) goes
// out with the allocate at t+0, BEFORE any RTP; no STUN binding-requests are ever sent.
//
// NOT VALIDATED: live-relay only.
func (e *engine) runMedia(ctx context.Context, callID string, call *Call, callKey []byte, selfLID, peerLID string, rd *relayData, inbound bool) error {
	log := e.c.log
	selfParticipantID := rtp.FormatE2ESrtpParticipantID(selfLID)
	ssrc, err := rtp.DeriveWasmParticipantSsrc(callID, selfParticipantID, 0, log)
	if err != nil {
		return err
	}
	videoSelfSsrc, err := rtp.DeriveWasmParticipantSsrc(callID, selfParticipantID, rtp.VideoSlotWord, log)
	if err != nil {
		return err
	}
	appDataSelfSsrc, err := rtp.DeriveWasmParticipantSsrc(callID, selfParticipantID, rtp.AppDataSlotWord, log)
	if err != nil {
		return err
	}
	hbhFECTXSSRC, err := rtp.DeriveWasmParticipantSsrc(callID, selfParticipantID, rtp.HBHFECTXSlotWord, log)
	if err != nil {
		return err
	}
	hbhFECRXSSRC, err := rtp.DeriveWasmParticipantSsrc(callID, selfParticipantID, rtp.HBHFECRXSlotWord, log)
	if err != nil {
		return err
	}
	streamSsrcs, err := rtp.DeriveWasmRelayStreamSsrcs(callID, selfParticipantID, log)
	if err != nil {
		return err
	}
	streamSsrcs, err = prepareWasmRelayStreamSSRCs(streamSsrcs, appDataSelfSsrc, rand.Reader)
	if err != nil {
		return err
	}
	firstCh, allocate, err := e.connectAndAllocate(ctx, rd, streamSsrcs, inbound)
	if err != nil {
		return err
	}
	// wa-poc patch: media goes through a relayLink so the channel can be replaced mid-call.
	ch := newRelayLink(firstCh)
	defer ch.swap(nil)
	e.mu.Lock()
	if m := e.calls[callID]; m != nil {
		m.link = ch
	}
	e.mu.Unlock()
	defer func() {
		e.mu.Lock()
		if m := e.calls[callID]; m != nil && m.link == ch {
			m.link = nil
		}
		e.mu.Unlock()
	}()
	allocateState := newGroupRelayAllocateStateWithHBHFEC(
		allocate,
		rd.relayKeyASCII,
		[2]uint32{hbhFECTXSSRC, hbhFECRXSSRC},
	)

	// Send a consent ping (0x0801) immediately, together with the allocate and BEFORE any
	// RTP. The relay won't forward the peer's media until consent (ping → pong) is
	// established; RTP sent before the first ping is dropped and the relay never bridges.
	{
		var ptx [12]byte
		_, _ = rand.Read(ptx[:])
		initPing := stun.BuildWhatsappPing(ptx, log)
		_, _ = ch.Send(initPing[:])
		e.c.diag.Emit("stun", map[string]any{
			"event": "consent_ping_sent", "tx_id_hex": hex.EncodeToString(ptx[:]),
			"ping_hex": hex.EncodeToString(initPing[:]),
		})
	}

	log.Info().
		Str("self_lid", selfLID).
		Str("peer_lid", peerLID).
		Str("ssrc", fmt.Sprintf("0x%08x", ssrc)).
		Str("video_ssrc", fmt.Sprintf("0x%08x", videoSelfSsrc)).
		Str("app_data_ssrc", fmt.Sprintf("0x%08x", appDataSelfSsrc)).
		Msg("media session")
	e.c.diag.Emit("ssrc", map[string]any{
		"call_id": callID, "ssrc": ssrc, "video_ssrc": videoSelfSsrc, "app_data_ssrc": appDataSelfSsrc,
		"stream_ssrcs": streamSsrcs, "self_lid": selfLID,
		"participant_id": selfParticipantID,
	})

	enc := mlow.NewMlowEncoder(mlow.WithLogger(log))
	e.mu.Lock()
	m := e.calls[callID]
	audioReceivers := (*participantReceiveRegistry)(nil)
	var groupMode atomic.Bool
	if m != nil {
		audioReceivers = m.groupReceivers
		groupMode.Store(m.group)
	}
	e.mu.Unlock()
	if audioReceivers == nil {
		audioReceivers, err = newParticipantReceiveRegistry(
			callID,
			callKey,
			selfLID,
			peerLID,
			func() participantAudioDecoder {
				return mlow.NewMlowDecoder(mlow.WithLogger(log))
			},
			WithLogger(log),
		)
		if err != nil {
			return err
		}
		unpublishedReceivers := audioReceivers
		defer func() {
			if unpublishedReceivers != nil {
				unpublishedReceivers.clear()
			}
		}()
		if groupMode.Load() {
			installed := false
			for attempt := 0; attempt < groupRegistryInstallMaxAttempts; attempt++ {
				if err = ctx.Err(); err != nil {
					return err
				}
				e.mu.Lock()
				current := e.calls[callID]
				if current == nil || !current.group || current.groupUpdate == nil ||
					!current.hasGroupEpoch || len(current.groupRawEpoch) != 32 {
					e.mu.Unlock()
					return fmt.Errorf("meowcaller: group media prerequisites changed during startup")
				}
				update := cloneGroupCallUpdate(*current.groupUpdate)
				rawEpoch := append([]byte(nil), current.groupRawEpoch...)
				transactionID := current.groupEpochTxID
				e.mu.Unlock()

				if err = audioReceivers.ApplyGroupUpdate(update); err == nil {
					err = audioReceivers.ApplyGroupRawEpoch(transactionID, rawEpoch)
				}
				clear(rawEpoch)
				if err != nil {
					return fmt.Errorf("initialize group participant media: %w", err)
				}

				e.mu.Lock()
				current = e.calls[callID]
				if current == nil || !current.group {
					e.mu.Unlock()
					return fmt.Errorf("meowcaller: group call ended during media startup")
				}
				if current.groupReceivers != nil {
					discarded := audioReceivers
					audioReceivers = current.groupReceivers
					e.mu.Unlock()
					discarded.clear()
					unpublishedReceivers = nil
					installed = true
					break
				}
				if current.groupUpdate != nil &&
					current.groupUpdate.TransactionID <= update.TransactionID &&
					current.groupEpochTxID <= transactionID {
					current.groupReceivers = audioReceivers
					e.mu.Unlock()
					unpublishedReceivers = nil
					installed = true
					break
				}
				e.mu.Unlock()
				select {
				case <-ctx.Done():
					return ctx.Err()
				case <-time.After(groupRegistryInstallBackoff):
				}
			}
			if !installed {
				return fmt.Errorf(
					"meowcaller: group media roster changed during %d startup attempts",
					groupRegistryInstallMaxAttempts,
				)
			}
		} else {
			e.mu.Lock()
			current := e.calls[callID]
			if current == nil {
				e.mu.Unlock()
				return fmt.Errorf("meowcaller: direct call ended during media startup")
			}
			if current.groupReceivers == nil {
				current.groupReceivers = audioReceivers
				e.mu.Unlock()
			} else {
				discarded := audioReceivers
				audioReceivers = current.groupReceivers
				e.mu.Unlock()
				discarded.clear()
			}
			unpublishedReceivers = nil
		}
	}
	audioPlayout := newAudioPlayoutBuffer()
	var audioPlayoutMu sync.Mutex
	var groupMixing atomic.Bool
	defer func() {
		if groupMixing.Load() {
			return
		}
		audioPlayoutMu.Lock()
		defer audioPlayoutMu.Unlock()
		if audioPlayout.Dropped > 0 {
			log.Info().Str("call_id", callID).Uint64("dropped", audioPlayout.Dropped).Msg("inbound audio frames dropped as duplicate or late")
		}
		_, sink := callPlayerSink(call)
		_ = audioPlayout.Flush(sink)
	}()
	audioMixer := newParticipantAudioMixer()
	var audioSinkFramer participantAudioSinkFramer
	go func() {
		ticker := time.NewTicker(time.Duration(participantAudioMixChunkSamples) * time.Second / SampleRate)
		defer ticker.Stop()
		playoutStarted := false
		var writeFailures uint64
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
			}
			if !groupMixing.Load() {
				continue
			}
			_, sink := callPlayerSink(call)
			if sink == nil {
				continue
			}
			chunk, ok := audioMixer.MixChunk()
			if !ok {
				continue
			}
			frame, ok := audioSinkFramer.Push(chunk)
			if !ok {
				continue
			}
			if err := sink.WriteFrame(frame); err != nil {
				if writeFailures++; writeFailures == 1 {
					log.Warn().Err(err).Msg("failed to write mixed WhatsApp audio")
				}
				continue
			}
			if !playoutStarted {
				playoutStarted = true
				log.Info().
					Int("prefill_ms", participantAudioMixerPrefillSamples*1000/SampleRate).
					Int("chunk_ms", participantAudioMixChunkSamples*1000/SampleRate).
					Msg("started participant-mixed inbound audio playout")
			}
		}
	}()
	txPipe, err := NewMediaPipeline(callKey, selfLID, peerLID, ssrc, FrameSamples, WithLogger(log))
	if err != nil {
		return err
	}
	if err = audioReceivers.attachSendPipeline(txPipe); err != nil {
		return fmt.Errorf("attach audio RTP sender: %w", err)
	}
	audioRtcp, err := newMediaSrtcpSender(callKey, selfLID, ssrc, false)
	if err != nil {
		return err
	}
	if err = audioReceivers.attachSRTCPSender(audioRtcp); err != nil {
		return fmt.Errorf("attach audio SRTCP sender: %w", err)
	}
	// The derived E2E-SRTP keys live inside MediaPipeline; record the derivation INPUTS
	// (callKey + participant-ID info strings) so a reference can re-derive and compare.
	e.c.diag.Emit("srtp", map[string]any{
		"event": "media_keys_input", "call_id": callID, "ssrc": ssrc,
		"self_participant_id": selfParticipantID,
		"peer_participant_id": rtp.FormatE2ESrtpParticipantID(peerLID),
		"call_key_bytes":      len(callKey),
	})
	e.c.diag.Emit("meta", map[string]any{
		"event": "media_start", "call_id": callID, "self_lid": selfLID,
		"peer_lid": peerLID, "ssrc": ssrc,
	})

	// relayRx counts packets received from the relay, so the silence watchdog can warn if
	// the relay never answers our allocate.
	var relayRx atomic.Uint64

	// Inbound calls are torn down by the caller within ~400ms if the relay bind never
	// comes alive; check at 400ms and 900ms and say so explicitly.
	go func() {
		for _, d := range []time.Duration{400 * time.Millisecond, 900 * time.Millisecond} {
			select {
			case <-ctx.Done():
				return
			case <-time.After(d):
			}
			if relayRx.Load() == 0 {
				log.Warn().Dur("after", d).Msg("relay silent after allocate, no bytes back yet (allocate undelivered or rejected)")
			}
		}
	}()

	// Keepalive: re-send the Allocate AND a WhatsApp ping (0x0801) ~1 Hz. This matches the
	// working capture exactly — allocate+ping every second, NO STUN binding-requests at
	// all; the relay answers allocate-success + pong and bridges the peer's media.
	// Binding-requests instead flip the relay into ICE-consent mode and the bridge never
	// forms.
	go func() {
		t := time.NewTicker(time.Second)
		defer t.Stop()
		var tickCount uint64
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
			}
			var tx [12]byte
			_, _ = rand.Read(tx[:])
			ping := stun.BuildWhatsappPing(tx, log)
			allocateSent := false
			e.mu.Lock()
			currentCall := e.calls[callID]
			if currentCall != nil && currentCall.group {
				groupMode.Store(true)
			}
			e.mu.Unlock()
			if groupMode.Load() {
				e.mu.Lock()
				current := e.calls[callID]
				var update *groupCallUpdate
				if current != nil && current.groupUpdate != nil {
					cloned := cloneGroupCallUpdate(*current.groupUpdate)
					update = &cloned
				}
				e.mu.Unlock()
				if update != nil && update.Relay != nil {
					var relayTx [12]byte
					if _, err := rand.Read(relayTx[:]); err == nil {
						endpoint := getMediaRelayEndpoint(rd, inbound)
						allocateSent, err = allocateState.ApplyWithSubscriptions(
							endpoint,
							update.Relay,
							streamSsrcs,
							appDataSelfSsrc,
							connectedRemoteParticipantPIDs(*update, audioReceivers.selfID),
							relayTx,
							func(packet []byte) error {
								_, sendErr := ch.Send(packet)
								return sendErr
							},
						)
						if err != nil {
							log.Warn().Err(err).Str("call_id", callID).Msg("failed to refresh group relay allocation")
						}
					}
				}
				activeParticipantIDs := audioReceivers.ActiveParticipantIDs()
				audioMixer.Retain(activeParticipantIDs)
				if shouldStartParticipantMixing(activeParticipantIDs) && !groupMixing.Load() {
					audioPlayoutMu.Lock()
					_, sink := callPlayerSink(call)
					if err := audioPlayout.Drain(sink); err != nil {
						log.Warn().Err(err).Msg("failed to drain direct audio before group playout")
					}
					groupMixing.Store(true)
					audioPlayoutMu.Unlock()
				}
			}
			if !allocateSent {
				if err := allocateState.SendCurrent(func(packet []byte) error {
					_, sendErr := ch.Send(packet)
					return sendErr
				}); err != nil {
					return
				}
			}
			_, _ = ch.Send(ping[:])
			tickCount++
			e.c.diag.Emit("stun", map[string]any{
				"event": "keepalive", "tick": tickCount,
				"tx_id_hex": hex.EncodeToString(tx[:]), "ping_hex": hex.EncodeToString(ping[:]),
			})
		}
	}()

	// wa-poc patch: the relay answers every keepalive, so a silent link is a dead one (the
	// network dropped and the transport hasn't noticed yet). Closing it makes the receive loop
	// reconnect.
	go func() {
		t := time.NewTicker(time.Second)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case now := <-t.C:
				if silent := ch.silentFor(now); silent > relaySilenceLimit && ch.current() != nil {
					log.Warn().Dur("silent", silent).Str("call_id", callID).Msg("relay link silent, treating it as lost")
					ch.kill()
				}
			}
		}
	}()

	// Send loop: frame-paced from connect, NOT gated on the Player. WhatsApp starts media
	// on relay connection and the relay learns our SSRC from our FIRST RTP — it won't
	// bridge the peer's media until it sees our stream. So we send silence frames until the
	// Player has real audio (nextFrame() == nil means send silence).
	frameInterval := time.Duration(FrameSamples) * time.Second / SampleRate
	go func() {
		silence := make([]float32, FrameSamples)
		ticker := time.NewTicker(frameInterval)
		defer ticker.Stop()
		var txCount uint64
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
			}
			frame := silence
			if player, _ := callPlayerSink(call); player != nil {
				if f := player.nextFrame(); f != nil {
					frame = f
				}
			}
			payload, err := enc.Encode(frame)
			if err != nil {
				continue
			}
			packet, err := txPipe.ProtectAudio(payload)
			if err != nil {
				continue
			}
			e.c.diag.Emit("media_out", map[string]any{
				"frame": txCount, "frame_samples": len(frame), "pcm_rms": rmsFloat32(frame),
				"payload_len": len(payload), "packet_len": len(packet),
			})
			if _, err := ch.Send(packet); err != nil {
				return
			}
			if txCount++; txCount == 1 {
				log.Info().Int("bytes", len(packet)).Msg("first RTP sent to relay, outbound media flowing")
				e.c.diag.Emit("meta", map[string]any{"event": "first_rtp_sent", "call_id": callID, "bytes": len(packet)})
			}
		}
	}()

	// Receive: DataChannel → classify. RTP → unprotect → decode → sink. A non-RTP STUN
	// binding request gets a binding-success reply (ICE consent freshness, RFC 7675);
	// without it the relay drops the binding and the peer's call fails.
	// Video receive uses participant-scoped WARP pipelines keyed on each video SSRC.
	//
	// NOT VALIDATED: no live video-RTP vector; assumes video shares the audio E2E keys and
	// WARP framing, and that the relay bridges the video SSRC.
	rekeyPeer := func(answeringPeer string) error {
		return audioReceivers.RekeyFallback(answeringPeer)
	}
	e.mu.Lock()
	currentPeer := peerLID
	if m := e.calls[callID]; m != nil {
		m.rekeyPeer = rekeyPeer
		currentPeer = m.peerLID
	}
	e.mu.Unlock()
	if currentPeer != "" && currentPeer != peerLID {
		if err := rekeyPeer(currentPeer); err != nil {
			return fmt.Errorf("rekey media to answering device: %w", err)
		}
		peerLID = currentPeer
	}
	defer func() {
		e.mu.Lock()
		if m := e.calls[callID]; m != nil {
			m.rekeyPeer = nil
		}
		e.mu.Unlock()
	}()
	videoReceiveStates := make(map[*participantAudioReceiver]*videoReceiveState)
	appDataReceivers := make(map[*participantAudioReceiver]*appDataReceiver)
	lastVideoPLI := make(map[uint32]time.Time)
	var rosterGeneration uint64
	hasRosterGeneration := false
	var videoWirePacket, videoWireFrame uint64
	var videoExtMissing int // wa-poc patch

	// Video send: a second WARP pipeline on our video SSRC, registered on the call so
	// Call.SendVideoFrame can push encoded H.264 to the relay. Cleared when the loop exits.
	txVideoPipe, err := NewMediaPipeline(callKey, selfLID, peerLID, videoSelfSsrc, FrameSamples, WithLogger(log))
	if err != nil {
		return err
	}
	if err = audioReceivers.attachSendPipeline(txVideoPipe); err != nil {
		return fmt.Errorf("attach video RTP sender: %w", err)
	}
	videoRtcp, err := newMediaSrtcpSender(callKey, selfLID, videoSelfSsrc, true)
	if err != nil {
		return err
	}
	if err = audioReceivers.attachSRTCPSender(videoRtcp); err != nil {
		return fmt.Errorf("attach video SRTCP sender: %w", err)
	}
	vsender := &videoSender{
		pipe: txVideoPipe, stream: rtp.NewVideoRtpStream(videoSelfSsrc, defaultVideoRtpStepSamples),
		ch: ch, ssrc: videoSelfSsrc, callID: callID, keyframeRequired: true,
		log: log, diag: e.c.diag,
	}
	txAppDataPipe, err := NewMediaPipeline(callKey, selfLID, peerLID, appDataSelfSsrc, FrameSamples, WithLogger(log))
	if err != nil {
		return err
	}
	if err = audioReceivers.attachSendPipeline(txAppDataPipe); err != nil {
		return fmt.Errorf("attach app-data RTP sender: %w", err)
	}
	appSender := newAppDataSender(txAppDataPipe, appDataSelfSsrc, func(packet []byte) (int, error) {
		if header, ok := rtp.ParseRtpHeader(packet); ok {
			e.c.diag.Emit("app_data", map[string]any{
				"event": "out", "ssrc": header.Ssrc, "seq": header.SequenceNumber,
				"ts": header.Timestamp, "pt": header.PayloadType, "bytes": len(packet),
			})
		}
		return ch.Send(packet)
	})
	e.mu.Lock()
	if m := e.calls[callID]; m != nil {
		vsender.active = m.localVideo
		vsender.sendGated = m.videoGate
		vsender.orientation = uint8(m.localOrientation & 0x03) // wa-poc patch
		m.videoTx = vsender
		m.appDataTx = appSender
	}
	e.mu.Unlock()
	// wa-poc patch: frames sent before this were dropped, and the sender waits for an IDR; ask
	// the encoder for one now rather than at its next scheduled keyframe.
	if call != nil {
		call.requestVideoKeyframe()
	}
	defer func() {
		appSender.close()
		vsender.mu.Lock()
		vsender.ch = nil
		vsender.mu.Unlock()
		e.mu.Lock()
		if m := e.calls[callID]; m != nil {
			m.videoTx = nil
			m.appDataTx = nil
		}
		e.mu.Unlock()
	}()

	var audioReception rtp.RtcpReceptionStatsSet
	var videoReception rtp.RtcpReceptionStatsSet
	if groupMode.Load() {
		// wa-poc patch: media can start a moment before the group update carrying the relay is
		// stored (the roster is applied first); wait for it rather than ending the call's media.
		var update *groupCallUpdate
		waitUntil := time.Now().Add(groupRelaySnapshotWait)
		for {
			e.mu.Lock()
			current := e.calls[callID]
			update = nil
			if current != nil && current.groupUpdate != nil {
				cloned := cloneGroupCallUpdate(*current.groupUpdate)
				update = &cloned
			}
			e.mu.Unlock()
			if update != nil && update.Relay != nil {
				break
			}
			if current == nil || time.Now().After(waitUntil) {
				return fmt.Errorf("meowcaller: group media started without an authoritative relay snapshot")
			}
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(50 * time.Millisecond):
			}
		}
		var relayTx [12]byte
		if _, err := rand.Read(relayTx[:]); err != nil {
			return fmt.Errorf("generate group relay transaction ID: %w", err)
		}
		_, err = allocateState.ApplyWithSubscriptions(
			getMediaRelayEndpoint(rd, inbound),
			update.Relay,
			streamSsrcs,
			appDataSelfSsrc,
			connectedRemoteParticipantPIDs(*update, audioReceivers.selfID),
			relayTx,
			func(packet []byte) error {
				_, sendErr := ch.Send(packet)
				return sendErr
			},
		)
		if err != nil {
			return fmt.Errorf("send initial group relay allocation: %w", err)
		}
		audioReception.Retain(audioReceivers.ActiveAudioSSRCs())
		videoReception.Retain(audioReceivers.ActiveVideoSSRCs())
		activeParticipantIDs := audioReceivers.ActiveParticipantIDs()
		audioMixer.Retain(activeParticipantIDs)
		groupMixing.Store(shouldStartParticipantMixing(activeParticipantIDs))
	}

	// WhatsApp associates the RTP streams with an SRTCP session. Periodic compound
	// SR+SDES packets are required for the caller's video to start flowing to the
	// answerer, and give the peer a target for PLI/FIR recovery feedback.
	go func() {
		ticker := time.NewTicker(1500 * time.Millisecond)
		defer ticker.Stop()
		var sent uint64
		for {
			select {
			case <-ctx.Done():
				return
			case now := <-ticker.C:
				nowMs := uint64(now.UnixMilli())
				e.mu.Lock()
				current := e.calls[callID]
				if current != nil && current.group {
					groupMode.Store(true)
				}
				e.mu.Unlock()
				if groupMode.Load() {
					audioReception.Retain(audioReceivers.ActiveAudioSSRCs())
					videoReception.Retain(audioReceivers.ActiveVideoSSRCs())
				}
				audioReports := audioReception.Reports(nowMs)
				_, err := sendMediaSrtcpReceptionReports(
					audioRtcp,
					txPipe.SenderStats(),
					nowMs,
					audioReports,
					groupMode.Load() && audioReceivers.HasCommittedGroupUpdate(),
					func(packet []byte) error {
						_, sendErr := ch.Send(packet)
						return sendErr
					},
				)
				if err != nil {
					return
				}
				videoStats := txVideoPipe.SenderStats()
				if videoStats.PacketsSent > 0 {
					videoReports := videoReception.Reports(nowMs)
					_, err = sendMediaSrtcpReceptionReports(
						videoRtcp,
						videoStats,
						nowMs,
						videoReports,
						groupMode.Load() && audioReceivers.HasCommittedGroupUpdate(),
						func(packet []byte) error {
							_, sendErr := ch.Send(packet)
							return sendErr
						},
					)
					if err != nil {
						return
					}
				}
				sent++
				if sent == 1 {
					log.Info().Msg("started periodic SRTCP sender reports")
				}
				e.c.diag.Emit("rtcp", map[string]any{
					"event": "sender_reports", "tick": sent,
					"audio_packets": txPipe.SenderStats().PacketsSent,
					"video_packets": videoStats.PacketsSent,
				})
			}
		}
	}()

	// relayName is the relay the call is on. goodRelay is the last one that carried the
	// peer's media; reconnects start from it (wa-poc patch).
	relayName := ""
	if ep := getMediaRelayEndpoint(rd, inbound); ep != nil {
		relayName = ep.relayName
	}
	goodRelay := relayName

	// reconnect replaces a lost relay link, retrying for relayReconnectWindow, and asks both
	// sides' video for a keyframe once it's back (wa-poc patch). It returns an error when
	// the link can't be restored; the caller then ends the call.
	reconnect := func(cause error) error {
		ch.swap(nil)
		prevPhase := CallPhaseActive
		if call != nil {
			prevPhase = call.State()
			call.setPhase(CallPhaseReconnecting)
		}
		log.Warn().Err(cause).Str("call_id", callID).Msg("relay link lost, reconnecting")
		started := time.Now()
		backoff := 500 * time.Millisecond
		for attempt := 0; ; attempt++ {
			e.mu.Lock()
			current := e.calls[callID]
			latest := rd
			if current != nil && current.relay != nil && !current.group {
				latest = current.relay
			}
			e.mu.Unlock()
			if current == nil {
				return cause
			}
			ep := reconnectEndpointFrom(latest, inbound, goodRelay, attempt)
			var subscribe []uint32
			if !groupMode.Load() && latest.hasPeerPID {
				subscribe = []uint32{latest.peerPID}
			}
			newCh, newAllocate, err := e.connectAndAllocateTo(ctx, latest, ep, streamSsrcs, subscribe, appDataSelfSsrc)
			if err == nil {
				allocateState.replace(newAllocate)
				ch.swap(newCh)
				var ptx [12]byte
				_, _ = rand.Read(ptx[:])
				ping := stun.BuildWhatsappPing(ptx, log)
				_, _ = ch.Send(ping[:])
				vsender.requestKeyframe()
				if call != nil {
					call.requestVideoKeyframe()
					call.setPhase(prevPhase)
				}
				relayName = ep.relayName
				log.Info().Str("call_id", callID).Int("attempt", attempt+1).Str("relay_name", ep.relayName).
					Dur("after", time.Since(started)).Msg("relay link restored")
				go e.relayLinkRestored(callID)
				return nil
			}
			if ctx.Err() != nil {
				return ctx.Err()
			}
			log.Warn().Err(err).Str("call_id", callID).Int("attempt", attempt+1).Msg("relay reconnect attempt failed")
			if time.Since(started) > relayReconnectWindow {
				return fmt.Errorf("relay link not restored after %s: %w", relayReconnectWindow, cause)
			}
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(backoff):
			}
			if backoff *= 2; backoff > relayReconnectMaxBackoff {
				backoff = relayReconnectMaxBackoff
			}
		}
	}

	// moveRelay joins the call's next relay when the current one never brought the caller's
	// media (wa-poc patch). The current link stays up if the next relay can't be joined.
	moveRelay := func() {
		ep := reconnectEndpointFrom(rd, inbound, relayName, 1)
		if ep == nil || ep.relayName == relayName {
			return
		}
		cctx, cancel := context.WithTimeout(ctx, relayReconnectConnectTimeout)
		newCh, newAllocate, err := e.connectAndAllocateTo(cctx, rd, ep, streamSsrcs, nil, 0)
		cancel()
		if err != nil {
			log.Warn().Err(err).Str("call_id", callID).Str("relay_name", ep.relayName).Msg("joining the next relay failed")
			return
		}
		allocateState.replace(newAllocate)
		ch.swap(newCh)
		var ptx [12]byte
		_, _ = rand.Read(ptx[:])
		ping := stun.BuildWhatsappPing(ptx, log)
		_, _ = ch.Send(ping[:])
		log.Info().Str("call_id", callID).Str("from", relayName).Str("to", ep.relayName).
			Msg("no caller media on the relay, moved to the call's next relay")
		relayName = ep.relayName
	}
	joinedAt := time.Now()
	var relayMoves int

	var census reconnectCensus
	var redials int
	buf := make([]byte, 1500)
	var rtpIn, rtpSeen, unprotectFail, rtpInspect, vidIn, appDataIn, appDataUnprotectFail, videoUnprotectFail, videoFrameIn, videoSinkMissing, rtcpIn, rtcpAuthFail, groupForwardingInvalid uint64
	var lastTxFeedbackLog time.Time // wa-poc patch
	var rtcpCensus rtp.RtcpCensus   // wa-poc patch
	var lastCensusLog time.Time     // wa-poc patch
	var extSamples int              // wa-poc patch
	var lastExtLog time.Time        // wa-poc patch
	var txKeyframeRequests int      // wa-poc patch
	for {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		n, err := ch.Recv(buf)
		if err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			// wa-poc patch: reconnect rather than leave the call open with no media.
			if err = reconnect(fmt.Errorf("relay recv: %w", err)); err != nil {
				return err
			}
			for receiver, state := range videoReceiveStates {
				state.assembler = rtp.H264AccessUnitAssembler{}
				state.reorder = rtp.SeqReorderBuffer{}
				delete(lastVideoPLI, receiver.videoSSRC)
			}
			for _, videoSSRC := range audioReceivers.ActiveVideoSSRCs() {
				if packet, pliErr := videoRtcp.pictureLossIndication(videoSSRC); pliErr == nil {
					_, _ = ch.Send(packet)
				}
			}
			census.start(time.Now())
			continue
		}
		// wa-poc patch: a reconnected link can be accepted (allocate success, pongs) yet carry
		// none of the peer's media, by chance of where it lands. With nothing from the peer
		// 3 s after reconnecting, dial again from a new socket, up to relayRedialLimit times.
		// wa-poc patch (experimental): nothing from the peer 1.5 s after reconnecting; ask it to
		// check the connection, which makes its side refresh.
		if census.active() && !census.checked && rtpIn > 0 && census.audio == 0 && census.rtcp == 0 &&
			time.Since(census.since) > 1500*time.Millisecond {
			census.checked = true
			go e.sendTransportCheck(callID)
		}
		if census.active() && !census.redialed && rtpIn > 0 && census.audio == 0 && census.rtcp == 0 &&
			time.Since(census.since) > relayRedialAfter {
			census.redialed = true
			if redials < relayRedialLimit {
				redials++
				log.Warn().Str("call_id", callID).Int("redial", redials).Msg("no peer media since reconnect, dialing the relay again")
				ch.kill()
			}
		}
		if census.active() && census.audio > 0 {
			redials = 0
		}
		if inbound && !groupMode.Load() && rtpSeen == 0 && rtcpIn == 0 && !census.active() &&
			relayMoves < initialRelayMoves && time.Since(joinedAt) > initialRelayWait {
			relayMoves++
			moveRelay()
			joinedAt = time.Now()
		}
		currentRosterGeneration, activeReceivers := audioReceivers.ActiveReceiverSnapshot()
		if !hasRosterGeneration || currentRosterGeneration != rosterGeneration {
			for receiver, state := range videoReceiveStates {
				if _, active := activeReceivers[receiver]; active {
					continue
				}
				state.assembler = rtp.H264AccessUnitAssembler{}
				delete(lastVideoPLI, receiver.videoSSRC)
				delete(videoReceiveStates, receiver)
			}
			for receiver := range appDataReceivers {
				if _, active := activeReceivers[receiver]; !active {
					delete(appDataReceivers, receiver)
				}
			}
			rosterGeneration = currentRosterGeneration
			hasRosterGeneration = true
		}
		relayRx.Add(1)
		rawPkt := buf[:n]
		pkt, groupWrapped, groupValid := relay.UnwrapGroupForwardingPacket(rawPkt)
		if !groupValid {
			if groupForwardingInvalid++; groupForwardingInvalid == 1 {
				log.Warn().Int("packet_bytes", n).Msg("invalid group-forwarding relay packet")
			}
			continue
		}
		packetKind := relay.ClassifyRelayPacket(pkt)
		isRTP := packetKind == relay.RelayPacketRtp
		e.c.diag.Emit("relay", map[string]any{
			"event": "packet_in", "bytes": n, "is_rtp": isRTP,
			"group_wrapped": groupWrapped, "group_header_bytes": n - len(pkt),
		})
		if census.active() && packetKind == relay.RelayPacketRtcp {
			census.rtcp++
		}
		switch packetKind {
		case relay.RelayPacketRtcp:
			senderSsrc, ok := rtp.ParseRtcpSenderSsrc(pkt)
			if !ok {
				continue
			}
			plain, index, ok := audioReceivers.UnprotectSRTCP(senderSsrc, pkt)
			if !ok {
				if rtcpAuthFail++; rtcpAuthFail == 1 {
					log.Warn().Uint32("ssrc", senderSsrc).Msg("peer SRTCP failed authentication")
				}
				e.c.diag.Emit("rtcp", map[string]any{
					"event": "auth_failed", "ssrc": senderSsrc, "bytes": n,
				})
				continue
			}
			if rtcpIn++; rtcpIn == 1 {
				log.Info().Uint32("ssrc", senderSsrc).Uint32("index", index).Msg("first authenticated peer SRTCP received")
			}
			// wa-poc patch: which feedback does a real WhatsApp client send?
			rtcpCensus.Add(plain)
			if now := time.Now(); now.Sub(lastCensusLog) > 10*time.Second {
				lastCensusLog = now
				log.Info().Str("types", rtcpCensus.String()).Msg("peer rtcp census")
			}
			// wa-poc patch: log how the peer is receiving our video (loss, jitter, keyframe asks).
			if rb, ok := rtp.FindReportBlock(plain, videoSelfSsrc); ok {
				if now := time.Now(); now.Sub(lastTxFeedbackLog) > 10*time.Second {
					lastTxFeedbackLog = now
					log.Info().
						Float64("lost_pct", float64(rb.FractionLost)*100/256).
						Int32("lost_total", rb.CumulativeLost).
						Float64("jitter_ms", float64(rb.Jitter)/90).
						Int("keyframe_requests", txKeyframeRequests).
						Msg("video tx feedback from peer")
				}
			}
			keyframe := rtp.RtcpRequestsKeyframe(plain, videoSelfSsrc)
			if keyframe {
				txKeyframeRequests++
				vsender.requestKeyframe()
				if call != nil {
					call.requestVideoKeyframe()
				}
				log.Debug().Uint32("video_ssrc", videoSelfSsrc).Msg("peer requested a video keyframe")
			}
			if reportSsrc, ntpSeconds, ntpFraction, ok := rtp.ParseSenderReportTiming(plain); ok {
				nowMs := uint64(time.Now().UnixMilli())
				audioReception.ObserveSenderReport(reportSsrc, ntpSeconds, ntpFraction, nowMs)
				videoReception.ObserveSenderReport(reportSsrc, ntpSeconds, ntpFraction, nowMs)
			}
			e.c.diag.Emit("rtcp", map[string]any{
				"event": "in", "ssrc": senderSsrc, "index": index,
				"plain_bytes": len(plain), "requests_keyframe": keyframe,
			})
			continue
		case relay.RelayPacketStun:
			mt, isStun := stun.StunMessageType(pkt)
			if isStun && mt == stun.MsgBindingRequest {
				resp, answered, err := allocateState.SendBindingSuccess(pkt, func(packet []byte) error {
					_, sendErr := ch.Send(packet)
					return sendErr
				})
				if err != nil {
					return fmt.Errorf("relay send binding-success: %w", err)
				}
				if answered {
					e.c.diag.Emit("stun", map[string]any{
						"event": "binding_request_answered", "response_bytes": len(resp),
					})
				}
			}
			continue
		case relay.RelayPacketOther:
			continue
		}
		goodRelay = relayName
		if rtpSeen++; rtpSeen == 1 {
			log.Info().Int("bytes", n).Msg("first RTP-classified packet from relay, relay is bridging the peer's media")
		}
		vh, vok := rtp.ParseRtpHeader(pkt)
		if vok {
			if rtpInspect < 20 || vh.PayloadType == rtp.RtpPayloadTypeH264 || vh.PayloadType == rtp.RtpPayloadTypeAppData {
				log.Debug().
					Uint8("payload_type", vh.PayloadType).
					Uint32("ssrc", vh.Ssrc).
					Uint16("seq", vh.SequenceNumber).
					Uint32("timestamp", vh.Timestamp).
					Bool("marker", vh.Marker).
					Int("bytes", n).
					Msg("relay RTP packet summary")
			}
			rtpInspect++
		}
		if !vok {
			continue
		}
		kind := classifyMediaPayload(vh)
		if kind == mediaPayloadAppData {
			media, ok := audioReceivers.UnprotectAppData(pkt)
			if !ok {
				if appDataUnprotectFail++; appDataUnprotectFail == 1 {
					log.Warn().Uint32("ssrc", vh.Ssrc).Uint16("seq", vh.SequenceNumber).Msg("app-data RTP arrived but failed to unprotect")
				}
				e.c.diag.Emit("app_data", map[string]any{"event": "unprotect_failed", "ssrc": vh.Ssrc, "seq": vh.SequenceNumber})
				continue
			}
			receiver := appDataReceivers[media.receiver]
			if receiver == nil {
				receiver = &appDataReceiver{}
				appDataReceivers[media.receiver] = receiver
			}
			handled, err := handleGroupAppDataReaction(call, receiver, media)
			if err != nil {
				log.Warn().Err(err).Uint32("ssrc", media.Header.Ssrc).Uint16("seq", media.Header.SequenceNumber).Msg("invalid RTC app-data payload")
				e.c.diag.Emit("app_data", map[string]any{
					"event": "decode_failed", "ssrc": media.Header.Ssrc, "seq": media.Header.SequenceNumber,
					"error": err.Error(),
				})
				continue
			}
			e.c.diag.Emit("app_data", map[string]any{
				"event": "in", "ssrc": media.Header.Ssrc, "seq": media.Header.SequenceNumber,
				"ts": media.Header.Timestamp, "handled": handled,
				"participant_id": media.ParticipantID,
			})
			if handled {
				if appDataIn++; appDataIn == 1 {
					log.Info().Uint32("ssrc", media.Header.Ssrc).Msg("first RTC call reaction received")
				}
			}
			continue
		}
		// Demux H.264 (PT 97) to video and emit Annex-B access units on the marker bit.
		// Source of truth: https://github.com/JotaDev66/WaCalls/blob/2d6a1f666426049a89ef9541414e771acdcf8a16/internal/voip/call/callmanager_video.go#L86-L126
		if kind == mediaPayloadVideo {
			media, vunok := audioReceivers.UnprotectVideo(pkt)
			if !vunok {
				if videoUnprotectFail++; videoUnprotectFail == 1 {
					log.Warn().
						Uint32("ssrc", vh.Ssrc).
						Uint16("seq", vh.SequenceNumber).
						Msg("video RTP arrived but failed to unprotect")
				}
				e.c.diag.Emit("video", map[string]any{"event": "unprotect_failed", "ssrc": vh.Ssrc, "seq": vh.SequenceNumber})
				continue
			}
			vh = media.Header
			videoState := videoReceiveStates[media.receiver]
			if videoState == nil {
				videoState = &videoReceiveState{orientation: -1}
				videoReceiveStates[media.receiver] = videoState
			}
			videoReception.Observe(vh.Ssrc, vh.SequenceNumber, vh.Timestamp, uint64(time.Now().UnixMilli()), 90000)
			// wa-poc patch: what a real WhatsApp client puts in the fields we always send as 0.
			if x := vh.VideoExtension; x != nil {
				if now := time.Now(); extSamples < 8 || now.Sub(lastExtLog) > 10*time.Second {
					extSamples++
					lastExtLog = now
					fn := -1
					if x.FrameNumber != nil {
						fn = int(*x.FrameNumber)
					}
					log.Info().Uint16("seq", vh.SequenceNumber).Uint32("rtp_ts", vh.Timestamp).Bool("marker", vh.Marker).
						Str("frame_info", fmt.Sprintf("0x%02x", x.MediaFrameInfo)).Int("frame_number", fn).
						Uint16("initial_bandwidth", x.InitialBandwidth).Int16("short_offset", x.ShortOffset).
						Uint16("transport_seq", x.TransportSequence).Msg("peer video rtp ext")
				}
			}
			// wa-poc patch: say what a video packet carried when it had no readable extension.
			if vh.VideoExtension == nil && videoExtMissing < 6 {
				videoExtMissing++
				profile, extension, hasExtension := rtp.RtpExtensionProfileAndData(pkt)
				log.Info().Uint32("ssrc", vh.Ssrc).Uint16("seq", vh.SequenceNumber).Bool("has_extension", hasExtension).
					Str("profile", fmt.Sprintf("0x%04x", profile)).Str("extension_hex", hex.EncodeToString(extension)).
					Msg("peer video rtp without readable extension")
			}
			if vh.VideoExtension != nil {
				orientation := vh.VideoExtension.DisplayOrientation()
				if orientation != videoState.orientation {
					videoState.orientation = orientation
					if sink, ok := callVideoSink(call).(VideoOrientationSink); ok {
						sink.SetOrientation(orientation)
					}
					log.Debug().
						Int("orientation", orientation).
						Uint8("media_frame_info", vh.VideoExtension.MediaFrameInfo).
						Msg("updated peer video orientation from RTP")
					e.c.diag.Emit("video", map[string]any{
						"event": "orientation", "orientation": orientation,
						"media_frame_info": vh.VideoExtension.MediaFrameInfo,
					})
				}
			}
			if videoWirePacket < videoWirePacketLimit {
				headerLen, _ := rtp.RtpHeaderByteLength(pkt)
				_, extension, _ := rtp.RtpExtensionProfileAndData(pkt)
				e.c.diag.Emit("video_wire", map[string]any{
					"event": "packet", "direction": "in", "call_id": callID,
					"packet": videoWirePacket, "frame": videoWireFrame,
					"ssrc": vh.Ssrc, "seq": vh.SequenceNumber, "rtp_ts": vh.Timestamp,
					"marker": vh.Marker, "header_hex": hex.EncodeToString(pkt[:headerLen]),
					"extension_hex": hex.EncodeToString(extension),
					"payload_bytes": len(media.Payload), "protected_bytes": len(pkt),
					"participant_id": media.ParticipantID,
				})
			}
			videoWirePacket++
			// wa-poc patch: restore sequence order first; the assembler drops everything until
			// the next IDR on any out-of-order packet.
			videoState.packets++
			for _, op := range videoState.reorder.Push(vh.SequenceNumber, vh.Marker, media.Payload, time.Now()) {
				frame, complete, recoveryNeeded := videoState.assembler.Push(op.Seq, op.Marker, op.Payload)
				if recoveryNeeded && shouldSendVideoPLI(lastVideoPLI, vh.Ssrc, time.Now()) {
					packet, feedbackErr := videoRtcp.pictureLossIndication(vh.Ssrc)
					if feedbackErr == nil {
						_, feedbackErr = ch.Send(packet)
					}
					videoState.plis++
					if feedbackErr != nil {
						log.Warn().Err(feedbackErr).Uint32("ssrc", vh.Ssrc).Msg("failed to request video keyframe after RTP loss")
					}
				}
				if complete {
					videoState.frames++
					if videoWireFrame < videoWireFrameLimit {
						e.c.diag.Emit("video_wire", map[string]any{
							"event": "access_unit", "direction": "in", "call_id": callID,
							"frame": videoWireFrame, "ssrc": vh.Ssrc, "rtp_ts": vh.Timestamp,
							"idr": rtp.AUHasIDR(frame), "bytes": len(frame),
						})
					}
					videoWireFrame++
					e.c.diag.Emit("video", map[string]any{"event": "frame", "ssrc": vh.Ssrc, "bytes": len(frame)})
					deliveredParticipantFrame := false
					if call != nil {
						deliveredParticipantFrame = call.dispatchParticipantVideoFrame(ParticipantVideoFrame{
							ParticipantID: media.ParticipantID,
							Sender:        media.UserJID,
							Device:        media.DeviceJID,
							PID:           media.PID,
							HasPID:        media.HasPID,
							SSRC:          vh.Ssrc,
							Orientation:   videoState.orientation,
							AccessUnit:    frame,
						})
					}
					if sink := callVideoSink(call); sink != nil {
						if err := sink.WriteVideo(frame); err != nil {
							log.Warn().Err(err).Uint32("ssrc", vh.Ssrc).Int("bytes", len(frame)).Msg("failed to write WhatsApp video frame to sink")
						} else {
							if videoFrameIn == 0 {
								log.Info().Uint32("ssrc", vh.Ssrc).Int("bytes", len(frame)).Msg("first WhatsApp video frame written to sink")
							}
							videoFrameIn++
						}
					} else if !deliveredParticipantFrame {
						if videoSinkMissing == 0 {
							log.Warn().Uint32("ssrc", vh.Ssrc).Int("bytes", len(frame)).Msg("WhatsApp video frame arrived with no sink attached")
						}
						videoSinkMissing++
					}
				}
			}
			if now := time.Now(); now.Sub(videoState.lastStats) > 10*time.Second {
				videoState.lastStats = now
				log.Info().Uint32("ssrc", vh.Ssrc).
					Uint64("packets", videoState.packets).Uint64("frames", videoState.frames).
					Uint64("held", videoState.reorder.Held).Uint64("late", videoState.reorder.Late).
					Uint64("lost", videoState.reorder.Skipped).Uint64("pli_sent", videoState.plis).
					Msg("video rx stats")
			}
			if vidIn++; vidIn == 1 {
				log.Info().Uint32("ssrc", vh.Ssrc).Msg("first video RTP demuxed from relay (NOT VALIDATED)")
				e.c.diag.Emit("meta", map[string]any{"event": "first_video_rtp_in", "call_id": callID, "ssrc": vh.Ssrc})
			}
			continue
		}
		if kind != mediaPayloadAudio {
			log.Debug().Uint8("payload_type", vh.PayloadType).Uint32("ssrc", vh.Ssrc).Msg("dropping unknown RTP payload")
			e.c.diag.Emit("rtp", map[string]any{
				"event": "unknown_payload", "pt": vh.PayloadType, "ssrc": vh.Ssrc,
				"seq": vh.SequenceNumber, "ts": vh.Timestamp,
			})
			continue
		}
		audio, ok := audioReceivers.DecodeAudio(pkt)
		if census.active() && ok {
			census.audio++
		}
		if !ok {
			if unprotectFail++; unprotectFail == 1 {
				log.Warn().Uint32("ssrc", vh.Ssrc).Int("bytes", n).Msg("audio RTP did not match an authenticated active participant")
			}
			e.c.diag.Emit("srtp", map[string]any{"event": "unprotect_failed", "ssrc": vh.Ssrc, "bytes": n})
			continue
		}
		audioReception.Observe(audio.SSRC, vh.SequenceNumber, audio.Timestamp, uint64(time.Now().UnixMilli()), SampleRate)
		e.c.diag.Emit("rtp", map[string]any{
			"event": "in", "ssrc": audio.SSRC, "seq": vh.SequenceNumber,
			"ts": audio.Timestamp, "pt": vh.PayloadType, "marker": vh.Marker,
			"participant_id": audio.ParticipantID, "pid": audio.PID, "has_pid": audio.HasPID,
		})
		e.c.diag.Emit("srtp", map[string]any{
			"event": "frame_authenticated", "ssrc": audio.SSRC, "seq": vh.SequenceNumber,
			"participant_id": audio.ParticipantID, "pid": audio.PID, "has_pid": audio.HasPID,
		})
		e.c.diag.Emit("media_in", map[string]any{
			"seq": vh.SequenceNumber, "samples": len(audio.PCM),
			"pcm_rms": rmsFloat32(audio.PCM), "participant_id": audio.ParticipantID,
		})
		mixedMode := groupMixing.Load()
		playoutLocked := false
		if !mixedMode {
			audioPlayoutMu.Lock()
			playoutLocked = true
			mixedMode = groupMixing.Load()
		}
		if mixedMode {
			audioMixer.Add(audio.ParticipantID, audio.PCM)
		} else {
			_, sink := callPlayerSink(call)
			playoutStarted, playoutErr := audioPlayout.Push(audio.Timestamp, audio.PCM, sink)
			if playoutErr != nil {
				log.Warn().Err(playoutErr).Msg("failed to write timestamp-aligned WhatsApp audio")
			}
			if playoutStarted {
				log.Info().Int("prefill_ms", audioPlayoutPrefillSamples*1000/SampleRate).Msg("started timestamp-aligned inbound audio playout")
			}
		}
		if playoutLocked {
			audioPlayoutMu.Unlock()
		}
		if rtpIn++; rtpIn == 1 {
			log.Info().Msg("first RTP decoded from relay, inbound audio flowing")
			e.c.diag.Emit("meta", map[string]any{"event": "first_rtp_in", "call_id": callID})
			if call != nil {
				call.setPhase(CallPhaseActive)
				if fn := call.onReadyFn(); fn != nil {
					fn()
				}
			}
		}
	}
}

func prepareWasmRelayStreamSSRCs(
	streamSSRCs [9]uint32,
	appDataSSRC uint32,
	random io.Reader,
) ([9]uint32, error) {
	// Source of truth: https://github.com/purpshell/meowcaller/blob/0911f20e97b858506a55ee6aa4f6a1ad73f19798/datasheets/group-video-reactions.md#L51-L65
	if random == nil {
		return [9]uint32{}, fmt.Errorf("meowcaller: relay stream SSRC random source is nil")
	}
	used := make(map[uint32]struct{}, len(streamSSRCs)+1)
	for _, ssrc := range streamSSRCs[:6] {
		if ssrc != 0 {
			used[ssrc] = struct{}{}
		}
	}
	if appDataSSRC != 0 {
		used[appDataSSRC] = struct{}{}
	}
	for i := 6; i < len(streamSSRCs); i++ {
		var selected bool
		for range 64 {
			var encoded [4]byte
			if _, err := io.ReadFull(random, encoded[:]); err != nil {
				return [9]uint32{}, fmt.Errorf("meowcaller: generate auxiliary video SSRC: %w", err)
			}
			candidate := binary.LittleEndian.Uint32(encoded[:])
			if candidate == 0 {
				continue
			}
			if _, exists := used[candidate]; exists {
				continue
			}
			streamSSRCs[i] = candidate
			used[candidate] = struct{}{}
			selected = true
			break
		}
		if !selected {
			return [9]uint32{}, fmt.Errorf("meowcaller: generate unique auxiliary video SSRC")
		}
	}
	return streamSSRCs, nil
}

func sendMediaSrtcpReceptionReports(
	sender *mediaSrtcpSender,
	stats rtp.RtcpSenderStats,
	nowMs uint64,
	reports []*rtp.RtcpReceptionReport,
	groupMedia bool,
	send func([]byte) error,
) (int, error) {
	// Source of truth: https://github.com/purpshell/meowcaller/blob/bab582d4e799292478ccba2f8a86f2164d4737c3/datasheets/group-media-rtcp-feedback.md#L148-L156
	if sender == nil || send == nil {
		return 0, fmt.Errorf("meowcaller: SRTCP sender or send callback is nil")
	}
	if !groupMedia || len(reports) == 0 {
		var report *rtp.RtcpReceptionReport
		if len(reports) > 0 {
			report = reports[0]
		}
		packet, err := sender.senderReport(stats, nowMs, report)
		if err != nil {
			return 0, err
		}
		return 1, send(packet)
	}
	sent := 0
	for _, report := range reports {
		packet, err := sender.groupSenderReport(stats, nowMs, report)
		if err != nil {
			return sent, err
		}
		if err = send(packet); err != nil {
			return sent, err
		}
		sent++
	}
	return sent, nil
}

func handleGroupAppDataReaction(
	call *Call,
	receiver *appDataReceiver,
	media unprotectedParticipantMedia,
) (bool, error) {
	// Source of truth: https://github.com/purpshell/meowcaller/blob/36d54857c74e45ccb08f6444a32d2afa13f20be9/datasheets/group-video-reactions.md#L10-L19
	reaction, ok, err := receiver.receive(media.Payload)
	if err != nil || !ok || call == nil {
		return false, err
	}
	if fn := call.onReactionFn(); fn != nil {
		fn(CallReaction{
			Emoji: reaction.emoji, ParticipantID: media.ParticipantID,
			Sender: media.UserJID, Device: media.DeviceJID,
			PID: media.PID, HasPID: media.HasPID,
			Removed: reaction.emoji == "",
		})
	}
	return true, nil
}

// callPlayerSink returns a Call's current Player and sink, tolerating a nil Call (an
// outbound call may never have had one attached).
func callPlayerSink(call *Call) (*Player, AudioSink) {
	if call == nil {
		return nil, nil
	}
	return call.playerAndSink()
}

// callVideoSink returns a Call's inbound-video sink, tolerating a nil Call.
func callVideoSink(call *Call) VideoSink {
	if call == nil {
		return nil
	}
	return call.videoSinkRef()
}

const defaultVideoRtpStepSamples = 90000 / 30

const (
	videoWirePacketLimit = 120
	videoWireFrameLimit  = 30
)

func videoRtpDurationSamples(duration time.Duration) uint32 {
	if duration <= 0 {
		return defaultVideoRtpStepSamples
	}
	samples := uint32((duration.Nanoseconds()*90000 + int64(time.Second)/2) / int64(time.Second))
	if samples == 0 {
		return defaultVideoRtpStepSamples
	}
	return samples
}

// videoSender packetizes encoded H.264 access units (Annex-B) into PT-97 RTP, E2E-SRTP
// protects them with the video pipeline, and sends them to the relay. The send path is
// fed encoded H.264 (e.g. from the VideoBridge / WebCodecs), not raw frames.
//
// NOT VALIDATED: the video send media path is unproven.
type videoSender struct {
	mu               sync.Mutex
	pipe             *MediaPipeline
	stream           *rtp.VideoRtpStream
	ch               relaySender
	ssrc             uint32
	callID           string
	frame            uint64
	logged           bool
	active           bool
	sendGated        bool
	keyframeRequired bool
	orientation      uint8 // wa-poc patch: CVO quarter turns (0..3) for outgoing frames
	log              zerolog.Logger
	diag             *diag.Recorder
}

type mediaSrtcpSender struct {
	mu      sync.Mutex
	keys    srtp.E2eSrtpKeys
	ssrc    uint32
	cname   [rtp.WhatsappRtcpCnameLen]byte
	profile bool
	index   uint32
}

type mediaSrtcpReceiver struct {
	mu   sync.Mutex
	keys srtp.E2eSrtpKeys
}

func newMediaSrtcpReceiver(callKey []byte, peerLID string) (*mediaSrtcpReceiver, error) {
	keys, err := srtp.DeriveE2eSrtcpKeys(callKey, rtp.FormatE2ESrtpParticipantID(peerLID))
	if err != nil {
		return nil, err
	}
	return &mediaSrtcpReceiver{keys: keys}, nil
}

func (r *mediaSrtcpReceiver) rekey(callKey []byte, peerLID string) error {
	keys, err := srtp.DeriveE2eSrtcpKeys(callKey, rtp.FormatE2ESrtpParticipantID(peerLID))
	if err != nil {
		return err
	}
	r.installKeys(keys)
	return nil
}

func (r *mediaSrtcpReceiver) installKeys(keys srtp.E2eSrtpKeys) {
	// Source of truth: https://github.com/oxidezap/whatsapp-rust/blob/41095d4e6ba4610e054e9ede3af1d5e88a83faee/wacore/src/voip/e2e_srtp.rs#L34-L70
	r.mu.Lock()
	r.keys = keys
	r.mu.Unlock()
}

func (r *mediaSrtcpReceiver) unprotect(senderSSRC uint32, packet []byte) ([]byte, uint32, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return srtp.UnprotectSrtcp(&r.keys, senderSSRC, packet)
}

func newMediaSrtcpSender(callKey []byte, selfLID string, ssrc uint32, profile bool) (*mediaSrtcpSender, error) {
	keys, err := srtp.DeriveE2eSrtcpKeys(callKey, rtp.FormatE2ESrtpParticipantID(selfLID))
	if err != nil {
		return nil, err
	}
	var entropy [12]byte
	_, _ = rand.Read(entropy[:])
	return &mediaSrtcpSender{
		keys: keys, ssrc: ssrc, cname: rtp.BuildWhatsappRtcpCname(entropy),
		profile: profile, index: 1,
	}, nil
}

func (s *mediaSrtcpSender) installKeys(keys srtp.E2eSrtpKeys) {
	// Source of truth: https://github.com/oxidezap/whatsapp-rust/blob/41095d4e6ba4610e054e9ede3af1d5e88a83faee/wacore/src/voip/e2e_srtp.rs#L34-L70
	s.mu.Lock()
	s.keys = keys
	s.mu.Unlock()
}

func (s *mediaSrtcpSender) senderReport(stats rtp.RtcpSenderStats, nowMs uint64, report *rtp.RtcpReceptionReport) ([]byte, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	plain := rtp.BuildSenderReportWithSdesAndReception(s.ssrc, &stats, nowMs, &s.cname, report, s.profile)
	packet, err := srtp.ProtectSrtcp(&s.keys, s.ssrc, s.index, plain)
	if err == nil {
		s.index++
	}
	return packet, err
}

func (s *mediaSrtcpSender) groupSenderReport(stats rtp.RtcpSenderStats, nowMs uint64, report *rtp.RtcpReceptionReport) ([]byte, error) {
	// Source of truth: https://github.com/purpshell/meowcaller/blob/6e202a6d6ec5a9384bae6ccbe621966edeee6592/datasheets/group-media-rtcp-feedback.md#L53-L75
	s.mu.Lock()
	defer s.mu.Unlock()
	plain := rtp.BuildGroupSenderReport(s.ssrc, &stats, nowMs, report, rtp.RTCPGroupReportExtension{})
	packet, err := srtp.ProtectSrtcp(&s.keys, s.ssrc, s.index, plain)
	if err == nil {
		s.index++
	}
	return packet, err
}

func shouldSendVideoPLI(lastSent map[uint32]time.Time, mediaSSRC uint32, now time.Time) bool {
	// Source of truth: https://github.com/oxidezap/whatsapp-rust/blob/41095d4e6ba4610e054e9ede3af1d5e88a83faee/wacore/src/voip/e2e_srtp.rs#L34-L70
	if lastSent == nil {
		return false
	}
	if previous, ok := lastSent[mediaSSRC]; ok && now.Sub(previous) < videoPLIInterval {
		return false
	}
	lastSent[mediaSSRC] = now
	return true
}

func (s *mediaSrtcpSender) pictureLossIndication(mediaSSRC uint32) ([]byte, error) {
	// Source of truth: https://github.com/oxidezap/whatsapp-rust/blob/41095d4e6ba4610e054e9ede3af1d5e88a83faee/wacore/src/voip/e2e_srtp.rs#L34-L70
	s.mu.Lock()
	defer s.mu.Unlock()
	plain := rtp.BuildPictureLossIndication(s.ssrc, mediaSSRC, s.profile)
	packet, err := srtp.ProtectSrtcp(&s.keys, s.ssrc, s.index, plain[:])
	if err == nil {
		s.index++
	}
	return packet, err
}

func (vs *videoSender) protectAccessUnit(au []byte, duration time.Duration) [][]byte {
	vs.mu.Lock()
	defer vs.mu.Unlock()
	return vs.protectAccessUnitLocked(au, duration)
}

func (vs *videoSender) protectAccessUnitLocked(au []byte, duration time.Duration) [][]byte {
	if !vs.active || vs.sendGated {
		return nil
	}
	idr := rtp.AUHasIDR(au)
	if vs.keyframeRequired && !idr {
		return nil
	}
	nalus := rtp.SplitAnnexB(au)
	var packedAccessUnit []byte
	for _, n := range nalus {
		if len(n) == 0 || n[0]&0x1f == 9 {
			continue
		}
		if len(packedAccessUnit) > 0 {
			packedAccessUnit = append(packedAccessUnit, 0, 0, 0, 1)
		}
		packedAccessUnit = append(packedAccessUnit, n...)
	}
	if len(packedAccessUnit) == 0 {
		return nil
	}
	// WhatsApp fragments the complete Annex-B access unit as one RTP NAL unit.
	payloads := rtp.PackageH264NALU(packedAccessUnit)
	captureWire := vs.frame < videoWireFrameLimit
	if captureWire {
		vs.diag.Emit("video_wire", map[string]any{
			"event": "access_unit", "direction": "out", "call_id": vs.callID,
			"frame": vs.frame, "ssrc": vs.ssrc, "idr": idr, "bytes": len(au),
			"duration_ms": duration.Milliseconds(),
		})
	}
	vs.stream.SetTimestampStride(videoRtpDurationSamples(duration))
	mediaFrameInfo := rtp.VideoMediaFrameInfoDelta
	if idr {
		mediaFrameInfo = rtp.VideoMediaFrameInfoIDR
	}
	// wa-poc patch: the low two bits carry CVO rotation (see VideoRtpExtension.DisplayOrientation).
	mediaFrameInfo |= vs.orientation & 0x03
	packets := make([][]byte, 0, len(payloads))
	for i, payload := range payloads {
		header := vs.stream.NextPacket(i == len(payloads)-1, mediaFrameInfo)
		packet, err := vs.pipe.ProtectRTP(&header, payload)
		if err == nil {
			packets = append(packets, packet)
			if captureWire {
				headerBytes := rtp.EncodeRtpHeader(&header)
				_, extension, _ := rtp.RtpExtensionProfileAndData(headerBytes)
				vs.diag.Emit("video_wire", map[string]any{
					"event": "packet", "direction": "out", "call_id": vs.callID,
					"frame": vs.frame, "packet": i, "ssrc": header.Ssrc,
					"seq": header.SequenceNumber, "rtp_ts": header.Timestamp, "marker": header.Marker,
					"header_hex":    hex.EncodeToString(headerBytes),
					"extension_hex": hex.EncodeToString(extension),
					"payload_bytes": len(payload), "protected_bytes": len(packet),
				})
			}
		}
	}
	if len(packets) > 0 && idr {
		vs.keyframeRequired = false
	}
	return packets
}

func (vs *videoSender) enable(sendGated bool) {
	vs.mu.Lock()
	needsRecovery := !vs.active || (vs.sendGated && !sendGated)
	vs.active = true
	vs.sendGated = sendGated
	vs.keyframeRequired = vs.keyframeRequired || needsRecovery
	vs.mu.Unlock()
}

func (vs *videoSender) disable() {
	vs.mu.Lock()
	vs.active = false
	vs.sendGated = false
	vs.keyframeRequired = true
	vs.mu.Unlock()
}

// setOrientation sets the CVO rotation stamped on outgoing frames (wa-poc patch).
func (vs *videoSender) setOrientation(quarterTurns int) {
	vs.mu.Lock()
	vs.orientation = uint8(quarterTurns & 0x03)
	vs.mu.Unlock()
}

func (vs *videoSender) requestKeyframe() {
	vs.mu.Lock()
	vs.keyframeRequired = true
	vs.mu.Unlock()
}

// send fragments one Annex-B access unit into PT-97 RTP packets (marker on the last) and
// sends them to the relay.
func (vs *videoSender) send(au []byte, duration time.Duration) {
	if vs == nil || len(au) == 0 {
		return
	}
	vs.mu.Lock()
	defer vs.mu.Unlock()
	if vs.ch == nil {
		return
	}
	packets := vs.protectAccessUnitLocked(au, duration)
	if len(packets) == 0 {
		return
	}
	sent := 0
	wireBytes := 0
	// wa-poc patch: pace large frames (keyframes) instead of bursting every packet into the
	// relay at once; spread over at most ~25 ms, well inside one 66 ms frame interval.
	var gap time.Duration
	if len(packets) > 6 {
		gap = 25 * time.Millisecond / time.Duration(len(packets))
		if gap > 2*time.Millisecond {
			gap = 2 * time.Millisecond
		}
	}
	for i, pkt := range packets {
		if _, err := vs.ch.Send(pkt); err != nil {
			return
		}
		sent++
		wireBytes += len(pkt)
		if gap > 0 && i < len(packets)-1 {
			time.Sleep(gap)
		}
	}
	frame := vs.frame
	if sent > 0 {
		vs.frame++
		if frame < 10 || frame%30 == 0 {
			vs.diag.Emit("video_out", map[string]any{
				"event": "frame", "call_id": vs.callID, "frame": frame,
				"ssrc": vs.ssrc, "rtp_ts": vs.pipe.SenderStats().RtpTimestamp,
				"packets": sent, "access_unit_bytes": len(au),
				"wire_bytes": wireBytes, "duration_ms": duration.Milliseconds(),
				"duration_samples": videoRtpDurationSamples(duration),
			})
		}
	}
	if sent > 0 && !vs.logged {
		vs.logged = true
		vs.log.Info().
			Int("packets", sent).
			Uint32("ssrc", vs.ssrc).
			Msg("first video RTP sent to relay, outbound video flowing")
	}
}

// rmsFloat32 returns the root-mean-square level of a PCM frame, a cheap loudness
// metric for the media diagnostic streams (avoids inlining raw float32 PCM).
func rmsFloat32(f []float32) float64 {
	if len(f) == 0 {
		return 0
	}
	var sum float64
	for _, s := range f {
		sum += float64(s) * float64(s)
	}
	return math.Sqrt(sum / float64(len(f)))
}
