package cmd

import (
	"fmt"
	"strings"

	"github.com/meshcore-cz/sidepath-protocol/core"
	"github.com/meshcore-cz/sidepath-protocol/internal/api"
	"github.com/meshcore-cz/sidepath-protocol/pathrank"
)

// autoPathKeyword is the --path value that selects pathrank auto-routing.
const autoPathKeyword = "auto"

// idResolver expands short (hex-prefix) NodeIDs to full ones and computes
// pathrank auto-routes. It fetches the topology from the daemon lazily and at
// most once, so commands that only ever see full IDs pay no extra round-trip.
type idResolver struct {
	client *api.Client
	topo   *api.TopologyResult
}

// newResolver returns a resolver bound to the configured daemon socket.
func newResolver() *idResolver { return &idResolver{client: api.NewClient(cfg.SockPath())} }

func (r *idResolver) topology() (*api.TopologyResult, error) {
	if r.topo == nil {
		t, err := r.client.Topology()
		if err != nil {
			return nil, fmt.Errorf("cannot reach daemon: %w (is 'sp daemon' running?)", err)
		}
		r.topo = t
	}
	return r.topo, nil
}

// resolve returns the full hex NodeID for s, which may already be a full id or a
// unique hex prefix of a node the daemon knows about.
func (r *idResolver) resolve(s string) (string, error) {
	if _, err := core.ParseNodeID(strings.ToLower(strings.TrimSpace(s))); err == nil {
		return strings.ToLower(strings.TrimSpace(s)), nil
	}
	topo, err := r.topology()
	if err != nil {
		return "", err
	}
	return resolveNodeID(topo, s)
}

// resolveHops resolves every hop of an explicit route, allowing short IDs.
func (r *idResolver) resolveHops(hops []string) ([]string, error) {
	out := make([]string, len(hops))
	for i, h := range hops {
		full, err := r.resolve(h)
		if err != nil {
			return nil, fmt.Errorf("route hop %q: %w", h, err)
		}
		out[i] = full
	}
	return out, nil
}

// autoRoute computes the best pathrank route to dest (full hex), returning the
// source-route hops (relays then dest). It errors if no route is found.
func (r *idResolver) autoRoute(dest string) ([]string, error) {
	topo, err := r.topology()
	if err != nil {
		return nil, err
	}
	self, err := core.ParseNodeID(topo.Self)
	if err != nil {
		return nil, fmt.Errorf("daemon reported an invalid self id %q: %w", topo.Self, err)
	}
	dst, err := core.ParseNodeID(dest)
	if err != nil {
		return nil, err
	}
	routes := buildGraph(topo).Routes(self, dst, pathrank.Options{})
	if len(routes) == 0 {
		return nil, fmt.Errorf("--path auto: pathrank found no route to %s in the known topology", shortID(dest))
	}
	hops := routes[0].Path()
	out := make([]string, len(hops))
	for i, h := range hops {
		out[i] = h.String()
	}
	return out, nil
}

// resolveNodeID maps s (a full hex id or a unique hex prefix) to a full NodeID
// from the given topology. It is the prefix-matching core shared by idResolver
// and any command that already holds a topology snapshot.
func resolveNodeID(topo *api.TopologyResult, s string) (string, error) {
	s = strings.ToLower(strings.TrimSpace(s))
	if _, err := core.ParseNodeID(s); err == nil {
		return s, nil
	}
	if !isHexPrefix(s) {
		return "", fmt.Errorf("invalid node id %q", s)
	}
	var matches []string
	seen := make(map[string]bool)
	for _, n := range topo.Nodes {
		if !seen[n.NodeID] && strings.HasPrefix(n.NodeID, s) {
			seen[n.NodeID] = true
			matches = append(matches, n.NodeID)
		}
	}
	switch len(matches) {
	case 0:
		return "", fmt.Errorf("no known node matches id %q", s)
	case 1:
		return matches[0], nil
	default:
		short := make([]string, len(matches))
		for i, m := range matches {
			short[i] = shortID(m)
		}
		return "", fmt.Errorf("id %q is ambiguous (%d matches: %s)", s, len(matches), strings.Join(short, ", "))
	}
}

// isAutoPath reports whether a --path value selects pathrank auto-routing.
func isAutoPath(path []string) bool {
	return len(path) == 1 && strings.EqualFold(strings.TrimSpace(path[0]), autoPathKeyword)
}

// isHexPrefix reports whether s is a non-empty hex string no longer than a full
// NodeID — i.e. a plausible id prefix.
func isHexPrefix(s string) bool {
	if s == "" || len(s) > core.NodeIDBytes*2 {
		return false
	}
	for _, c := range s {
		if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
			return false
		}
	}
	return true
}
