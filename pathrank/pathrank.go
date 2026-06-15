// Package pathrank is an experimental, self-contained route ranker for the
// Sidepath mesh graph. Given a weighted view of the topology — who can hear
// whom, and how well — it enumerates the candidate source routes between two
// nodes and ranks them by a transparent cost model, so a caller can see not
// just the winning route but exactly why it won.
//
// It deliberately knows nothing about the control API, the daemon, or BLE: it
// operates on plain core.NodeIDs and per-link metrics. Keeping the ranking
// logic isolated here makes it easy to tune and unit-test, and lets the message
// router reuse the same scoring later instead of growing its own copy.
package pathrank

import (
	"sort"

	"github.com/meshcore-cz/sidepath-protocol/core"
)

// Link is the quality of one directed hop, as the route ranker sees it. The
// fields mirror what a node advertises about a neighbor in a v3 ANNOUNCE (§8.8)
// or, for the local node, what its live link table holds.
type Link struct {
	// RSSI is the signal strength in dBm measured at the hop's near end. It is
	// always negative in practice; 0 means "no sample" and is treated as unknown.
	RSSI int
	// HasInfo is true when real per-link metrics are available. When false the
	// edge is known only as "these two nodes are neighbors" (v1/v2 announce), so
	// the ranker charges an uncertainty penalty instead of a quality cost.
	HasInfo bool
	// AgeS is seconds since the near end last heard the far end; older links are
	// less likely to still be up.
	AgeS uint32
	// Reverse is set when these metrics actually describe the far end's view of
	// the link (the near end never advertised it). The link is still usable but
	// we trust it a little less, so it carries a small extra penalty.
	Reverse bool
}

// Weights tunes the cost model. Every value is in the same abstract unit; only
// the ratios between them matter. Larger penalty = that factor weighs more
// heavily against a route.
type Weights struct {
	HopBase   float64 // fixed cost charged for every hop — this is what favors shorter routes
	RSSIFloor float64 // RSSI (dBm) considered "free"; weaker links pay (RSSIFloor-RSSI)*RSSIScale
	RSSIScale float64 // cost per dB below the floor
	AgeScale  float64 // cost per second of link staleness
	AgeCap    float64 // ceiling on the staleness cost of a single hop
	Unknown   float64 // charged when a hop has no measured quality (uncertainty)
	Reverse   float64 // extra charge when the quality is only known from the far side
}

// DefaultWeights is a reasonable starting point: every hop costs HopBase, so a
// shorter route always beats a longer one unless the longer one's links are
// markedly better; link quality then breaks ties and penalizes weak/stale hops.
func DefaultWeights() Weights {
	return Weights{
		HopBase:   10,
		RSSIFloor: -50,
		RSSIScale: 0.2,
		AgeScale:  0.1,
		AgeCap:    10,
		Unknown:   6,
		Reverse:   2,
	}
}

// Cost is the per-hop cost breakdown. It is kept component-by-component (rather
// than collapsed to a single number) precisely so a route can be explained:
// every term shows which factor contributed how much.
type Cost struct {
	Hop     float64 `json:"hop"`
	RSSI    float64 `json:"rssi"`
	Age     float64 `json:"age"`
	Unknown float64 `json:"unknown"`
	Reverse float64 `json:"reverse"`
	Total   float64 `json:"total"`
}

// edgeCost scores a single hop from its link metrics.
func (w Weights) edgeCost(l Link) Cost {
	c := Cost{Hop: w.HopBase}
	if !l.HasInfo || l.RSSI == 0 {
		// No usable measurement: charge a flat uncertainty penalty.
		c.Unknown = w.Unknown
	} else {
		if d := w.RSSIFloor - float64(l.RSSI); d > 0 { // RSSI weaker than the free floor
			c.RSSI = d * w.RSSIScale
		}
		if age := float64(l.AgeS) * w.AgeScale; age > 0 {
			if age > w.AgeCap {
				age = w.AgeCap
			}
			c.Age = age
		}
	}
	if l.Reverse {
		c.Reverse = w.Reverse
	}
	c.Total = c.Hop + c.RSSI + c.Age + c.Unknown + c.Reverse
	return c
}

// Hop is one edge of a ranked route, carrying the link it traverses and that
// link's cost breakdown.
type Hop struct {
	From core.NodeID
	To   core.NodeID
	Link Link
	Cost Cost
}

// Route is one candidate path from a source to a destination, ordered from the
// first hop to the last, with the summed cost the ranker assigned it.
type Route struct {
	Hops  []Hop
	Total float64
}

// Path returns the node IDs the route traverses after the source, i.e. every
// relay in order followed by the destination — the shape a source route takes.
func (r Route) Path() []core.NodeID {
	out := make([]core.NodeID, len(r.Hops))
	for i, h := range r.Hops {
		out[i] = h.To
	}
	return out
}

// Graph is a directed, cost-weighted view of the mesh. Build it with AddLink,
// then ask it for ranked Routes. Edge costs are computed eagerly from the
// graph's weights as links are added.
type Graph struct {
	w   Weights
	adj map[core.NodeID][]Hop
}

// New returns an empty graph that scores edges with the given weights.
func New(w Weights) *Graph {
	return &Graph{w: w, adj: make(map[core.NodeID][]Hop)}
}

// Weights reports the cost model this graph scores with.
func (g *Graph) Weights() Weights { return g.w }

// AddLink adds (or replaces) the directed hop from→to with the given metrics.
// Call it once per direction; a bidirectional link is two AddLink calls.
func (g *Graph) AddLink(from, to core.NodeID, l Link) {
	hop := Hop{From: from, To: to, Link: l, Cost: g.w.edgeCost(l)}
	for i, e := range g.adj[from] {
		if e.To == to {
			g.adj[from][i] = hop
			return
		}
	}
	g.adj[from] = append(g.adj[from], hop)
}

// Options bounds the search.
type Options struct {
	// MaxHops caps the number of hops in a route (relays + the final hop to the
	// destination). 0 selects a sensible default.
	MaxHops int
	// MaxRoutes caps how many ranked routes are returned. 0 selects a default.
	MaxRoutes int
}

// Routes enumerates every simple (loop-free) path from→to within the hop limit
// and returns them ranked cheapest-first, with the best MaxRoutes kept. The
// caller gets the full per-hop cost breakdown so it can show why the top route
// beat the alternatives. The mesh graphs this runs on are small, so exhaustive
// enumeration is both affordable and maximally transparent.
func (g *Graph) Routes(from, to core.NodeID, opt Options) []Route {
	if opt.MaxHops <= 0 {
		opt.MaxHops = 6
	}
	if opt.MaxRoutes <= 0 {
		opt.MaxRoutes = 8
	}

	var routes []Route
	visited := map[core.NodeID]bool{from: true}
	var cur []Hop

	var dfs func(node core.NodeID, total float64)
	dfs = func(node core.NodeID, total float64) {
		if node == to {
			if len(cur) > 0 {
				routes = append(routes, Route{Hops: append([]Hop(nil), cur...), Total: total})
			}
			return
		}
		if len(cur) >= opt.MaxHops {
			return
		}
		for _, e := range g.adj[node] {
			if visited[e.To] {
				continue
			}
			visited[e.To] = true
			cur = append(cur, e)
			dfs(e.To, total+e.Cost.Total)
			cur = cur[:len(cur)-1]
			visited[e.To] = false
		}
	}
	dfs(from, 0)

	sort.SliceStable(routes, func(i, j int) bool {
		if routes[i].Total != routes[j].Total {
			return routes[i].Total < routes[j].Total
		}
		return len(routes[i].Hops) < len(routes[j].Hops)
	})
	if len(routes) > opt.MaxRoutes {
		routes = routes[:opt.MaxRoutes]
	}
	return routes
}
