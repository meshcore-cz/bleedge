package cmd

import (
	"strings"
	"testing"

	"github.com/meshcore-cz/sidepath-protocol/core"
	"github.com/meshcore-cz/sidepath-protocol/internal/api"
)

// fullID returns a 20-char (10-byte) hex NodeID string from a seed byte.
func fullID(b byte) string {
	var id core.NodeID
	id[0] = b
	id[1] = 0xaa
	return id.String()
}

func testTopo() *api.TopologyResult {
	return &api.TopologyResult{Nodes: []api.TopologyNode{
		{NodeID: fullID(0x11)}, // 11aa...
		{NodeID: fullID(0x12)}, // 12aa...
		{NodeID: fullID(0x99)}, // 99aa...
	}}
}

func TestResolveFullID(t *testing.T) {
	id := fullID(0x11)
	got, err := resolveNodeID(testTopo(), id)
	if err != nil || got != id {
		t.Fatalf("full id should pass through: got %q err %v", got, err)
	}
}

func TestResolveUniquePrefix(t *testing.T) {
	got, err := resolveNodeID(testTopo(), "99")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got != fullID(0x99) {
		t.Fatalf("prefix 99 should resolve to %q, got %q", fullID(0x99), got)
	}
}

func TestResolveAmbiguousPrefix(t *testing.T) {
	// "1" matches both 11aa... and 12aa...
	_, err := resolveNodeID(testTopo(), "1")
	if err == nil || !strings.Contains(err.Error(), "ambiguous") {
		t.Fatalf("prefix 1 should be ambiguous, got err %v", err)
	}
}

func TestResolveNoMatch(t *testing.T) {
	_, err := resolveNodeID(testTopo(), "ab")
	if err == nil || !strings.Contains(err.Error(), "no known node") {
		t.Fatalf("prefix ab should not match, got err %v", err)
	}
}

func TestResolveRejectsNonHex(t *testing.T) {
	if _, err := resolveNodeID(testTopo(), "xyz"); err == nil {
		t.Fatalf("non-hex input should be rejected")
	}
}

func TestIsAutoPath(t *testing.T) {
	for _, v := range []string{"auto", "AUTO", " auto "} {
		if !isAutoPath([]string{v}) {
			t.Errorf("%q should be auto", v)
		}
	}
	if isAutoPath([]string{"auto", "11aa"}) {
		t.Errorf("auto plus an explicit hop is not auto")
	}
	if isAutoPath(nil) {
		t.Errorf("empty path is not auto")
	}
}
