package core

// MeshCore carrier framing (SPMC). A MESHCORE_PACKET datagram payload is, by
// default, the raw MeshCore over-the-air packet carried verbatim. When a bridge
// knows the external network a packet was heard on, it MAY prepend a small,
// self-describing frame so receivers can attribute the packet to that network
// immediately, without waiting for the bridge's signed ANNOUNCE. Absence of the
// frame == legacy raw payload (back-compat). See docs/PROTOCOL.md §13.1
// "Bridged MeshCore packet carriage (SPMC framing)".
//
// Layout (only when the bridge has a single network):
//
//	magic        [4]  ASCII "SPMC"                     // Sidepath-MeshCore bridged marker
//	version      [1]  = MeshCoreCarrierVersion
//	code_len     [1]  uint8 (1..MaxNetworkCodeBytes)
//	code_utf8    [code_len]
//	raw_meshcore [...]                                 // the original OTA packet, unchanged
//
// Critical invariant: dedup and CoreScope content hashing operate on the inner
// raw packet, never the framed payload — so the same packet bridged by a legacy
// (raw) bridge and a new (framed) bridge dedups identically across a mixed fleet.
//
// The embedded code is UNSIGNED — a carrier could mislabel it. That is acceptable
// because a malicious carrier can already forge announces; the signed-announce
// path remains the trustworthy source when present.

// MeshCoreCarrierVersion is the SPMC frame version this build emits and accepts.
const MeshCoreCarrierVersion = 1

// meshCoreCarrierMagic is the 4-byte marker that distinguishes a framed payload
// from a legacy raw MeshCore packet.
var meshCoreCarrierMagic = [4]byte{'S', 'P', 'M', 'C'}

// meshCoreCarrierHeader is the fixed-size prefix length: magic(4) + version(1) + code_len(1).
const meshCoreCarrierHeader = 6

// FrameMeshCorePacket prepends the SPMC frame to raw, tagging it with the network
// code. If code is empty or too long to frame, raw is returned unchanged (the
// legacy raw form), so callers can pass payloads through unconditionally.
func FrameMeshCorePacket(code string, raw []byte) []byte {
	cb := []byte(code)
	if len(cb) < 1 || len(cb) > MaxNetworkCodeBytes {
		return raw
	}
	out := make([]byte, 0, meshCoreCarrierHeader+len(cb)+len(raw))
	out = append(out, meshCoreCarrierMagic[:]...)
	out = append(out, MeshCoreCarrierVersion)
	out = append(out, byte(len(cb)))
	out = append(out, cb...)
	out = append(out, raw...)
	return out
}

// UnframeMeshCorePacket splits a MESHCORE_PACKET datagram payload into the
// embedded network code (if any) and the inner raw MeshCore packet. A payload
// without a valid SPMC frame is treated as legacy raw: ("", payload).
func UnframeMeshCorePacket(payload []byte) (code string, raw []byte) {
	if len(payload) < meshCoreCarrierHeader {
		return "", payload
	}
	if payload[0] != meshCoreCarrierMagic[0] || payload[1] != meshCoreCarrierMagic[1] ||
		payload[2] != meshCoreCarrierMagic[2] || payload[3] != meshCoreCarrierMagic[3] {
		return "", payload
	}
	if payload[4] != MeshCoreCarrierVersion {
		return "", payload
	}
	codeLen := int(payload[5])
	if codeLen < 1 || codeLen > MaxNetworkCodeBytes || meshCoreCarrierHeader+codeLen > len(payload) {
		return "", payload
	}
	code = string(payload[meshCoreCarrierHeader : meshCoreCarrierHeader+codeLen])
	raw = payload[meshCoreCarrierHeader+codeLen:]
	return code, raw
}
