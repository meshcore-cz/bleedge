package pathrank

import (
	"testing"

	"github.com/meshcore-cz/sidepath-protocol/core"
)

// nid builds a distinct NodeID from a single byte, enough to tell test nodes apart.
func nid(b byte) core.NodeID {
	var id core.NodeID
	id[0] = b
	return id
}

var (
	self   = nid(0)
	relayA = nid(1)
	relayB = nid(2)
	dest   = nid(9)
)

// strong is a healthy, freshly-seen, fully-measured link.
func strong() Link { return Link{RSSI: -45, HasInfo: true, AgeS: 1} }

func TestPrefersFewerHops(t *testing.T) {
	g := New(DefaultWeights())
	// Direct self->dest, plus a two-hop detour self->relayA->dest, all strong.
	g.AddLink(self, dest, strong())
	g.AddLink(self, relayA, strong())
	g.AddLink(relayA, dest, strong())

	routes := g.Routes(self, dest, Options{})
	if len(routes) != 2 {
		t.Fatalf("want 2 routes, got %d", len(routes))
	}
	if got := routes[0].Path(); len(got) != 1 || got[0] != dest {
		t.Fatalf("best route should be the direct hop, got %v", got)
	}
	if routes[0].Total >= routes[1].Total {
		t.Fatalf("direct route should cost less: %.1f vs %.1f", routes[0].Total, routes[1].Total)
	}
}

func TestPrefersStrongerLinkAtEqualHops(t *testing.T) {
	g := New(DefaultWeights())
	// Two one-hop-relay routes of equal length; the relayA path has a weak last hop.
	g.AddLink(self, relayA, strong())
	g.AddLink(relayA, dest, Link{RSSI: -95, HasInfo: true, AgeS: 1}) // weak
	g.AddLink(self, relayB, strong())
	g.AddLink(relayB, dest, strong())

	routes := g.Routes(self, dest, Options{})
	if len(routes) != 2 {
		t.Fatalf("want 2 routes, got %d", len(routes))
	}
	if got := routes[0].Path(); got[0] != relayB {
		t.Fatalf("best route should go via the stronger relayB, got first hop %v", got[0])
	}
}

func TestUnknownLinkCostsMoreThanMeasured(t *testing.T) {
	g := New(DefaultWeights())
	g.AddLink(self, dest, Link{HasInfo: false}) // ID-only edge (v1/v2 announce)
	measured := New(DefaultWeights())
	measured.AddLink(self, dest, strong())

	unknown := g.Routes(self, dest, Options{})[0].Total
	known := measured.Routes(self, dest, Options{})[0].Total
	if unknown <= known {
		t.Fatalf("unknown link (%.1f) should cost more than a measured strong one (%.1f)", unknown, known)
	}
}

func TestNoRouteReturnsEmpty(t *testing.T) {
	g := New(DefaultWeights())
	g.AddLink(self, relayA, strong()) // dead end, never reaches dest
	if routes := g.Routes(self, dest, Options{}); len(routes) != 0 {
		t.Fatalf("want no routes, got %d", len(routes))
	}
}

func TestMaxHopsBounds(t *testing.T) {
	g := New(DefaultWeights())
	g.AddLink(self, relayA, strong())
	g.AddLink(relayA, relayB, strong())
	g.AddLink(relayB, dest, strong())
	if routes := g.Routes(self, dest, Options{MaxHops: 2}); len(routes) != 0 {
		t.Fatalf("3-hop route should be excluded by MaxHops=2, got %d routes", len(routes))
	}
	if routes := g.Routes(self, dest, Options{MaxHops: 3}); len(routes) != 1 {
		t.Fatalf("3-hop route should be found with MaxHops=3, got %d routes", len(routes))
	}
}
