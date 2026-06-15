package cmd

import (
	"encoding/json"
	"fmt"
	"io"
	"strings"

	"github.com/meshcore-cz/sidepath-protocol/core"
	"github.com/meshcore-cz/sidepath-protocol/internal/api"
	"github.com/meshcore-cz/sidepath-protocol/pathrank"
	"github.com/spf13/cobra"
)

var pathCmd = &cobra.Command{
	Use:   "path <node-id>",
	Short: "Rank candidate routes to a node from announce & neighbor data",
	Long: `path is an experiment in offline route selection: rather than tracing a route
over the air, it builds a weighted graph from what the local node already knows —
the mesh topology learned via signed ANNOUNCE plus each node's advertised
per-link details (RSSI, PHY, age) — and ranks the candidate routes to a
destination by a transparent cost model.

Every route is shown with its total cost and a per-hop breakdown, so you can see
exactly why one route was preferred over another (shorter, stronger signal,
fresher links, fewer unknowns). The ranking algorithm lives in its own package
(pathrank) so it can later drive real message routing.

The node ID may be given in full or as any unambiguous short prefix.

  sp path <node-id>
  sp path <node-id> --max-hops 4 --routes 5

path requires a running daemon.`,
	Args: cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		if cfg.NoDaemon {
			return fmt.Errorf("path requires the daemon; remove --no-daemon")
		}
		topo, err := api.NewClient(cfg.SockPath()).Topology()
		if err != nil {
			return fmt.Errorf("cannot reach daemon: %w (is 'sp daemon' running?)", err)
		}

		self, err := core.ParseNodeID(topo.Self)
		if err != nil {
			return fmt.Errorf("daemon reported an invalid self id %q: %w", topo.Self, err)
		}
		destStr, err := resolveNodeID(topo, args[0])
		if err != nil {
			return err
		}
		dest, err := core.ParseNodeID(destStr)
		if err != nil {
			return err
		}
		if dest == self {
			return fmt.Errorf("destination is this node")
		}

		maxHops, _ := cmd.Flags().GetInt("max-hops")
		maxRoutes, _ := cmd.Flags().GetInt("routes")

		names := nodeNames(topo)
		g := buildGraph(topo)
		routes := g.Routes(self, dest, pathrank.Options{MaxHops: maxHops, MaxRoutes: maxRoutes})

		out := cmd.OutOrStdout()
		if cfg.JSON {
			return json.NewEncoder(out).Encode(pathJSON(self, dest, routes, names))
		}
		return printPaths(out, self, dest, routes, names)
	},
}

// buildGraph turns the topology into a directed, cost-weighted pathrank graph.
// Each advertised neighbor relation becomes an edge in both directions: the
// near end's own link metrics are used when it advertised them, otherwise the
// far end's view is borrowed and marked Reverse (trusted slightly less).
func buildGraph(topo *api.TopologyResult) *pathrank.Graph {
	// near[a][b] is how node a described its link to neighbor b.
	near := make(map[core.NodeID]map[core.NodeID]pathrank.Link)
	remember := func(from, to core.NodeID, l pathrank.Link) {
		if near[from] == nil {
			near[from] = make(map[core.NodeID]pathrank.Link)
		}
		near[from][to] = l
	}
	for _, n := range topo.Nodes {
		from, err := core.ParseNodeID(n.NodeID)
		if err != nil {
			continue
		}
		if len(n.Links) > 0 {
			for _, l := range n.Links {
				if to, err := core.ParseNodeID(l.NodeID); err == nil {
					remember(from, to, pathrank.Link{RSSI: l.RSSI, HasInfo: l.HasInfo, AgeS: l.AgeS})
				}
			}
			continue
		}
		// No per-link details (v1/v2 announce): edges are known by ID only.
		for _, nb := range n.Neighbors {
			if to, err := core.ParseNodeID(nb); err == nil {
				remember(from, to, pathrank.Link{})
			}
		}
	}

	g := pathrank.New(pathrank.DefaultWeights())
	added := make(map[[2]core.NodeID]bool)
	addEdge := func(from, to core.NodeID) {
		if from == to || added[[2]core.NodeID{from, to}] {
			return
		}
		l, ok := near[from][to]
		if !ok {
			// The near end never advertised this link; borrow the far end's view.
			if rev, okr := near[to][from]; okr {
				l, ok = rev, true
				l.Reverse = true
			}
		}
		if !ok {
			return
		}
		added[[2]core.NodeID{from, to}] = true
		g.AddLink(from, to, l)
	}
	for a, m := range near {
		for b := range m {
			addEdge(a, b)
			addEdge(b, a)
		}
	}
	return g
}

// nodeNames maps each known NodeID to its display name (empty if none).
func nodeNames(topo *api.TopologyResult) map[core.NodeID]string {
	names := make(map[core.NodeID]string, len(topo.Nodes))
	for _, n := range topo.Nodes {
		if id, err := core.ParseNodeID(n.NodeID); err == nil {
			names[id] = n.Name
		}
	}
	return names
}

// nodeLabeler returns a function that renders a NodeID as "shortid name" (or
// just the short id when no name is known), used for compact route printing.
func nodeLabeler(names map[core.NodeID]string) func(core.NodeID) string {
	return func(id core.NodeID) string {
		if name := names[id]; name != "" {
			return shortID(id.String()) + " " + name
		}
		return shortID(id.String())
	}
}

// printPaths renders the ranked routes with a per-hop cost breakdown.
func printPaths(out io.Writer, self, dest core.NodeID, routes []pathrank.Route, names map[core.NodeID]string) error {
	label := nodeLabeler(names)

	fmt.Fprintf(out, "routes %s → %s — %d candidate(s)\n\n", label(self), label(dest), len(routes))
	if len(routes) == 0 {
		fmt.Fprintln(out, "no route found in the known topology")
		return nil
	}

	for i, r := range routes {
		nodeSeq := append([]core.NodeID{self}, r.Path()...)
		parts := make([]string, len(nodeSeq))
		for j, id := range nodeSeq {
			parts[j] = label(id)
		}
		fmt.Fprintf(out, "#%d  cost %.1f  %d hop(s): %s\n", i+1, r.Total, len(r.Hops), strings.Join(parts, " → "))
		for _, h := range r.Hops {
			fmt.Fprintf(out, "      %s → %s   %s   cost %.1f  [%s]\n",
				label(h.From), label(h.To), linkMetrics(h.Link), h.Cost.Total, costBreakdown(h.Cost))
		}
		fmt.Fprintln(out)
	}

	fmt.Fprintln(out, "lower cost is better — each hop costs a base + penalties for weak signal, stale or unknown links")
	return nil
}

// printPathOverview renders a compact, one-line-per-route pathrank summary (no
// per-hop cost breakdown) — used as a section of `sp peer`. The full breakdown
// is available via `sp path`.
func printPathOverview(out io.Writer, self, dest core.NodeID, routes []pathrank.Route, names map[core.NodeID]string) {
	label := nodeLabeler(names)
	fmt.Fprintf(out, "\nPATHRANK (%s → %s) — %d candidate route(s)\n", label(self), label(dest), len(routes))
	if len(routes) == 0 {
		fmt.Fprintln(out, "  none in the known topology")
		return
	}
	for i, r := range routes {
		seq := append([]core.NodeID{self}, r.Path()...)
		parts := make([]string, len(seq))
		for j, id := range seq {
			parts[j] = label(id)
		}
		fmt.Fprintf(out, "  #%d  cost %.1f  %d hop(s)  %s\n", i+1, r.Total, len(r.Hops), strings.Join(parts, " → "))
	}
	fmt.Fprintf(out, "  (run 'sp path %s' for the per-hop cost breakdown)\n", shortID(dest.String()))
}

// linkMetrics renders the raw link facts a hop's cost was derived from.
func linkMetrics(l pathrank.Link) string {
	if !l.HasInfo || l.RSSI == 0 {
		s := "rssi=? age=?"
		if l.Reverse {
			s += " (reverse)"
		}
		return s
	}
	s := fmt.Sprintf("rssi=%d age=%s", l.RSSI, lastSeenLabel(int64(l.AgeS)))
	if l.Reverse {
		s += " (reverse)"
	}
	return s
}

// costBreakdown lists the non-zero components that make up a hop's cost, so the
// ranking is fully observable.
func costBreakdown(c pathrank.Cost) string {
	var parts []string
	add := func(name string, v float64) {
		if v != 0 {
			parts = append(parts, fmt.Sprintf("%s %.1f", name, v))
		}
	}
	add("hop", c.Hop)
	add("rssi", c.RSSI)
	add("age", c.Age)
	add("unknown", c.Unknown)
	add("reverse", c.Reverse)
	return strings.Join(parts, " + ")
}

// --- JSON shapes ----------------------------------------------------------

type pathResult struct {
	Self   string      `json:"self"`
	Dest   string      `json:"dest"`
	Routes []pathRoute `json:"routes"`
}

type pathRoute struct {
	Rank  int          `json:"rank"`
	Total float64      `json:"total_cost"`
	Path  []string     `json:"path"` // relay hops then dest (hex), as a source route
	Hops  []pathHopOut `json:"hops"`
}

type pathHopOut struct {
	From    string        `json:"from"`
	To      string        `json:"to"`
	Name    string        `json:"name,omitempty"`
	RSSI    int           `json:"rssi,omitempty"`
	HasInfo bool          `json:"has_info"`
	AgeS    uint32        `json:"age_s,omitempty"`
	Reverse bool          `json:"reverse,omitempty"`
	Cost    pathrank.Cost `json:"cost"`
}

func pathJSON(self, dest core.NodeID, routes []pathrank.Route, names map[core.NodeID]string) pathResult {
	res := pathResult{Self: self.String(), Dest: dest.String()}
	for i, r := range routes {
		pr := pathRoute{Rank: i + 1, Total: r.Total}
		for _, id := range r.Path() {
			pr.Path = append(pr.Path, id.String())
		}
		for _, h := range r.Hops {
			pr.Hops = append(pr.Hops, pathHopOut{
				From:    h.From.String(),
				To:      h.To.String(),
				Name:    names[h.To],
				RSSI:    h.Link.RSSI,
				HasInfo: h.Link.HasInfo,
				AgeS:    h.Link.AgeS,
				Reverse: h.Link.Reverse,
				Cost:    h.Cost,
			})
		}
		res.Routes = append(res.Routes, pr)
	}
	return res
}

func init() {
	pathCmd.Flags().Int("max-hops", 6, "maximum number of hops to consider")
	pathCmd.Flags().Int("routes", 8, "maximum number of ranked routes to show")
	rootCmd.AddCommand(pathCmd)
}
