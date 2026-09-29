"""Independent reference vectors for the crank tests (Python stdlib + `cryptography`).

- HEARTBEAT / BREAK / PLAN preimages per programs/heads-down/INTERFACE.md, SHA-256 digests.
- A P-256 signature over the heartbeat digest made by OpenSSL (via `cryptography`), in both
  low-S and high-S form, with the compressed public key.
- ema_ev per ml/forecaster/orelib.py::ema_ev_lamports (arbitrary precision).

Usage: python3 gen_vectors.py > interface.json
OpenSSL signs with a random nonce, so a rerun changes r and s (both stay valid); the
committed interface.json is the fixed vector the Rust tests read. Nothing here shares
code with the crank, which is the point: it is an independent implementation.
"""
import hashlib
import json
import struct

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

ALPHABET = b"123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"


def b58decode(s: str) -> bytes:
    n = 0
    for c in s.encode():
        n = n * 58 + ALPHABET.index(c)
    raw = n.to_bytes((n.bit_length() + 7) // 8, "big") if n else b""
    pad = len(s) - len(s.lstrip("1"))
    return b"\x00" * pad + raw


PROGRAM = b58decode("HDn4vgLWFLLdexKEwfZwRHjWtizNvdqFteLbMsE67F9p")
assert len(PROGRAM) == 32
RIG = bytes(range(1, 33))

N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551


def heartbeat(counter, shift_id, round_id, lease):
    return (b"HDv1" + PROGRAM + RIG + bytes([1]) + struct.pack("<QQQB", counter, shift_id, round_id, lease))


def brk(kind, counter, shift_id, reason):
    return b"HDv1" + PROGRAM + RIG + bytes([kind]) + struct.pack("<QQB", counter, shift_id, reason)


def plan(counter, max_ev, dig, split, solo, lease, flags, ws, we):
    return (b"HDv1" + PROGRAM + RIG + bytes([4]) + struct.pack("<QQQBBBBqq", counter, max_ev, dig, split, solo, lease, flags, ws, we))


def ema_ev(ema, pot):
    one_ore = 10**11
    return (ema * 6 * 500 * one_ore) // (5 * (500 * one_ore + pot))


hb = heartbeat(7, 3, 422_601, 2)
assert len(hb) == 94
br = brk(2, 8, 3, 1)
assert len(br) == 86
pl = plan(9, 700_000_000, 1_000_000, 15, 0, 3, 0, 1_790_000_000, 1_790_028_800)
assert len(pl) == 113
digest = hashlib.sha256(hb).digest()

# Fixed private key (test only).
d = int("C9AFA9D845BA75166B5C215767B1D6934E50C3DB36E89B127B8A622B120F6721", 16)
key = ec.derive_private_key(d, ec.SECP256R1())
pub = key.public_key().public_numbers()
compressed = bytes([2 | (pub.y & 1)]) + pub.x.to_bytes(32, "big")
# Signs SHA-256(digest): the message is the 32-byte digest, exactly like the precompile.
der = key.sign(digest, ec.ECDSA(hashes.SHA256()))
r, s = decode_dss_signature(der)
low_s = s if s <= N // 2 else N - s
high_s = N - low_s
key.public_key().verify(der, digest, ec.ECDSA(hashes.SHA256()))

out = {
    "program_id_hex": PROGRAM.hex(),
    "rig_hex": RIG.hex(),
    "heartbeat": {"counter": 7, "shift_id": 3, "round_id": 422601, "lease_rounds": 2,
                  "preimage_hex": hb.hex(), "digest_hex": digest.hex()},
    "break": {"kind": 2, "counter": 8, "shift_id": 3, "reason": 1,
              "preimage_hex": br.hex(), "digest_hex": hashlib.sha256(br).digest().hex()},
    "plan": {"counter": 9, "max_ev_cost": 700000000, "dig_lamports": 1000000, "split": 15, "solo": 0,
             "lease": 3, "flags": 0, "window_start": 1790000000, "window_end": 1790028800,
             "preimage_hex": pl.hex(), "digest_hex": hashlib.sha256(pl).digest().hex()},
    "p256": {"pubkey_hex": compressed.hex(), "r_hex": r.to_bytes(32, "big").hex(),
             "s_low_hex": low_s.to_bytes(32, "big").hex(), "s_high_hex": high_s.to_bytes(32, "big").hex()},
    "ema_ev": [
        {"ema": e, "pot": p, "ema_ev": ema_ev(e, p)}
        for (e, p) in [
            (918_782_720, 34_400_000_000_000),  # live 2026-09-29: 344 ORE pot
            (918_782_720, 0),
            (0, 0),
            (1, 0),
            (5, 0),
            (1_000_000_000, 50_000_000_000_000),
            (2**64 - 1, 2**64 - 1),
            (2**64 - 1, 10_000_000_000_000),
            (123_456_789, 2**64 - 1),
            (600_000_000, 12_345_678_901_234),
        ]
    ],
}
print(json.dumps(out, indent=2))
