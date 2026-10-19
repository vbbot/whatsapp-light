// Package walib is a gomobile-friendly wrapper around hypermeow (whatsmeow fork)
// and meowcaller for a single linked-device WhatsApp session with one active call.
//
// Audio contract (both directions): 16 kHz, mono, signed 16-bit little-endian PCM
// in []byte. meowcaller works in 60 ms frames (960 samples = 1920 bytes); PushMic
// accepts any chunk size and re-frames internally.
package walib

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	whatsmeow "github.com/polymorfa/hypermeow"
	"github.com/polymorfa/hypermeow/proto/waCompanionReg"
	"github.com/polymorfa/hypermeow/proto/waE2E"
	"github.com/polymorfa/hypermeow/store"
	"github.com/polymorfa/hypermeow/store/sqlstore"
	"github.com/polymorfa/hypermeow/types"
	"github.com/polymorfa/hypermeow/types/events"
	waLog "github.com/polymorfa/hypermeow/util/log"
	meowcaller "github.com/purpshell/meowcaller"
	"github.com/rs/zerolog"
	"google.golang.org/protobuf/proto"

	_ "modernc.org/sqlite"
)

// Listener receives events. Implemented in Kotlin; called from Go goroutines.
type Listener interface {
	OnQR(code string)
	OnConnected(jid string)
	OnDisconnected()
	OnIncomingCall(callID, peer string, video bool)
	OnCallReady(callID string)
	OnCallState(callID string, phase int)
	OnCallEnded(callID, reason string)
	OnLog(line string)
	// OnMessage reports a text message (incoming, or sent from another device). Non-text
	// messages arrive with a placeholder body such as "[image]". timestampMs is Unix ms.
	// mediaPath is a local file for photo/video messages (the embedded thumbnail at first; see
	// OnMediaReady), or "".
	OnMessage(chat, sender, senderName, id, text string, timestampMs int64, fromMe, isGroup bool, mediaPath string)
	// OnMediaReady reports that a received photo finished downloading to path (full size).
	OnMediaReady(id, path string)
	// OnReceipt reports message receipts. ids is comma-separated. kind is one of
	// "delivered", "read", "played" (from the other party, about our messages) or
	// "read-self" (we read their messages on another of our devices).
	OnReceipt(chat, ids, kind string, timestampMs int64)
	// OnVideoState reports the peer's camera state: active (camera on), upgrade (they ask to
	// switch an audio call to video), orientation (rotate their frames by orientation x 90°
	// clockwise to show them upright), raw (the protocol state value).
	OnVideoState(callID string, active, upgrade bool, orientation, raw int)
	// OnKeyframeRequest asks the encoder to make its next frame an IDR (peer lost sync or
	// just accepted the call).
	OnKeyframeRequest(callID string)
	// OnRemoteOrientation reports the orientation carried in the peer's video packets:
	// clockwise quarter turns to draw their frames upright. Authoritative for rendering.
	OnRemoteOrientation(callID string, quarterTurns int)
	// OnHistory delivers a batch of past messages, chat names and calls from the main phone
	// (at link time, or answering RequestOlderMessages) as JSON; see historyBatch.
	OnHistory(json string)
}

// VideoRenderer receives the peer's video as H.264 Annex-B access units. Implemented in
// Kotlin (MediaCodec decoder); called from Go goroutines.
type VideoRenderer interface {
	Frame(accessUnit []byte)
}

// Speaker plays peer audio (16 kHz mono s16le). Implemented in Kotlin (AudioTrack).
type Speaker interface {
	Write(pcm []byte)
}

type Client struct {
	l  Listener
	wa *whatsmeow.Client
	mc *meowcaller.Client

	mu       sync.Mutex
	call     *meowcaller.Call
	mic      *micSource
	speaker  Speaker
	renderer VideoRenderer
	mediaDir string
	cancel   context.CancelFunc
}

// NewClient opens (or creates) the session database at dbPath. Nothing connects yet.
func NewClient(dbPath string, l Listener) (*Client, error) {
	logger := zerolog.New(zerolog.ConsoleWriter{Out: logWriter{l}, NoColor: true, TimeFormat: "15:04:05.000"}).
		Level(zerolog.InfoLevel).With().Timestamp().Logger()
	ctx := logger.WithContext(context.Background())

	store.DeviceProps.Os = proto.String("Android")
	store.DeviceProps.PlatformType = waCompanionReg.DeviceProps_CHROME.Enum()
	// Ask for all history when linking, not just recent months. Only read at pairing.
	store.DeviceProps.RequireFullSync = proto.Bool(true)

	wl := waLog.Zerolog(logger)
	dsn := fmt.Sprintf("file:%s?_pragma=foreign_keys(1)&_pragma=busy_timeout(5000)&_pragma=temp_store(2)", dbPath)
	container, err := sqlstore.New(ctx, "sqlite", dsn, wl.Sub("db"))
	if err != nil {
		return nil, fmt.Errorf("open store: %w", err)
	}
	device, err := container.GetFirstDevice(ctx)
	if err != nil {
		return nil, fmt.Errorf("load device: %w", err)
	}
	wa := whatsmeow.NewClient(device, wl.Sub("wa"))
	c := &Client{l: l, wa: wa}
	// meowcaller must be created before Connect so its call handlers are installed first.
	c.mc = meowcaller.NewClient(wa, meowcaller.WithLogger(logger))
	c.mc.OnIncomingCall(c.onIncoming)
	wa.AddEventHandler(func(evt any) {
		switch e := evt.(type) {
		case *events.Disconnected:
			l.OnDisconnected()
		case *events.Message:
			c.handleMessage(e)
		case *events.Receipt:
			c.handleReceipt(e)
		case *events.HistorySync:
			c.handleHistory(e)
		}
	})
	return c, nil
}

func (c *Client) handleReceipt(r *events.Receipt) {
	var kind string
	switch r.Type {
	case types.ReceiptTypeDelivered:
		kind = "delivered"
	case types.ReceiptTypeRead:
		kind = "read"
	case types.ReceiptTypePlayed:
		kind = "played"
	case types.ReceiptTypeReadSelf, types.ReceiptTypePlayedSelf:
		kind = "read-self"
	default:
		return // sender/retry/server-error/etc. are protocol plumbing
	}
	ids := make([]string, len(r.MessageIDs))
	for i, id := range r.MessageIDs {
		ids[i] = string(id)
	}
	c.l.OnReceipt(r.Chat.ToNonAD().String(), strings.Join(ids, ","), kind, r.Timestamp.UnixMilli())
}

// MarkRead sends read receipts ("blue ticks") for messages in chat. ids is comma-separated;
// sender is the author of those messages (the chat itself for 1:1 chats).
func (c *Client) MarkRead(chat, sender, ids string) error {
	chatJID, err := types.ParseJID(chat)
	if err != nil {
		return err
	}
	senderJID, err := types.ParseJID(sender)
	if err != nil {
		return err
	}
	var list []types.MessageID
	for _, id := range strings.Split(ids, ",") {
		if id != "" {
			list = append(list, types.MessageID(id))
		}
	}
	return c.wa.MarkRead(context.Background(), list, time.Now(), chatJID, senderJID)
}

func (c *Client) handleMessage(m *events.Message) {
	if m.Message == nil || m.Info.Chat.Server == types.BroadcastServer {
		return
	}
	text := messageText(m.Message)
	if text == "" {
		return
	}
	mediaPath := c.saveMedia(m, true)
	c.l.OnMessage(m.Info.Chat.String(), m.Info.Sender.String(), m.Info.PushName, m.Info.ID,
		text, m.Info.Timestamp.UnixMilli(), m.Info.IsFromMe, m.Info.IsGroup, mediaPath)
}

// messageText is the body shown for a message: its text, or a placeholder such as "[image]"
// for media. "" for protocol, reaction and system messages, which aren't shown.
func messageText(msg *waE2E.Message) string {
	text := msg.GetConversation()
	if text == "" {
		text = msg.GetExtendedTextMessage().GetText()
	}
	if text == "" {
		switch {
		case msg.GetImageMessage() != nil:
			text = "[image] " + msg.GetImageMessage().GetCaption()
		case msg.GetVideoMessage() != nil:
			text = "[video] " + msg.GetVideoMessage().GetCaption()
		case msg.GetAudioMessage() != nil:
			text = "[voice message]"
		case msg.GetDocumentMessage() != nil:
			text = "[document]"
		case msg.GetStickerMessage() != nil:
			text = "[sticker]"
		}
	}
	return text
}

// SetMediaDir sets where received photos and thumbnails are stored. Without it, media
// messages arrive as text placeholders only.
func (c *Client) SetMediaDir(dir string) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return
	}
	c.mu.Lock()
	c.mediaDir = dir
	c.mu.Unlock()
}

// saveMedia writes the embedded thumbnail of a photo/video message right away (returned path)
// and, for photos with full set, downloads the full image in the background (reported via
// OnMediaReady). Videos keep the thumbnail only for now.
func (c *Client) saveMedia(m *events.Message, full bool) string {
	c.mu.Lock()
	dir := c.mediaDir
	c.mu.Unlock()
	if dir == "" {
		return ""
	}
	id := safeFileName(m.Info.ID)
	var thumb []byte
	img := m.Message.GetImageMessage()
	vid := m.Message.GetVideoMessage()
	switch {
	case img != nil:
		thumb = img.GetJPEGThumbnail()
	case vid != nil:
		thumb = vid.GetJPEGThumbnail()
	default:
		return ""
	}
	path := ""
	if len(thumb) > 0 {
		path = filepath.Join(dir, id+"_t.jpg")
		if err := os.WriteFile(path, thumb, 0o600); err != nil {
			path = ""
		}
	}
	if img != nil && full {
		go func() {
			data, err := c.wa.Download(context.Background(), img)
			if err != nil {
				c.l.OnLog("media: download failed: " + err.Error())
				return
			}
			full := filepath.Join(dir, id+".jpg")
			if err := os.WriteFile(full, data, 0o600); err != nil {
				c.l.OnLog("media: save failed: " + err.Error())
				return
			}
			c.l.OnMediaReady(m.Info.ID, full)
		}()
	}
	return path
}

func safeFileName(s string) string {
	b := []byte(s)
	for i, ch := range b {
		if !(ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9' || ch == '-' || ch == '_') {
			b[i] = '_'
		}
	}
	return string(b)
}

// SendText sends a text message to a phone number (+15551234567) or JID and returns the message ID.
func (c *Client) SendText(chat, text string) (string, error) {
	var jid types.JID
	if strings.Contains(chat, "@") {
		var err error
		if jid, err = types.ParseJID(chat); err != nil {
			return "", err
		}
	} else {
		jid = types.NewJID(strings.TrimPrefix(chat, "+"), types.DefaultUserServer)
	}
	resp, err := c.wa.SendMessage(context.Background(), jid, &waE2E.Message{Conversation: proto.String(text)})
	if err != nil {
		return "", err
	}
	return resp.ID, nil
}

// RegisterWebPush registers a Web Push (RFC 8030) endpoint, as handed out by a
// UnifiedPush distributor, with WhatsApp. auth and p256dh are base64 (url-safe or std).
func (c *Client) RegisterWebPush(endpoint, auth, p256dh string) error {
	a, err := decodeB64(auth)
	if err != nil {
		return fmt.Errorf("auth: %w", err)
	}
	p, err := decodeB64(p256dh)
	if err != nil {
		return fmt.Errorf("p256dh: %w", err)
	}
	return c.wa.RegisterForPushNotifications(context.Background(), &whatsmeow.WebPushConfig{Endpoint: endpoint, Auth: a, P256DH: p})
}

func decodeB64(s string) ([]byte, error) {
	s = strings.TrimRight(s, "=")
	if b, err := base64.RawURLEncoding.DecodeString(s); err == nil {
		return b, nil
	}
	return base64.RawStdEncoding.DecodeString(s)
}

// ContactName returns the best known name for a JID (LID or phone), or "" if none.
// Order: saved full/first name, then the contact's own push name, then business name.
func (c *Client) ContactName(jid string) string {
	name, _ := c.resolve(jid)
	return name
}

// PhoneNumber returns "+<digits>" for a JID, mapping a LID to its phone number when known, or "" if not.
func (c *Client) PhoneNumber(jid string) string {
	_, phone := c.resolve(jid)
	return phone
}

// OwnNumber returns "+<digits>" for the linked account, or "" before pairing.
func (c *Client) OwnNumber() string {
	if c.wa.Store.ID == nil {
		return ""
	}
	return "+" + c.wa.Store.ID.User
}

func (c *Client) resolve(raw string) (name, phone string) {
	jid, err := types.ParseJID(raw)
	if err != nil {
		return "", ""
	}
	jid = jid.ToNonAD()
	ctx := context.Background()
	pn := jid
	if jid.Server == types.HiddenUserServer { // LID
		if m, err := c.wa.Store.LIDs.GetPNForLID(ctx, jid); err == nil && !m.IsEmpty() {
			pn = m
		} else {
			pn = types.EmptyJID
		}
	}
	if pn.Server == types.DefaultUserServer {
		phone = "+" + pn.User
	}
	for _, j := range []types.JID{pn, jid} {
		if j.IsEmpty() {
			continue
		}
		ci, err := c.wa.Store.Contacts.GetContact(ctx, j)
		if err != nil || !ci.Found {
			continue
		}
		for _, n := range []string{ci.FullName, ci.FirstName, ci.PushName, ci.BusinessName} {
			if n != "" {
				return n, phone
			}
		}
	}
	return "", phone
}

// Contacts returns saved contacts (those with a saved full or first name) as lines of
// "jid<TAB>name<TAB>phone", where phone is "+<digits>" or empty when unknown.
func (c *Client) Contacts() string {
	ctx := context.Background()
	all, err := c.wa.Store.Contacts.GetAllContacts(ctx)
	if err != nil {
		return ""
	}
	var b strings.Builder
	for jid, ci := range all {
		if jid.Server != types.DefaultUserServer && jid.Server != types.HiddenUserServer {
			continue
		}
		if c.wa.Store.ID != nil && (jid.User == c.wa.Store.ID.User) {
			continue
		}
		name := ci.FullName
		if name == "" {
			name = ci.FirstName
		}
		if name == "" {
			continue
		}
		_, phone := c.resolve(jid.String())
		fmt.Fprintf(&b, "%s\t%s\t%s\n", jid.String(), strings.ReplaceAll(name, "\t", " "), phone)
	}
	return b.String()
}

// CanonicalChat maps a phone JID or number to the LID chat key WhatsApp uses for that person,
// when known, so a typed number opens the same thread incoming messages land in.
func (c *Client) CanonicalChat(jid string) string {
	if !strings.Contains(jid, "@") {
		jid = strings.TrimPrefix(jid, "+") + "@" + types.DefaultUserServer
	}
	j, err := types.ParseJID(jid)
	if err != nil {
		return jid
	}
	j = j.ToNonAD()
	if j.Server == types.DefaultUserServer {
		if lid, err := c.wa.Store.LIDs.GetLIDForPN(context.Background(), j); err == nil && !lid.IsEmpty() {
			return lid.String()
		}
	}
	return j.String()
}

// SendMedia uploads a photo or video and sends it to chat. kind is "image" or "video";
// thumbnail is an optional small JPEG shown before the recipient downloads the file.
// Returns the message ID.
func (c *Client) SendMedia(chat, kind string, data []byte, mimetype, caption string, width, height, seconds int, thumbnail []byte) (string, error) {
	jid, err := parseChat(chat)
	if err != nil {
		return "", err
	}
	ctx := context.Background()
	// gomobile reuses byte slices after the call returns; the upload reads them synchronously,
	// but copy anyway so nothing outlives the call.
	data = append([]byte(nil), data...)
	thumb := append([]byte(nil), thumbnail...)
	if len(thumb) == 0 {
		thumb = nil
	}
	var msg *waE2E.Message
	switch kind {
	case "image":
		up, err := c.wa.Upload(ctx, data, whatsmeow.MediaImage)
		if err != nil {
			return "", fmt.Errorf("upload: %w", err)
		}
		msg = &waE2E.Message{ImageMessage: &waE2E.ImageMessage{
			URL: proto.String(up.URL), DirectPath: proto.String(up.DirectPath),
			MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256,
			FileLength: proto.Uint64(up.FileLength), Mimetype: proto.String(mimetype),
			Width: proto.Uint32(uint32(width)), Height: proto.Uint32(uint32(height)),
			MediaKeyTimestamp: proto.Int64(time.Now().Unix()), JPEGThumbnail: thumb,
			Caption: optString(caption),
		}}
	case "video":
		up, err := c.wa.Upload(ctx, data, whatsmeow.MediaVideo)
		if err != nil {
			return "", fmt.Errorf("upload: %w", err)
		}
		msg = &waE2E.Message{VideoMessage: &waE2E.VideoMessage{
			URL: proto.String(up.URL), DirectPath: proto.String(up.DirectPath),
			MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256,
			FileLength: proto.Uint64(up.FileLength), Mimetype: proto.String(mimetype),
			Width: proto.Uint32(uint32(width)), Height: proto.Uint32(uint32(height)),
			Seconds:           proto.Uint32(uint32(seconds)),
			MediaKeyTimestamp: proto.Int64(time.Now().Unix()), JPEGThumbnail: thumb,
			Caption: optString(caption),
		}}
	default:
		return "", fmt.Errorf("unsupported media kind %q", kind)
	}
	resp, err := c.wa.SendMessage(ctx, jid, msg)
	if err != nil {
		return "", err
	}
	return resp.ID, nil
}

func optString(s string) *string {
	if s == "" {
		return nil
	}
	return proto.String(s)
}

func parseChat(chat string) (types.JID, error) {
	if strings.Contains(chat, "@") {
		return types.ParseJID(chat)
	}
	return types.NewJID(strings.TrimPrefix(chat, "+"), types.DefaultUserServer), nil
}

func (c *Client) IsPaired() bool { return c.wa.Store.ID != nil }

// Connect logs in (emitting OnQR codes if unpaired) and blocks until connected.
// Call from a background thread.
func (c *Client) Connect() error {
	ctx, cancel := context.WithCancel(context.Background())
	c.mu.Lock()
	c.cancel = cancel
	c.mu.Unlock()

	if c.wa.Store.ID == nil {
		qr, _ := c.wa.GetQRChannel(ctx)
		if err := c.wa.Connect(); err != nil {
			return fmt.Errorf("connect: %w", err)
		}
		go func() {
			for evt := range qr {
				if evt.Event == "code" {
					c.l.OnQR(evt.Code)
				}
			}
		}()
	} else if err := c.wa.Connect(); err != nil {
		return fmt.Errorf("connect: %w", err)
	}
	if err := waitReady(ctx, c.wa, 3*time.Minute); err != nil {
		return err
	}
	if c.wa.Store.PushName == "" {
		c.wa.Store.PushName = "wa-android"
	}
	// Presence "available" is required for call signaling to be delivered.
	_ = c.wa.SendPresence(ctx, types.PresenceAvailable)
	c.l.OnConnected(c.wa.Store.ID.String())
	return nil
}

func (c *Client) Disconnect() {
	c.mu.Lock()
	if c.cancel != nil {
		c.cancel()
	}
	c.mu.Unlock()
	c.wa.Disconnect()
}

// SetSpeaker sets the playback sink used for peer audio on all calls.
func (c *Client) SetSpeaker(s Speaker) {
	c.mu.Lock()
	c.speaker = s
	c.mu.Unlock()
}

// PushMic feeds captured mic audio (16 kHz mono s16le) into the active call.
func (c *Client) PushMic(pcm []byte) {
	c.mu.Lock()
	m := c.mic
	c.mu.Unlock()
	if m != nil {
		m.push(pcm)
	}
}

// Call places a 1:1 voice call to a phone number (+15551234567) or JID. Returns the call ID.
func (c *Client) Call(target string) (string, error) {
	return c.place(target, false)
}

// CallVideo places a 1:1 video call. The app must then feed encoded frames with SendVideo.
func (c *Client) CallVideo(target string) (string, error) {
	return c.place(target, true)
}

func (c *Client) place(target string, video bool) (string, error) {
	if !strings.Contains(target, "@") {
		target = strings.TrimPrefix(target, "+")
	}
	call, err := c.mc.CallWithOptions(context.Background(), target, meowcaller.CallOptions{Video: video})
	if err != nil {
		return "", fmt.Errorf("place call: %w", err)
	}
	c.attach(call)
	return call.ID(), nil
}

func (c *Client) Answer(callID string) error {
	call := c.current(callID)
	if call == nil {
		return errors.New("no such call")
	}
	if err := call.Answer(); err != nil {
		return err
	}
	return nil
}

func (c *Client) Reject(callID string) error {
	if call := c.current(callID); call != nil {
		return call.Reject()
	}
	return errors.New("no such call")
}

func (c *Client) Hangup(callID string) error {
	if call := c.current(callID); call != nil {
		return call.Hangup()
	}
	return errors.New("no such call")
}

// IsVideo reports whether either direction of the call currently has video.
func (c *Client) IsVideo(callID string) bool {
	if call := c.current(callID); call != nil {
		return call.IsVideo()
	}
	return false
}

// StartVideo asks the peer to upgrade an audio call to video.
func (c *Client) StartVideo(callID string) error {
	if call := c.current(callID); call != nil {
		return call.StartVideo()
	}
	return errors.New("no such call")
}

// AcceptVideo accepts the peer's request to upgrade to video.
func (c *Client) AcceptVideo(callID string) error {
	if call := c.current(callID); call != nil {
		return call.AcceptVideo()
	}
	return errors.New("no such call")
}

// StopVideo turns off our camera for the rest of the call; audio and their video continue.
func (c *Client) StopVideo(callID string) error {
	if call := c.current(callID); call != nil {
		return call.StopVideo()
	}
	return errors.New("no such call")
}

// SetVideoOrientation tells the peer how to rotate our frames: quarter turns clockwise.
func (c *Client) SetVideoOrientation(callID string, quarterTurns int) error {
	if call := c.current(callID); call != nil {
		return call.SetVideoOrientation(quarterTurns)
	}
	return errors.New("no such call")
}

// SendVideo sends one encoded H.264 Annex-B access unit lasting durationUs (the camera's own
// frame timing, so the receiver's clock doesn't drift). Frames sent before video media is up
// are dropped (returns an error the caller can ignore).
func (c *Client) SendVideo(callID string, accessUnit []byte, durationUs int) error {
	call := c.current(callID)
	if call == nil || !call.IsVideo() {
		return errors.New("no video call")
	}
	// gomobile reuses the byte slice after return; the engine may hold on to it.
	au := append([]byte(nil), accessUnit...)
	return call.SendVideoWithDuration(au, time.Duration(durationUs)*time.Microsecond)
}

// SetVideoRenderer sets where the peer's video goes (all calls).
func (c *Client) SetVideoRenderer(r VideoRenderer) {
	c.mu.Lock()
	c.renderer = r
	c.mu.Unlock()
}

func (c *Client) current(id string) *meowcaller.Call {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.call != nil && c.call.ID() == id {
		return c.call
	}
	return nil
}

func (c *Client) onIncoming(call *meowcaller.Call) {
	c.attach(call)
	c.l.OnIncomingCall(call.ID(), call.Peer().String(), call.IsVideo())
}

// attach wires lifecycle callbacks and audio endpoints to call.
func (c *Client) attach(call *meowcaller.Call) {
	id := call.ID()
	mic := newMicSource(c.l.OnLog)
	c.mu.Lock()
	c.call, c.mic = call, mic
	c.mu.Unlock()

	call.OnReady(func() { c.l.OnCallReady(id) })
	call.ReceiveVideo(&videoSink{c: c, callID: id})
	call.OnVideoState(func(v meowcaller.VideoState) {
		c.l.OnVideoState(id, v.Active, v.Upgrade, v.Orientation, v.Raw)
	})
	call.OnVideoKeyframeRequest(func() { c.l.OnKeyframeRequest(id) })
	call.OnPeerAccept(func() { c.l.OnKeyframeRequest(id) })
	call.OnStateChange(func(p meowcaller.CallPhase) { c.l.OnCallState(id, int(p)) })
	call.OnEnd(func(reason string) {
		c.mu.Lock()
		if c.call != nil && c.call.ID() == id {
			c.call = nil
			c.mic = nil
		}
		c.mu.Unlock()
		mic.Close()
		c.l.OnCallEnded(id, reason)
	})
	call.Play(mic)
	call.Receive(meowcaller.SinkFunc(func(frame []float32) {
		c.mu.Lock()
		sp := c.speaker
		c.mu.Unlock()
		if sp != nil {
			sp.Write(floatToS16(frame))
		}
	}))
}

// videoSink forwards the peer's frames to the Kotlin renderer and reports the per-frame
// orientation meowcaller reads from the RTP video header extension.
type videoSink struct {
	c      *Client
	callID string
}

func (v *videoSink) WriteVideo(au []byte) error {
	v.c.mu.Lock()
	r := v.c.renderer
	v.c.mu.Unlock()
	if r != nil {
		r.Frame(au)
	}
	return nil
}

func (v *videoSink) SetOrientation(quarterTurns int) {
	v.c.l.OnRemoteOrientation(v.callID, quarterTurns)
}

func (v *videoSink) Close() error { return nil }

var _ meowcaller.VideoOrientationSink = (*videoSink)(nil)

func waitReady(ctx context.Context, wa *whatsmeow.Client, timeout time.Duration) error {
	ready := make(chan struct{}, 8)
	id := wa.AddEventHandler(func(evt any) {
		if _, ok := evt.(*events.Connected); ok {
			select {
			case ready <- struct{}{}:
			default:
			}
		}
	})
	defer wa.RemoveEventHandler(id)
	deadline := time.After(timeout)
	for !(wa.IsConnected() && wa.IsLoggedIn()) {
		select {
		case <-ready:
		case <-deadline:
			return errors.New("timed out waiting for connection")
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	return nil
}

type logWriter struct{ l Listener }

func (w logWriter) Write(p []byte) (int, error) {
	w.l.OnLog(strings.TrimRight(string(p), "\n"))
	return len(p), nil
}
