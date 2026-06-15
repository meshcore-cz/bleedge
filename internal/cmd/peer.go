package cmd

import (
	"encoding/json"
	"fmt"
	"io"
	"sort"
	"strings"
	"text/tabwriter"

	"github.com/meshcore-cz/sidepath-protocol/core"
	"github.com/meshcore-cz/sidepath-protocol/internal/api"
	"github.com/meshcore-cz/sidepath-protocol/pathrank"
	"github.com/spf13/cobra"
)

var peerCmd = &cobra.Command{
	Use:   "peer <node-id>",
	Short: "Show detailed info about one node, its links, and routes to it",
	Long: `peer shows everything the local daemon knows about a single node: its latest
announce data (name, platform, description, capabilities, public key, bridged
networks), this node's live link and route to it, and the node's links shown
from both ends — what that node advertises about each neighbor and what the
neighbor advertises back (RSSI, PHY, direction, age can differ per side). It
finishes with a pathrank overview of the candidate routes from us to it. The
node ID may be given in full or as any unambiguous short prefix. It requires a
running daemon.`,
	Args: cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		if cfg.NoDaemon {
			return fmt.Errorf("peer requires the daemon; remove --no-daemon")
		}

		client := api.NewClient(cfg.SockPath())
		// Fetch the topology once: it both resolves a short id and feeds the
		// pathrank overview. If it is unavailable we can still resolve a full id.
		topo, topoErr := client.Topology()
		lookupTopo := &api.TopologyResult{}
		if topoErr == nil {
			lookupTopo = topo
		}
		id, err := resolveNodeID(lookupTopo, args[0])
		if err != nil {
			return err
		}

		detail, err := client.Peer(id)
		if err != nil {
			return fmt.Errorf("cannot reach daemon: %w (is 'sp daemon' running?)", err)
		}

		out := cmd.OutOrStdout()
		if cfg.JSON {
			return json.NewEncoder(out).Encode(detail)
		}

		p := detail.Peer
		kv := tabwriter.NewWriter(out, 0, 2, 2, ' ', 0)
		fmt.Fprintf(kv, "NODE ID\t%s\n", p.NodeID)
		fmt.Fprintf(kv, "NAME\t%s\n", dash(p.Name))
		fmt.Fprintf(kv, "PLATFORM\t%s\n", dash(p.Platform))
		if detail.Pubkey != "" {
			fmt.Fprintf(kv, "PUBKEY\t%s\n", detail.Pubkey)
		}
		fmt.Fprintf(kv, "CONN\t%s\n", connLabel(p))
		fmt.Fprintf(kv, "RSSI\t%s\n", rssiLabel(p))
		fmt.Fprintf(kv, "PHY\t%s\n", phyLabel(p))
		// Packet counters are link-only; show them just for a connected peer.
		if p.Connected {
			fmt.Fprintf(kv, "RX/TX\t%s / %s packets\n", pktCount(p, p.RxPackets), pktCount(p, p.TxPackets))
			fmt.Fprintf(kv, "LAST RX\t%s\n", lastRxLabel(p))
		}
		fmt.Fprintf(kv, "ROUTE\t%s\n", routeLabel(p.Hops))
		fmt.Fprintf(kv, "CAPS\t%s\n", capsString(p.Relay, p.Gateway))
		if len(detail.Bridges) > 0 {
			fmt.Fprintf(kv, "BRIDGES\t%s\n", strings.Join(detail.Bridges, ", "))
		}
		fmt.Fprintf(kv, "ANNOUNCE\tepoch=%d seq=%d (%s)\n", p.AnnounceEpoch, p.AnnounceSeq, lastSeenLabel(p.LastAnnounceS))
		if p.Description != "" {
			fmt.Fprintf(kv, "DESCRIPTION\t%s\n", p.Description)
		}
		if err := kv.Flush(); err != nil {
			return err
		}

		// One merged links table: each neighbor on one row, with the link as this
		// node advertises it (→) and as the neighbor advertises it back (←).
		if err := printLinkTable(out, shortID(p.NodeID), detail.NeighborList, detail.NeighborOfList); err != nil {
			return err
		}

		// Pathrank overview: candidate routes from us to this node.
		if topoErr == nil {
			if self, perr := core.ParseNodeID(topo.Self); perr == nil {
				if dst, derr := core.ParseNodeID(id); derr == nil && dst != self {
					routes := buildGraph(topo).Routes(self, dst, pathrank.Options{MaxRoutes: 5})
					printPathOverview(out, self, dst, routes, nodeNames(topo))
				}
			}
		}
		return nil
	},
}

// linkRow is one neighbor in the merged links table, holding the forward link
// (this node's advertised view) and the reverse link (the neighbor's view).
type linkRow struct {
	id   string
	name string
	fwd  *api.NeighborDetail // subject → neighbor, as the subject advertised it
	rev  *api.NeighborDetail // neighbor → subject, as the neighbor advertised it
}

// printLinkTable merges the subject's advertised neighbors (fwd) and the nodes
// that advertise the subject (rev) into one row per neighbor, so each link is
// visible from both ends at once.
func printLinkTable(out io.Writer, subject string, fwd, rev []api.NeighborDetail) error {
	rows := make(map[string]*linkRow)
	var order []string
	get := func(id string) *linkRow {
		r := rows[id]
		if r == nil {
			r = &linkRow{id: id}
			rows[id] = r
			order = append(order, id)
		}
		return r
	}
	for i := range fwd {
		r := get(fwd[i].NodeID)
		r.fwd = &fwd[i]
		if r.name == "" {
			r.name = fwd[i].Name
		}
	}
	for i := range rev {
		r := get(rev[i].NodeID)
		r.rev = &rev[i]
		if r.name == "" {
			r.name = rev[i].Name
		}
	}
	sort.Strings(order)

	fmt.Fprintf(out, "\nLINKS (%d) — → as %s advertises the link, ← as the neighbor advertises it back\n", len(order), subject)
	if len(order) == 0 {
		_, err := fmt.Fprintln(out, "  none")
		return err
	}
	tw := tabwriter.NewWriter(out, 0, 2, 2, ' ', 0)
	fmt.Fprintln(tw, "NODE ID\tNAME\t→ (this node sees)\t← (neighbor sees)")
	for _, id := range order {
		r := rows[id]
		fmt.Fprintf(tw, "%s\t%s\t%s\t%s\n", r.id, dash(r.name), linkCell(r.fwd), linkCell(r.rev))
	}
	return tw.Flush()
}

// linkCell renders one direction of a link as a compact "rssi phy dir age"
// string, omitting fields with no sample. "-" means the link is not advertised
// in that direction; "advertised" means it is, but with no per-link metrics
// (a v1/v2 announce).
func linkCell(n *api.NeighborDetail) string {
	if n == nil {
		return "-"
	}
	if !n.HasInfo {
		return "advertised"
	}
	var parts []string
	if n.RSSI < 0 {
		parts = append(parts, fmt.Sprintf("%d", n.RSSI))
	}
	if phy := nbrPHY(*n); phy != "-" {
		parts = append(parts, phy)
	}
	if d := dirAbbrev(n.Direction); d != "" {
		parts = append(parts, d)
	}
	parts = append(parts, lastSeenLabel(int64(n.AgeS)))
	return strings.Join(parts, " ")
}

func nbrPHY(n api.NeighborDetail) string {
	if !n.HasInfo {
		return "-"
	}
	switch {
	case n.TxPHY == "" && n.RxPHY == "":
		return "-"
	case n.TxPHY == n.RxPHY:
		return n.TxPHY
	default:
		return n.TxPHY + "/" + n.RxPHY
	}
}

// dirAbbrev shortens a link direction for the compact link cell.
func dirAbbrev(d string) string {
	switch d {
	case "outbound":
		return "out"
	case "inbound":
		return "in"
	case "in+out":
		return "both"
	default:
		return ""
	}
}

func init() {
	rootCmd.AddCommand(peerCmd)
}
