package walib

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/polymorfa/hypermeow/proto/waHistorySync"
	"github.com/polymorfa/hypermeow/proto/waSyncAction"
	"github.com/polymorfa/hypermeow/proto/waWeb"
	"github.com/polymorfa/hypermeow/types"
	"github.com/polymorfa/hypermeow/types/events"
)

// historyBatch is the JSON passed to Listener.OnHistory.
type historyBatch struct {
	// OnDemand is true for an answer to RequestOlderMessages.
	OnDemand bool `json:"onDemand"`
	// Type is the sync stage, e.g. INITIAL_BOOTSTRAP, RECENT, FULL; Progress is 0-100 within it.
	Type     string        `json:"type"`
	Progress int           `json:"progress"`
	Messages []historyMsg  `json:"messages"`
	Chats    []historyChat `json:"chats"`
	Calls    []historyCall `json:"calls"`
}

type historyMsg struct {
	Chat       string `json:"chat"`
	Sender     string `json:"sender"`
	SenderName string `json:"senderName"`
	ID         string `json:"id"`
	Text       string `json:"text"`
	TsMs       int64  `json:"ts"`
	FromMe     bool   `json:"fromMe"`
	IsGroup    bool   `json:"isGroup"`
	// Status of our own messages: 0 none, 1 sent, 2 delivered, 3 seen (as in the app).
	Status int    `json:"status"`
	Media  string `json:"media"`
	// MediaRef lets DownloadPhoto / DownloadVideo fetch the full photo or video later (history
	// keeps thumbnails only).
	MediaRef string `json:"mediaRef"`
	// The message this one replies to (see quote); empty when it isn't a reply.
	QuotedID     string `json:"quotedId,omitempty"`
	QuotedSender string `json:"quotedSender,omitempty"`
	QuotedText   string `json:"quotedText,omitempty"`
	// Reactions on this message, one per person.
	Reactions []historyReaction `json:"reactions,omitempty"`
}

type historyChat struct {
	Chat     string `json:"chat"`
	Name     string `json:"name"`
	Unread   int    `json:"unread"`
	Archived bool   `json:"archived"`
	// MutedUntil: 0 not muted, -1 until unmuted, else Unix ms.
	MutedUntil int64 `json:"mutedUntil"`
	// PinnedAt: 0 not pinned, else when it was pinned (Unix ms).
	PinnedAt int64 `json:"pinnedAt"`
}

type historyCall struct {
	ID        string `json:"id"`
	Peer      string `json:"peer"`
	Incoming  bool   `json:"incoming"`
	StartedMs int64  `json:"startedAt"`
	DurationS int64  `json:"duration"`
	Video     bool   `json:"video"`
	// Result: "connected", "rejected", "missed" or "cancelled".
	Result string `json:"result"`
}

func (c *Client) handleHistory(e *events.HistorySync) {
	data := e.Data
	if data == nil {
		return
	}
	pushNames := map[string]string{}
	for _, p := range data.GetPushnames() {
		if p.GetPushname() != "" {
			pushNames[p.GetID()] = p.GetPushname()
		}
	}
	mappings := batchMappings(data)
	b := historyBatch{
		OnDemand: data.GetSyncType() == waHistorySync.HistorySync_ON_DEMAND,
		Type:     data.GetSyncType().String(),
		Progress: int(data.GetProgress()),
	}
	for _, conv := range data.GetConversations() {
		chat, err := types.ParseJID(conv.GetID())
		if err != nil || chat.Server == types.BroadcastServer {
			continue
		}
		name := conv.GetName()
		if name == "" {
			name = conv.GetDisplayName()
		}
		b.Chats = append(b.Chats, historyChat{
			Chat:       c.canonical(chat, mappings).String(),
			Name:       name,
			Unread:     int(conv.GetUnreadCount()),
			Archived:   conv.GetArchived(),
			MutedUntil: historyMutedUntil(conv.GetMuteEndTime()),
			PinnedAt:   historyPinnedAt(conv.GetPinned()),
		})
		for _, hm := range conv.GetMessages() {
			if m, ok := c.historyMessage(chat, hm.GetMessage(), pushNames, mappings); ok {
				b.Messages = append(b.Messages, m)
			}
		}
	}
	for _, r := range data.GetCallLogRecords() {
		if call, ok := historyCallOf(r); ok {
			call.Peer = c.canonicalString(call.Peer, mappings)
			b.Calls = append(b.Calls, call)
		}
	}
	c.l.OnLog(fmt.Sprintf("history: type=%s progress=%d chats=%d messages=%d calls=%d",
		data.GetSyncType(), b.Progress, len(b.Chats), len(b.Messages), len(b.Calls)))
	if len(b.Chats) == 0 && len(b.Calls) == 0 {
		return
	}
	out, err := json.Marshal(b)
	if err != nil {
		return
	}
	c.l.OnHistory(string(out))
}

func (c *Client) historyMessage(chat types.JID, web *waWeb.WebMessageInfo, pushNames map[string]string, mappings map[string]types.JID) (historyMsg, bool) {
	if web == nil {
		return historyMsg{}, false
	}
	m, err := c.wa.ParseWebMessage(chat, web)
	if err != nil || m.Message == nil {
		return historyMsg{}, false
	}
	text := messageText(m.Message)
	if text == "" {
		return historyMsg{}, false
	}
	name := m.Info.PushName
	if name == "" {
		name = pushNames[m.Info.Sender.ToNonAD().String()]
	}
	status := 0
	if m.Info.IsFromMe {
		status = historyStatus(web.GetStatus())
	}
	q := c.quoteOf(m.Message, mappings)
	return historyMsg{
		Chat:       c.canonical(m.Info.Chat, mappings).String(),
		Sender:     c.canonical(m.Info.Sender, mappings).String(),
		SenderName: name,
		ID:         m.Info.ID,
		Text:       text,
		TsMs:       m.Info.Timestamp.UnixMilli(),
		FromMe:     m.Info.IsFromMe,
		IsGroup:    m.Info.IsGroup,
		Status:     status,
		// Thumbnails only: downloading every old photo would be slow and mostly unseen.
		Media:        c.saveMedia(m, false),
		MediaRef:     mediaRef(m.Message),
		QuotedID:     q.ID,
		QuotedSender: q.Sender,
		QuotedText:   q.Text,
		Reactions:    c.historyReactions(m.Info.Chat, web, mappings),
	}, true
}

// historyStatus maps a stored message's status to the app's (sent/delivered/seen).
func historyStatus(s waWeb.WebMessageInfo_Status) int {
	switch s {
	case waWeb.WebMessageInfo_SERVER_ACK:
		return 1
	case waWeb.WebMessageInfo_DELIVERY_ACK:
		return 2
	case waWeb.WebMessageInfo_READ, waWeb.WebMessageInfo_PLAYED:
		return 3
	}
	return 0
}

// historyCallOf converts a call-log record. Group and call-link calls are skipped.
func historyCallOf(r *waSyncAction.CallLogRecord) (historyCall, bool) {
	if r.GetCallID() == "" || r.GetGroupJID() != "" || r.GetIsCallLink() {
		return historyCall{}, false
	}
	var peer string
	if r.GetIsIncoming() {
		peer = r.GetCallCreatorJID()
	} else if ps := r.GetParticipants(); len(ps) > 0 {
		peer = ps[0].GetUserJID()
	}
	if peer == "" {
		return historyCall{}, false
	}
	start := r.GetStartTime()
	if start < 1e12 { // seconds
		start *= 1000
	}
	var result string
	switch r.GetCallResult() {
	case waSyncAction.CallLogRecord_CONNECTED, waSyncAction.CallLogRecord_ACCEPTEDELSEWHERE:
		result = "connected"
	case waSyncAction.CallLogRecord_REJECTED:
		result = "rejected"
	case waSyncAction.CallLogRecord_MISSED:
		result = "missed"
	default:
		result = "cancelled"
	}
	if r.GetIsIncoming() && result == "cancelled" {
		result = "missed" // they hung up before we answered
	}
	return historyCall{
		ID:        r.GetCallID(),
		Peer:      peer,
		Incoming:  r.GetIsIncoming(),
		StartedMs: start,
		DurationS: r.GetDuration(),
		Video:     r.GetIsVideo(),
		Result:    result,
	}, true
}

// RequestOlderMessages asks the main phone for up to count messages in chat older than the
// given one (the oldest we have). The answer arrives later through OnHistory, if at all.
func (c *Client) RequestOlderMessages(chat, oldestID string, oldestTsMs int64, oldestFromMe bool, count int) error {
	jid, err := types.ParseJID(chat)
	if err != nil {
		return err
	}
	info := &types.MessageInfo{
		MessageSource: types.MessageSource{Chat: jid, IsFromMe: oldestFromMe, IsGroup: strings.HasSuffix(chat, "@g.us")},
		ID:            oldestID,
		Timestamp:     time.UnixMilli(oldestTsMs),
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	_, err = c.wa.SendPeerMessage(ctx, c.wa.BuildHistorySyncRequest(info, count))
	return err
}
