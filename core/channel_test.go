package core

import "testing"

func TestChannelPublicHash(t *testing.T) {
	if got := ChannelHash(ChannelPublicPSK); got != 0x11 {
		t.Fatalf("public channel hash = 0x%02x, want 0x11", got)
	}
}

func TestChannelSealOpenRoundTrip(t *testing.T) {
	psk := DeriveChannelSecret("rock climbers")
	payload := SealChannel(psk, "Maya", "see you at 8am 🧗", 1_700_000_000)
	if payload[0] != ChannelHash(psk) {
		t.Fatalf("payload[0]=0x%02x, want channel hash 0x%02x", payload[0], ChannelHash(psk))
	}
	d, ok := OpenChannel(psk, payload)
	if !ok {
		t.Fatal("OpenChannel failed")
	}
	if d.Sender != "Maya" || d.Text != "see you at 8am 🧗" || d.Timestamp != 1_700_000_000 {
		t.Fatalf("decoded = %+v", d)
	}
}

func TestChannelWrongKeyFails(t *testing.T) {
	a := DeriveChannelSecret("alpha")
	b := DeriveChannelSecret("beta")
	payload := SealChannel(a, "x", "hi", 1)
	if _, ok := OpenChannel(b, payload); ok {
		t.Error("OpenChannel should fail with the wrong key")
	}
}

func TestChannelPublicRoundTrip(t *testing.T) {
	payload := SealChannel(ChannelPublicPSK, "Bob", "hello world", 42)
	if payload[0] != 0x11 {
		t.Fatalf("public payload[0]=0x%02x, want 0x11", payload[0])
	}
	d, ok := OpenChannel(ChannelPublicPSK, payload)
	if !ok || d.Text != "hello world" {
		t.Fatalf("decoded=%+v ok=%v", d, ok)
	}
}
