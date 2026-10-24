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
