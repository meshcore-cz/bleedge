package core

import (
	"bytes"
	"crypto/sha256"
	"testing"
)

func TestMeshCoreCarrierRoundTrip(t *testing.T) {
	raw := []byte{0x10, 0x00, 0xde, 0xad, 0xbe, 0xef}
	framed := FrameMeshCorePacket("CZ", raw)
	if bytes.Equal(framed, raw) {
		t.Fatal("framed payload should differ from raw when a code is present")
	}
	code, got := UnframeMeshCorePacket(framed)
	if code != "CZ" {
		t.Fatalf("code = %q, want CZ", code)
	}
	if !bytes.Equal(got, raw) {
		t.Fatalf("raw = %x, want %x", got, raw)
	}
}

func TestMeshCoreCarrierLegacyRawUnframes(t *testing.T) {
	// A legacy raw payload (no SPMC frame) must unframe to ("", sameBytes).
	raw := []byte{0x10, 0x00, 0x01, 0x02, 0x03}
	code, got := UnframeMeshCorePacket(raw)
	if code != "" {
		t.Fatalf("code = %q, want empty", code)
	}
	if !bytes.Equal(got, raw) {
		t.Fatalf("raw = %x, want %x", got, raw)
	}
}

func TestMeshCoreCarrierFrameRejectsBadCode(t *testing.T) {
	raw := []byte{0x01, 0x02}
	if got := FrameMeshCorePacket("", raw); !bytes.Equal(got, raw) {
		t.Fatalf("empty code should pass through raw, got %x", got)
	}
	if got := FrameMeshCorePacket("TOOLONG", raw); !bytes.Equal(got, raw) {
		t.Fatalf("over-length code should pass through raw, got %x", got)
	}
}

func TestMeshCoreCarrierUnframeGuards(t *testing.T) {
	cases := map[string][]byte{
		"too short":        {'S', 'P', 'M'},
		"wrong magic":      {'X', 'P', 'M', 'C', 1, 1, 'C', 0xaa},
		"wrong version":    {'S', 'P', 'M', 'C', 99, 1, 'C', 0xaa},
		"zero code len":    {'S', 'P', 'M', 'C', 1, 0, 0xaa},
		"code len too big": {'S', 'P', 'M', 'C', 1, byte(MaxNetworkCodeBytes + 1), 'C'},
		"truncated code":   {'S', 'P', 'M', 'C', 1, 4, 'C', 'Z'},
	}
	for name, payload := range cases {
		t.Run(name, func(t *testing.T) {
			code, got := UnframeMeshCorePacket(payload)
			if code != "" {
				t.Fatalf("code = %q, want empty (treated as legacy raw)", code)
			}
			if !bytes.Equal(got, payload) {
				t.Fatalf("raw = %x, want whole payload %x", got, payload)
			}
		})
	}
}

// TestMeshCoreCarrierContentHashEquality is the safety invariant for a mixed bridge fleet: the
// content hash of the inner raw must be identical whether the packet was bridged framed or raw.
func TestMeshCoreCarrierContentHashEquality(t *testing.T) {
	raw := []byte{0x10, 0x00, 0xca, 0xfe, 0xba, 0xbe, 0x01}
	framed := FrameMeshCorePacket("EU", raw)

	_, fromFramed := UnframeMeshCorePacket(framed)
	_, fromRaw := UnframeMeshCorePacket(raw)

	if sha256.Sum256(fromFramed) != sha256.Sum256(fromRaw) {
		t.Fatal("content hash of inner raw differs between framed and raw carriage")
	}
}

// TestMeshCoreCarrierCrossImplVector pins the exact bytes a Go bridge emits so the Kotlin
// implementation can assert it unframes the identical vector. Keep in sync with the Kotlin test.
func TestMeshCoreCarrierCrossImplVector(t *testing.T) {
	raw := []byte{0xde, 0xad, 0xbe, 0xef}
	framed := FrameMeshCorePacket("CZ", raw)
	want := []byte{'S', 'P', 'M', 'C', 1, 2, 'C', 'Z', 0xde, 0xad, 0xbe, 0xef}
	if !bytes.Equal(framed, want) {
		t.Fatalf("framed = %x, want %x", framed, want)
	}
}
