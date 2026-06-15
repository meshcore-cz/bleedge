package core

import "testing"

// TestNeighborLiveStatsFeedAnnounce checks that recorded RSSI/RTT/delivery
// samples are smoothed into the neighbor and surface in the v3 announce info.
func TestNeighborLiveStatsFeedAnnounce(t *testing.T) {
	tbl := NewNeighborTable()
	id := testNodeID(1)
	tbl.Upsert(Neighbor{ID: id, TxPHY: PHY1M, RxPHY: PHY1M, Direction: DirectionOutgoing})

	tbl.SetRSSI(id, -60)
	tbl.SetRSSI(id, -80) // EWMA should land between the two samples
	tbl.RecordRTT(id, 40)
	// Mostly-successful deliveries should yield a high (but not maxed) quality.
	for i := 0; i < 6; i++ {
		tbl.RecordDelivery(id, true)
	}
	tbl.RecordDelivery(id, false)

	infos := tbl.AnnounceInfo()
	if len(infos) != 1 {
		t.Fatalf("want 1 neighbor info, got %d", len(infos))
	}
	ni := infos[0]
	if ni.Transport != TransportBLE {
		t.Errorf("transport should be BLE, got %v", ni.Transport)
	}
	if ni.RSSIEWMA >= -60 || ni.RSSIEWMA <= -80 {
		t.Errorf("smoothed RSSI should be between the samples, got %d", ni.RSSIEWMA)
	}
	if ni.LatencyMs != 40 {
		t.Errorf("first RTT sample should pass through, got %d", ni.LatencyMs)
	}
	if ni.QualityQ8 == 0 {
		t.Error("quality should be sampled (non-zero) after deliveries")
	}
	if !ni.Valid() {
		t.Error("emitted neighbor info must be valid")
	}
}

// TestQualityNeverStoredAsZero ensures a fully-failed link still reports "sampled"
// (>=1) so 0 keeps meaning "no data".
func TestQualityNeverStoredAsZero(t *testing.T) {
	tbl := NewNeighborTable()
	id := testNodeID(2)
	tbl.Upsert(Neighbor{ID: id})
	for i := 0; i < 20; i++ {
		tbl.RecordDelivery(id, false)
	}
	n, _ := tbl.Get(id)
	if n.QualityQ8 == 0 {
		t.Fatal("a sampled link must report quality >= 1, not 0 (which means unknown)")
	}
}
