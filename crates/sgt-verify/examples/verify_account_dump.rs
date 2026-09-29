//! Verify an SGT from two `solana account <ADDRESS> --output json` dumps.
//!
//! ```text
//! solana account <SGT_MINT> --output json > mint.json
//! solana account <HOLDER_TOKEN_ACCOUNT> --output json > token_account.json
//! cargo run --example verify_account_dump -- mint.json token_account.json <HOLDER>
//! ```
//!
//! Runs the exact on-chain code path (`verify_sgt_raw`) against whatever
//! anchors this build has: mainnet by default, or the test group with
//! `--features test-group` and `SGT_VERIFY_TEST_GROUP/AUTHORITY` set at build
//! time. `scripts/make-test-sgt.sh` uses it to close the loop on a local
//! validator or devnet.

use std::{env, fs, process::ExitCode, str::FromStr};

use base64::{engine::general_purpose::STANDARD, Engine};
use pinocchio::Address;
use sgt_verify::{anchors, verify_sgt_raw, RawAccount};

struct Dump {
    address: Address,
    owner: Address,
    data: Vec<u8>,
}

fn load(path: &str) -> Result<Dump, String> {
    let text = fs::read_to_string(path).map_err(|e| format!("{path}: {e}"))?;
    let v: serde_json::Value = serde_json::from_str(&text).map_err(|e| format!("{path}: {e}"))?;
    let field = |p: &serde_json::Value| -> Result<String, String> {
        p.as_str()
            .map(str::to_owned)
            .ok_or_else(|| format!("{path}: missing field"))
    };
    let account = &v["account"];
    if account["data"][1] != "base64" {
        return Err(format!("{path}: expected base64 data (use --output json)"));
    }
    let parse = |s: String| Address::from_str(&s).map_err(|e| format!("{path}: {s}: {e:?}"));
    Ok(Dump {
        address: parse(field(&v["pubkey"])?)?,
        owner: parse(field(&account["owner"])?)?,
        data: STANDARD
            .decode(field(&account["data"][0])?)
            .map_err(|e| format!("{path}: {e}"))?,
    })
}

fn main() -> ExitCode {
    let args: Vec<String> = env::args().skip(1).collect();
    let [mint, token_account, holder] = args.as_slice() else {
        eprintln!("usage: verify_account_dump <mint.json> <token_account.json> <holder>");
        return ExitCode::from(2);
    };
    let (mint, token_account) = match (load(mint), load(token_account)) {
        (Ok(m), Ok(t)) => (m, t),
        (Err(e), _) | (_, Err(e)) => {
            eprintln!("{e}");
            return ExitCode::from(2);
        }
    };
    let Ok(holder) = Address::from_str(holder) else {
        eprintln!("invalid holder address");
        return ExitCode::from(2);
    };

    println!(
        "anchors: group {} authority {}{}",
        anchors::SGT_GROUP,
        anchors::SGT_AUTHORITY,
        if anchors::IS_TEST_GROUP_BUILD { " (test-group build)" } else { " (mainnet)" }
    );
    match verify_sgt_raw(
        RawAccount {
            address: &token_account.address,
            owner: &token_account.owner,
            data: &token_account.data,
        },
        RawAccount {
            address: &mint.address,
            owner: &mint.owner,
            data: &mint.data,
        },
        &holder,
    ) {
        Ok(info) => {
            println!(
                "VALID SGT: mint {} member #{} held by {} ({})",
                info.mint,
                info.member_number,
                holder,
                if info.frozen { "frozen" } else { "not frozen" }
            );
            ExitCode::SUCCESS
        }
        Err(e) => {
            println!("REJECTED: {e:?} (code {:#x})", e.program_error_code());
            ExitCode::from(1)
        }
    }
}
