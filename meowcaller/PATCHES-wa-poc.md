# Local patches to meowcaller (wa-poc)

1. **Outgoing video orientation (CVO).** `setVideoOrientation` now stores the orientation and
   stamps it into the low two bits of `MediaFrameInfo` on every outgoing video RTP packet
   (`engine.go`, `engine_media.go`, marked `wa-poc patch`). Upstream only sent it in the
   `<video>` stanza, and only once video was active; receivers rotate frames by the RTP CVO,
   so phone-camera (landscape sensor) video arrived sideways. Candidate for upstreaming.

2. **Inbound video reorder buffer + stats.** `rtp/reorder.go` (`SeqReorderBuffer`) puts video
   RTP packets back in sequence (holds up to 24 packets / 80 ms) before
   `H264AccessUnitAssembler`, which otherwise treats any out-of-order packet as loss and drops
   every frame until the next IDR. Logs `video rx stats` (packets, frames, held, late, lost,
   pli_sent) every 10 s. In testing before this patch only ~15 of ~1,800 inbound frames
   survived. Candidate for upstreaming.

3. **Outgoing video pacing + feedback logging.** `videoSender.send` spreads the packets of
   large frames over up to ~25 ms (max 2 ms apart) instead of bursting them into the relay.
   `rtp/rtcp_feedback.go` (`FindReportBlock`) reads the peer's RTCP report block about our
   video SSRC; logged every 10 s as `video tx feedback from peer` (loss %, cumulative loss,
   jitter ms, keyframe requests). Upstream reads no receiver feedback at all, so there is no
   congestion control yet.

4. **Diagnostics for the receive-side lag.** `rtp/rtcp_census.go` counts every RTCP type the
   peer sends (logged as `peer rtcp census`, incl. NACKed sequence numbers), and the first
   packets plus one every 10 s of the peer's video RTP extension are logged as
   `peer video rtp ext` (frame info, initial bandwidth, short offset, transport seq) to
   compare with what we send (we send 0 for initial bandwidth and short offset). Reorder
   window widened to 200 ms / 96 packets after "late" packets grew at 80 ms.

5. **Group video accept and group ID.** `BuildActiveGroupAccept` takes a `video` flag and, for a
   video call, adds `<video dec=H264 device_orientation=0>` after `<audio>`, matching what
   WhatsApp Web sends when it answers a group video call (`engine.go` passes the call's video
   state). Upstream sent an audio-only accept, which the server refused with call ack error
   431. `GroupCallState` gained `GroupJID`, and `ParseGroupInviteSnapshot` also reads
   `group-jid` from `<offer>` when `<group_info>` lacks it. Candidate for upstreaming
   (upstream issue #34).

6. **Who a video state is from.** `VideoState` gained `Sender`, the `from` of the `<call>` that
   carried the `<video>` (`onVideoStanzaFrom` in `engine.go`). In a group call each participant
   sends their own `<video state device_orientation>`, and their video packets carry no
   orientation extension, so the app needs the sender to rotate each participant's video.

7. **Tolerant video RTP extension parsing.** `ParseWhatsappVideoExtension` (`rtp/rtp.go`) now
   stops at element ID 15 (RFC 8285 §4.2) instead of rejecting the whole extension. It also no
   longer requires the short offset, only the frame info that carries the orientation. In a
   group video call with two remote senders, the second sender's orientation was never read,
   so their video was drawn rotated. `engine_media.go` logs the first few video packets whose
   extension still can't be read (`peer video rtp without readable extension`, with the raw
   extension bytes, which are unencrypted header metadata). Patch 6's note that group video
   packets carry no orientation was wrong: they do, and it accounts for the sender's camera,
   which the `<video>` stanza's orientation doesn't.

8. **Group media start race and the first keyframe.** `runMedia` (`engine_media.go`) waits up to
   5 s for the group update that carries the relay snapshot. The receive roster is applied
   before that update is stored, so media could start in between and end immediately ("group
   media started without an authoritative relay snapshot"), leaving a group call with no media
   at all. Once the video sender is installed, the call also asks for a keyframe
   (`requestVideoKeyframe`): frames sent before media was up, including the camera's first
   IDR, are dropped, and the sender waits for an IDR, so without the request our video stayed
   blank to the others until the encoder's next scheduled keyframe or a peer's PLI.

9. **Duplicate and late audio frames.** `audioPlayoutBuffer.Push` (`audio_playout.go`) reset
   playout on any frame whose timestamp wasn't ahead of the last one, so every repeated or
   reordered packet restarted playback with a fresh prefill, an audible gap. Such frames (same or
   earlier timestamp, within the 2 s gap limit) are now dropped and counted (`Dropped`, logged at
   the end of a call's media); only a jump of more than 2 s either way resets.

10. **Reconnecting a call after a network dropout (experimental).** When the relay link failed
   mid-call (the relay aborts the association after a few seconds of silence, "Too many
   retransmissions"), `runMedia` (`engine_media.go`) returned and the call stayed open with no
   media; the other phone showed "Reconnecting…" until someone hung up. Now:
   - Media goes through a `relayLink` whose channel can be replaced while the pipelines and
     their SRTP state carry on. A watchdog treats 6 s without anything from the relay (which
     answers the 1 Hz keepalive) as a lost link, and `Client.NetworkChanged` drops the link at
     once when the phone switches networks.
   - The receive loop reconnects with backoff for up to 30 s (a hung DTLS handshake is given
     4 s), trying the call's usual relay first, then the allocation's others
     (`reconnectEndpoint`). The fresh allocate subscribes to the peer's participant, as a group
     call's does. Once back, both sides' video are asked for a keyframe.
   - A reconnected link can be accepted yet carry none of the peer's media. With nothing from
     the peer 3 s after reconnecting, it is dialled again, up to 8 times in a row.
   - The peer's `<transport transport-message-type=3>` checks are answered with type 9 at once,
     and again once the link is back; if the peer goes quiet after a reconnect we send it a
     type 3 check of our own.
   - While reconnecting, the call is in the new `CallPhaseReconnecting`. If the link can't be
     restored, or media fails to start, a 1:1 call is hung up; a terminate that can't be sent
     is retried for 30 s.

   Dropouts on the same network recover reliably. After a switch between networks the relay
   bridges a new link only some of the time. Tried and ruled out: moving the DTLS session to
   the new address (the relay answers ICE checks there, with the base64 auth token as ufrag
   and the relay key as password, but keeps sending to the first address, and takes up no DTLS
   connection IDs); the Android app's allocate format (the peer's media then arrives on the new
   link every time, but ours never reaches the peer, with or without a hop-by-hop SRTP layer
   from the 1:1 relay's `hbh_key`).

11. **Choosing the relay for an incoming call.** An incoming call has to join the relay the
   caller sends through, or the relay never bridges the caller's media. The call then stays on
   connecting until the caller gives up. `getMediaRelayEndpoint` (`engine.go`) finds that relay
   by its `is_fna` mark, but some offers mark none.
   - In that case it now picks the endpoint without an auth token, the opposite of the
     outbound rule. The caller's reported latencies were tried first and picked the wrong
     relay.
   - If none of the caller's media arrives within 2.5 s of joining a relay, `runMedia`
     (`engine_media.go`) moves to the call's next relay, up to four times.
   - Reconnects after a dropout start from the relay that last carried the peer's media
     (`reconnectEndpointFrom`), not from the first pick.
   - The relay mark still wins whenever an offer has one.
