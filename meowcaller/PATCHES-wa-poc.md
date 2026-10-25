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
