package core

import (
	"fmt"
	"sync"
	"time"
)

type ConnDirection uint8

const (
	DirectionOutgoing ConnDirection = 1
	DirectionIncoming ConnDirection = 2
	// DirectionBoth marks a peer held over both an inbound and an outbound link at once (§4.4). The
	// smaller-NodeID side of a redundant pair keeps both links, so it advertises the link as "in+out".
	DirectionBoth ConnDirection = 3
)

// Neighbor represents a directly connected peer. Alongside the last-sample link
// facts it carries the live link statistics this node observes — a smoothed
// RSSI, a representative round-trip latency, and a delivery-reliability score —
// which seed the quality hints in our v3 ANNOUNCE (§8.8) and the local route
// ranker. They are updated by Set/Record* and snapshotted by AnnounceInfo.
type Neighbor struct {
	ID        NodeID
	Direction ConnDirection
	LastSeen  time.Time
	RSSI      int
	TxPHY     PHY
	RxPHY     PHY
	Caps      Capabilities
	PublicKey []byte
	// Live link statistics (0 = no sample / unknown for each).
	RSSIEWMA  int    // smoothed RSSI in dBm
	RTTms     uint16 // representative round-trip latency
	QualityQ8 uint8  // delivery reliability 0..255 (0 = unknown; we never store a true 0)
	// deliverEWMA is the smoothed [0,1] delivery success behind QualityQ8.
	deliverEWMA float64
	deliverInit bool
}

// EWMA smoothing factors: higher = more weight on the newest sample.
const (
	rssiAlpha    = 0.3
	rttAlpha     = 0.3
	deliverAlpha = 0.25
)

func (n Neighbor) String() string {
	return fmt.Sprintf("%s rssi=%d tx=%s rx=%s relay=%v gateway=%v seen=%s",
		n.ID, n.RSSI, n.TxPHY, n.RxPHY,
		n.Caps.IsRelay(), n.Caps.IsGateway(),
		time.Since(n.LastSeen).Round(time.Second))
}

// NeighborTable is a thread-safe map of directly connected peers.
type NeighborTable struct {
	mu        sync.RWMutex
	neighbors map[NodeID]*Neighbor
	timeout   time.Duration
}

func NewNeighborTable() *NeighborTable {
	t := &NeighborTable{
		neighbors: make(map[NodeID]*Neighbor),
		timeout:   60 * time.Second,
	}
	go t.reap()
	return t
}

func (t *NeighborTable) Upsert(n Neighbor) {
	t.mu.Lock()
	defer t.mu.Unlock()
	n.LastSeen = time.Now()
	t.neighbors[n.ID] = &n
}

// SetRSSI refreshes an existing neighbor's signal strength in place (and its
// LastSeen, since hearing an advertisement is a sign of liveness). It is a no-op
// if the neighbor isn't in the table.
func (t *NeighborTable) SetRSSI(id NodeID, rssi int) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if n, ok := t.neighbors[id]; ok {
		n.RSSI = rssi
		if n.RSSIEWMA == 0 {
			n.RSSIEWMA = rssi
		} else {
			n.RSSIEWMA = int(float64(n.RSSIEWMA)*(1-rssiAlpha) + float64(rssi)*rssiAlpha)
		}
		n.LastSeen = time.Now()
	}
}

// RecordRTT folds a round-trip latency sample (ms) into a neighbor's smoothed
// RTT. Callers should pass only direct-link samples (e.g. an ACK or trace to a
// direct neighbor), not end-to-end multi-hop times. No-op for an unknown neighbor.
func (t *NeighborTable) RecordRTT(id NodeID, ms uint16) {
	if ms == 0 {
		return
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	if n, ok := t.neighbors[id]; ok {
		if n.RTTms == 0 {
			n.RTTms = ms
		} else {
			n.RTTms = uint16(float64(n.RTTms)*(1-rttAlpha) + float64(ms)*rttAlpha)
		}
	}
}

// RecordDelivery folds one delivery outcome (an ACK received, or a timeout) into
// a neighbor's smoothed reliability, updating QualityQ8. Callers should pass only
// direct-link outcomes. QualityQ8 is floored at 1 once sampled so 0 still means
// "no data". No-op for an unknown neighbor.
func (t *NeighborTable) RecordDelivery(id NodeID, ok bool) {
	t.mu.Lock()
	defer t.mu.Unlock()
	n, present := t.neighbors[id]
	if !present {
		return
	}
	x := 0.0
	if ok {
		x = 1.0
	}
	if !n.deliverInit {
		n.deliverEWMA, n.deliverInit = x, true
	} else {
		n.deliverEWMA = n.deliverEWMA*(1-deliverAlpha) + x*deliverAlpha
	}
	q := int(n.deliverEWMA*255 + 0.5)
	if q < 1 {
		q = 1
	} else if q > 255 {
		q = 255
	}
	n.QualityQ8 = uint8(q)
}

// SetDirection updates an existing neighbor's link direction in place. It is a no-op if the neighbor
// isn't in the table. Nodes call this before building an announce so the advertised direction
// (out/in/both) tracks the live link set rather than whatever it was at first connect.
func (t *NeighborTable) SetDirection(id NodeID, dir ConnDirection) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if n, ok := t.neighbors[id]; ok {
		n.Direction = dir
	}
}

func (t *NeighborTable) Get(id NodeID) (*Neighbor, bool) {
	t.mu.RLock()
	defer t.mu.RUnlock()
	n, ok := t.neighbors[id]
	return n, ok
}

func (t *NeighborTable) All() []Neighbor {
	t.mu.RLock()
	defer t.mu.RUnlock()
	out := make([]Neighbor, 0, len(t.neighbors))
	for _, n := range t.neighbors {
		out = append(out, *n)
	}
	return out
}

// AnnounceInfo snapshots the table as wire NeighborInfo entries for a v3 ANNOUNCE, capturing each
// link's RSSI, PHY in both directions, which side opened it, and how long ago it was last seen. RSSI
// is clamped to int8 (dBm); age is whole seconds since LastSeen. Entries are returned unsorted; the
// announce constructor sorts and de-duplicates them.
func (t *NeighborTable) AnnounceInfo() []NeighborInfo {
	t.mu.RLock()
	defer t.mu.RUnlock()
	now := time.Now()
	out := make([]NeighborInfo, 0, len(t.neighbors))
	for _, n := range t.neighbors {
		rssi := clampInt8(n.RSSI)
		var age uint32
		if d := now.Sub(n.LastSeen); d > 0 {
			age = uint32(d / time.Second)
		}
		out = append(out, NeighborInfo{
			ID:        n.ID,
			RSSI:      rssi,
			TxPHY:     n.TxPHY,
			RxPHY:     n.RxPHY,
			Dir:       n.Direction,
			AgeS:      age,
			Transport: TransportBLE, // every link in this table is a BLE peer link
			RSSIEWMA:  clampInt8(n.RSSIEWMA),
			QualityQ8: n.QualityQ8,
			LatencyMs: n.RTTms,
			// QueueQ8 (congestion) has no source yet; left 0 = unknown.
		})
	}
	return out
}

func (t *NeighborTable) Remove(id NodeID) {
	t.mu.Lock()
	defer t.mu.Unlock()
	delete(t.neighbors, id)
}

func (t *NeighborTable) IDs() []NodeID {
	t.mu.RLock()
	defer t.mu.RUnlock()
	ids := make([]NodeID, 0, len(t.neighbors))
	for id := range t.neighbors {
		ids = append(ids, id)
	}
	return ids
}

func (t *NeighborTable) Reap() {
	t.mu.Lock()
	defer t.mu.Unlock()
	now := time.Now()
	for id, n := range t.neighbors {
		if now.Sub(n.LastSeen) > t.timeout {
			delete(t.neighbors, id)
		}
	}
}

func (t *NeighborTable) Touch(id NodeID) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if n, ok := t.neighbors[id]; ok {
		n.LastSeen = time.Now()
	}
}

func (t *NeighborTable) reap() {
	ticker := time.NewTicker(10 * time.Second)
	for range ticker.C {
		t.Reap()
	}
}

// clampInt8 saturates a dBm value into the int8 range used on the wire.
func clampInt8(v int) int8 {
	switch {
	case v > 127:
		return 127
	case v < -128:
		return -128
	default:
		return int8(v)
	}
}
