package meowcaller

import (
	"testing"
	"time"
)

func TestRelayLinkDownDropsSendsAndFailsRecv(t *testing.T) {
	l := newRelayLink(nil)
	if n, err := l.Send([]byte{1, 2, 3}); n != 0 || err != nil {
		t.Fatalf("send on a down link = %d, %v; want 0, nil", n, err)
	}
	if _, err := l.Recv(make([]byte, 4)); err == nil {
		t.Fatal("recv on a down link succeeded")
	}
	if silent := l.silentFor(time.Now().Add(relaySilenceLimit + time.Second)); silent <= relaySilenceLimit {
		t.Fatalf("silentFor = %s; want more than %s", silent, relaySilenceLimit)
	}
}

func TestReconnectEndpointTriesTheUsualRelayFirstThenTheOthers(t *testing.T) {
	addr := []relayAddress{{ipv4: "192.0.2.1", port: 3478}}
	rd := &relayData{endpoints: []relayEndpoint{
		{relayName: "a", addresses: addr},
		{relayName: "b", authTokenID: 1, addresses: addr},
		{relayName: "c", addresses: addr},
		{relayName: "fna", isFNA: true, addresses: addr},
		{relayName: "empty"},
	}}
	var got []string
	for n := 0; n < 4; n++ {
		got = append(got, reconnectEndpoint(rd, false, n).relayName)
	}
	want := []string{"b", "a", "c", "b"}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("outbound order = %v; want %v", got, want)
		}
	}
	if ep := reconnectEndpoint(rd, true, 0); ep.relayName != "fna" {
		t.Fatalf("inbound first endpoint = %s; want fna", ep.relayName)
	}
	if ep := reconnectEndpoint(rd, true, 1); ep.relayName != "fna" {
		t.Fatalf("inbound second endpoint = %s; want fna (the only one)", ep.relayName)
	}
}

func TestInboundRelayPrefersTheTokenlessRelayWhenNoneIsMarked(t *testing.T) {
	addr := []relayAddress{{ipv4: "192.0.2.1", port: 3478}}
	rd := &relayData{endpoints: []relayEndpoint{
		{relayName: "a", tokenID: 2, authTokenID: 1, addresses: addr},
		{relayName: "b", authTokenID: 1, addresses: addr},
		{relayName: "noaddr"},
		{relayName: "c", addresses: addr},
	}}
	if ep := getMediaRelayEndpoint(rd, true); ep.relayName != "c" {
		t.Fatalf("inbound relay = %s; want the one without an auth token, c", ep.relayName)
	}
	if ep := getMediaRelayEndpoint(rd, false); ep.relayName != "a" {
		t.Fatalf("outbound relay = %s; want the first with an auth token, a", ep.relayName)
	}
}

func TestInboundRelayStillPrefersTheMarkedRelay(t *testing.T) {
	addr := []relayAddress{{ipv4: "192.0.2.1", port: 3478}}
	rd := &relayData{endpoints: []relayEndpoint{
		{relayName: "plain", addresses: addr},
		{relayName: "fna", isFNA: true, addresses: addr},
	}}
	if ep := getMediaRelayEndpoint(rd, true); ep.relayName != "fna" {
		t.Fatalf("inbound relay = %s; want the FNA-marked relay", ep.relayName)
	}
}

func TestReconnectStartsFromTheRelayThatCarriedTheCall(t *testing.T) {
	addr := []relayAddress{{ipv4: "192.0.2.1", port: 3478}}
	rd := &relayData{endpoints: []relayEndpoint{
		{relayName: "a", addresses: addr},
		{relayName: "b", authTokenID: 1, addresses: addr},
		{relayName: "c", authTokenID: 1, addresses: addr},
	}}
	var got []string
	for n := 0; n < 4; n++ {
		got = append(got, reconnectEndpointFrom(rd, true, "b", n).relayName)
	}
	want := []string{"b", "c", "a", "b"}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("order from b = %v; want %v", got, want)
		}
	}
	if ep := reconnectEndpointFrom(rd, true, "gone", 0); ep.relayName != "a" {
		t.Fatalf("unknown relay start = %s; want the usual first pick, a", ep.relayName)
	}
}
