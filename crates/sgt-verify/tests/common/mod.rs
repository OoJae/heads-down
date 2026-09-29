//! Shared helpers for the fixture and spoof suites: loading the real mainnet
//! accounts, a TLV rebuilder that is independent of the crate under test, and
//! a host-side `AccountView` harness.
#![allow(dead_code)]

use std::{fs, path::PathBuf, str::FromStr, sync::OnceLock};

use base64::{engine::general_purpose::STANDARD, Engine};
use pinocchio::{
    account::{RuntimeAccount, NOT_BORROWED},
    AccountView, Address,
};
use serde_json::Value;
use sgt_verify::{verify_sgt_raw, RawAccount, SgtError, SgtInfo};

pub const TOKEN_2022: Address = Address::from_str_const("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb");
pub const SPL_TOKEN: Address = Address::from_str_const("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
pub const SYSTEM_PROGRAM: Address = Address::from_str_const("11111111111111111111111111111111");
pub const SGT_GROUP: Address = Address::from_str_const("GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te");
pub const SGT_AUTHORITY: Address = Address::from_str_const("GT2zuHVaZQYZSyQMgJPLzvkmyztfyXg2NJunqFp4p3A4");

/// One account as dumped by `scripts/fetch_fixtures.py`.
#[derive(Clone, Debug)]
pub struct Fixture {
    pub address: Address,
    pub owner: Address,
    pub lamports: u64,
    pub data: Vec<u8>,
}

impl Fixture {
    pub fn raw(&self) -> RawAccount<'_> {
        RawAccount {
            address: &self.address,
            owner: &self.owner,
            data: &self.data,
        }
    }
}

/// A real SGT: mint, holder token account, and what the RPC's own
/// spl-token-2022 parser said about them.
#[derive(Clone, Debug)]
pub struct RealSgt {
    pub label: String,
    pub mint: Fixture,
    pub token_account: Fixture,
    pub holder: Address,
    pub member_number: u64,
    pub state: String,
    pub mint_extensions: Vec<String>,
    pub token_account_extensions: Vec<String>,
    pub mint_authority: Address,
    pub freeze_authority: Address,
    pub supply: u64,
    pub decimals: u8,
}

impl RealSgt {
    pub fn verify(&self) -> Result<SgtInfo, SgtError> {
        verify_sgt_raw(self.token_account.raw(), self.mint.raw(), &self.holder)
    }

    pub fn verify_with(&self, token_account: &Fixture, mint: &Fixture) -> Result<SgtInfo, SgtError> {
        verify_sgt_raw(token_account.raw(), mint.raw(), &self.holder)
    }
}

pub fn fixtures_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("fixtures")
}

fn json(path: &PathBuf) -> Value {
    let text = fs::read_to_string(path).unwrap_or_else(|e| panic!("read {}: {e}", path.display()));
    serde_json::from_str(&text).unwrap()
}

fn addr(v: &Value) -> Address {
    Address::from_str(v.as_str().unwrap()).unwrap()
}

pub fn load_fixture(rel: &str) -> Fixture {
    let v = json(&fixtures_dir().join(rel));
    let account = &v["account"];
    assert_eq!(account["data"][1], "base64");
    Fixture {
        address: addr(&v["pubkey"]),
        owner: addr(&account["owner"]),
        lamports: account["lamports"].as_u64().unwrap(),
        data: STANDARD.decode(account["data"][0].as_str().unwrap()).unwrap(),
    }
}

/// All real SGT fixtures (parsed once per test binary).
pub fn real_sgts() -> Vec<RealSgt> {
    static CACHE: OnceLock<Vec<RealSgt>> = OnceLock::new();
    CACHE.get_or_init(load_real_sgts).clone()
}

fn load_real_sgts() -> Vec<RealSgt> {
    let manifest = json(&fixtures_dir().join("manifest.json"));
    let strings = |v: &Value| -> Vec<String> {
        v.as_array()
            .unwrap()
            .iter()
            .map(|s| s.as_str().unwrap().to_owned())
            .collect()
    };
    manifest["sgts"]
        .as_array()
        .unwrap()
        .iter()
        .map(|s| {
            let label = s["label"].as_str().unwrap().to_owned();
            let mint = load_fixture(&format!("{label}/mint.json"));
            let token_account = load_fixture(&format!("{label}/token_account.json"));
            assert_eq!(mint.address, addr(&s["mint"]));
            assert_eq!(token_account.address, addr(&s["token_account"]));
            RealSgt {
                label,
                mint,
                token_account,
                holder: addr(&s["holder"]),
                member_number: s["member_number"].as_u64().unwrap(),
                state: s["token_account_state"].as_str().unwrap().to_owned(),
                mint_extensions: strings(&s["mint_extensions"]),
                token_account_extensions: strings(&s["token_account_extensions"]),
                mint_authority: addr(&s["mint_authority"]),
                freeze_authority: addr(&s["freeze_authority"]),
                supply: s["supply"].as_str().unwrap().parse().unwrap(),
                decimals: u8::try_from(s["decimals"].as_u64().unwrap()).unwrap(),
            }
        })
        .collect()
}

/// The member-20 SGT, the example Solana Mobile's docs use.
pub fn member_20() -> RealSgt {
    real_sgts()
        .into_iter()
        .find(|s| s.label == "member-20")
        .expect("member-20 fixture")
}

pub fn group_fixture() -> Fixture {
    load_fixture("group.json")
}

/// Map the RPC's camelCase extension names to ExtensionType discriminants,
/// as listed in spl-token-2022's `ExtensionType` enum.
pub fn extension_number(name: &str) -> u16 {
    match name {
        "mintCloseAuthority" => 3,
        "immutableOwner" => 7,
        "permanentDelegate" => 12,
        "metadataPointer" => 18,
        "tokenMetadata" => 19,
        "groupPointer" => 20,
        "tokenGroup" => 21,
        "groupMemberPointer" => 22,
        "tokenGroupMember" => 23,
        other => panic!("unmapped extension {other}"),
    }
}

// --- independent TLV handling ------------------------------------------------

pub const TLV_START: usize = 166;

/// Split extended-account bytes into TLV entries with a deliberately naive
/// walker (independent of `sgt_verify::tlv`). Only for well-formed inputs.
pub fn split_tlv(data: &[u8]) -> Vec<(u16, Vec<u8>)> {
    let mut out = Vec::new();
    let mut i = TLV_START;
    while i + 4 <= data.len() {
        let ty = u16::from_le_bytes([data[i], data[i + 1]]);
        if ty == 0 {
            break;
        }
        let len = u16::from_le_bytes([data[i + 2], data[i + 3]]) as usize;
        out.push((ty, data[i + 4..i + 4 + len].to_vec()));
        i += 4 + len;
    }
    out
}

/// Rebuild an extended account from its first 166 bytes plus `entries`.
pub fn with_tlv(data: &[u8], entries: &[(u16, Vec<u8>)]) -> Vec<u8> {
    let mut out = data[..TLV_START].to_vec();
    for (ty, value) in entries {
        out.extend_from_slice(&ty.to_le_bytes());
        out.extend_from_slice(&u16::try_from(value.len()).unwrap().to_le_bytes());
        out.extend_from_slice(value);
    }
    out
}

/// Byte offset of the value of extension `ty` inside `data`.
pub fn value_offset(data: &[u8], ty: u16) -> usize {
    let mut i = TLV_START;
    loop {
        let t = u16::from_le_bytes([data[i], data[i + 1]]);
        let len = u16::from_le_bytes([data[i + 2], data[i + 3]]) as usize;
        if t == ty {
            return i + 4;
        }
        assert_ne!(t, 0, "extension {ty} not found");
        i += 4 + len;
    }
}

/// Byte offset of the TLV header of extension `ty` inside `data`.
pub fn header_offset(data: &[u8], ty: u16) -> usize {
    value_offset(data, ty) - 4
}

// --- host AccountView harness -------------------------------------------------

/// Owns memory laid out the way the SBF loader lays out an input account:
/// a `RuntimeAccount` header immediately followed by the data, 8-aligned.
pub struct HostAccount {
    buf: Vec<u64>,
}

impl HostAccount {
    pub fn new(address: &Address, owner: &Address, lamports: u64, data: &[u8]) -> Self {
        let header = std::mem::size_of::<RuntimeAccount>();
        let words = (header + data.len()).div_ceil(8) + 1;
        let mut buf = vec![0u64; words];
        let base = buf.as_mut_ptr().cast::<u8>();
        // SAFETY: `buf` is 8-aligned and large enough for the header plus
        // data; the two regions do not overlap `data`.
        unsafe {
            base.cast::<RuntimeAccount>().write(RuntimeAccount {
                borrow_state: NOT_BORROWED,
                is_signer: 0,
                is_writable: 0,
                executable: 0,
                padding: [0; 4],
                address: address.clone(),
                owner: owner.clone(),
                lamports,
                data_len: data.len() as u64,
            });
            std::ptr::copy_nonoverlapping(data.as_ptr(), base.add(header), data.len());
        }
        Self { buf }
    }

    pub fn from_fixture(f: &Fixture) -> Self {
        Self::new(&f.address, &f.owner, f.lamports, &f.data)
    }

    /// A view into this account. It must not outlive `self`.
    pub fn view(&mut self) -> AccountView {
        // SAFETY: the buffer holds a valid RuntimeAccount followed by
        // `data_len` bytes, and lives as long as `self`.
        unsafe { AccountView::new_unchecked(self.buf.as_mut_ptr().cast::<RuntimeAccount>()) }
    }
}

// --- deterministic PRNG for mutation sweeps ----------------------------------

pub struct XorShift(pub u64);

impl XorShift {
    pub fn next_u64(&mut self) -> u64 {
        let mut x = self.0;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        self.0 = x;
        x
    }

    pub fn below(&mut self, n: usize) -> usize {
        (self.next_u64() % n as u64) as usize
    }
}
