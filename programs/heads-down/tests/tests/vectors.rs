//! Golden vectors (`programs/heads-down/vectors/`): regenerate them by
//! executing every instruction on the pinned LiteSVM fork and fail if the
//! committed files differ by a single byte.
//!
//! Update after an intentional contract change:
//! `HD_WRITE_VECTORS=1 cargo +1.97.1 test -p heads-down-tests --test vectors`
//! (or `bash scripts/vectors.sh`), then review the diff.

use heads_down_tests::{root, vectors};

#[test]
fn golden_vectors_match_the_committed_files() {
    let golden = vectors::generate();
    let dir = root().join("vectors");
    let write = std::env::var("HD_WRITE_VECTORS").is_ok_and(|v| v == "1");
    let mut drift = vec![];
    for (name, contents) in &golden.files {
        let path = dir.join(name);
        if write {
            std::fs::create_dir_all(&dir).unwrap();
            std::fs::write(&path, contents).unwrap();
            continue;
        }
        let committed = std::fs::read_to_string(&path).unwrap_or_default();
        if &committed != contents {
            let line = committed
                .lines()
                .zip(contents.lines())
                .position(|(a, b)| a != b)
                .unwrap_or(committed.lines().count().min(contents.lines().count()))
                + 1;
            drift.push(format!("{name} (first difference at line {line})"));
        }
    }
    assert!(
        drift.is_empty(),
        "golden vectors drifted: {drift:?}\nIf the contract change is intentional, run \
         HD_WRITE_VECTORS=1 cargo +1.97.1 test -p heads-down-tests --test vectors and review \
         the diff (INTERFACE.md must change with it)."
    );
}

/// Table rows (`| a | b | ... |`, header and separator skipped) of the
/// INTERFACE.md section whose heading starts with `heading`.
fn table_rows(doc: &str, heading: &str) -> Vec<Vec<String>> {
    let start = doc
        .find(heading)
        .unwrap_or_else(|| panic!("{heading} missing"));
    let body = &doc[start + heading.len()..];
    let end = body.find("\n## ").unwrap_or(body.len());
    body[..end]
        .lines()
        .filter(|l| l.starts_with("| ") && !l.starts_with("| ---") && !l.starts_with("|---"))
        .map(|l| {
            l.trim_matches('|')
                .split('|')
                .map(|c| c.trim().to_string())
                .collect()
        })
        .filter(|cells: &Vec<String>| cells[0].parse::<u32>().is_ok())
        .collect()
}

#[test]
fn interface_md_tables_match_the_program() {
    use heads_down_tests::hd::{error::HdError, events};
    let doc = std::fs::read_to_string(root().join("INTERFACE.md")).unwrap();

    // §7 events: tag, name and exact length.
    let rows = table_rows(&doc, "## 7. Events");
    assert_eq!(rows.len(), 10, "one row per event tag");
    for r in &rows {
        let tag: usize = r[0].parse().unwrap();
        let len: usize = r[3].parse().unwrap();
        assert_eq!(len, events::LEN[tag], "INTERFACE.md event tag {tag} length");
        assert_eq!(r[1], vectors::event_name(tag as u8), "event tag {tag} name");
    }

    // §8 errors: every code 0..=31 with the program's variant name.
    let all = [
        HdError::InvalidInstruction,
        HdError::CostGate,
        HdError::InvalidExecutor,
        HdError::InvalidOreAccount,
        HdError::InvalidAccountTag,
        HdError::Unauthorized,
        HdError::InvalidHeartbeat,
        HdError::StaleHeartbeat,
        HdError::LeaseExpired,
        HdError::AlreadyDugRound,
        HdError::CapsExpired,
        HdError::OutsideWindow,
        HdError::BudgetExhausted,
        HdError::RigNotArmed,
        HdError::RigFrozen,
        HdError::PlanExceedsCaps,
        HdError::InvalidSgt,
        HdError::SeatTaken,
        HdError::Paused,
        HdError::TimelockNotElapsed,
        HdError::MathOverflow,
        HdError::InvalidAttestation,
        HdError::DuplicateRig,
        HdError::StrategyMismatch,
        HdError::InvalidRigState,
        HdError::RoundNotActive,
        HdError::MinerNotCheckpointed,
        HdError::MotherlodeCondition,
        HdError::InsufficientAutomationBalance,
        HdError::OreNoOp,
        HdError::FocusOnly,
        HdError::ExecutorUnderfunded,
    ];
    let rows = table_rows(&doc, "## 8. Errors");
    assert_eq!(rows.len(), all.len(), "one row per error code");
    for (e, r) in all.iter().zip(&rows) {
        assert_eq!(r[0].parse::<u32>().unwrap(), e.code());
        assert_eq!(r[1], format!("{e:?}"), "error {}", e.code());
    }

    // §5 instruction table: name and data lengths vs the executed vectors.
    let ix: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(root().join("vectors/instructions.json")).unwrap(),
    )
    .unwrap();
    let vectors = ix["instructions"].as_array().unwrap();
    let rows = table_rows(&doc, "## 5. Instructions");
    assert_eq!(rows.len(), 15, "one row per instruction tag");
    for r in &rows {
        let tag: u64 = r[0].parse().unwrap();
        let name = r[1].trim_matches('`');
        let mine: Vec<&serde_json::Value> = vectors.iter().filter(|v| v["tag"] == tag).collect();
        assert!(!mine.is_empty(), "tag {tag} has vectors");
        for v in &mine {
            assert_eq!(v["instruction"], name, "tag {tag} name");
            let len = v["data_len"].as_u64().unwrap();
            if r[3] == "2 + 20n" {
                assert_eq!(len, 2 + 20 * v["args"]["n"].as_u64().unwrap(), "{name}");
            } else {
                let allowed: Vec<u64> =
                    r[3].split('/').map(|x| x.trim().parse().unwrap()).collect();
                assert!(allowed.contains(&len), "{name}: {len} not in {allowed:?}");
            }
        }
    }
}

/// Table rows of the subsection whose heading starts with `heading`, up to
/// the next heading of any level (§11 has `###` subsections).
fn subsection_rows(doc: &str, heading: &str) -> Vec<Vec<String>> {
    let start = doc
        .find(heading)
        .unwrap_or_else(|| panic!("{heading} missing"));
    let body = &doc[start + heading.len()..];
    let end = body.find("\n#").unwrap_or(body.len());
    body[..end]
        .lines()
        .filter(|l| l.starts_with("| ") && !l.starts_with("| ---") && !l.starts_with("|---"))
        .map(|l| {
            l.trim_matches('|')
                .split('|')
                .map(|c| c.trim().to_string())
                .collect()
        })
        .filter(|cells: &Vec<String>| cells[0].parse::<u32>().is_ok())
        .collect()
}

#[test]
fn interface_md_v12_tables_match_the_program() {
    use heads_down_tests::hd::{error::HdError, events};
    let doc = std::fs::read_to_string(root().join("INTERFACE.md")).unwrap();

    // §11.9 events 11..=23: name and exact length.
    let rows = subsection_rows(&doc, "### 11.9 Events");
    assert_eq!(rows.len(), 13, "one row per v1.2 event tag");
    for (i, r) in rows.iter().enumerate() {
        let tag: usize = r[0].parse().unwrap();
        assert_eq!(tag, 11 + i);
        assert_eq!(r[1], vectors::event_name(tag as u8), "event tag {tag} name");
        let len: usize = r[3].parse().unwrap();
        assert_eq!(len, events::LEN[tag], "INTERFACE.md event tag {tag} length");
    }

    // §11.10 errors 32..=48 with the program's variant names.
    let all = [
        HdError::InvalidTokenAccount,
        HdError::AmountOutOfRange,
        HdError::InvalidStackParams,
        HdError::StackJoinClosed,
        HdError::StackIneligible,
        HdError::StackNotEnded,
        HdError::InvalidStackState,
        HdError::StackSeatMismatch,
        HdError::StackShiftMismatch,
        HdError::StackLeaseTooLong,
        HdError::StackSeatBroken,
        HdError::BondNotResolvable,
        HdError::GiftNotClaimable,
        HdError::GiftExpiry,
        HdError::AuctionEmpty,
        HdError::PriceAboveMax,
        HdError::BuryMismatch,
    ];
    let rows = subsection_rows(&doc, "### 11.10 Errors");
    assert_eq!(rows.len(), all.len(), "one row per v1.2 error code");
    for (e, r) in all.iter().zip(&rows) {
        assert_eq!(r[0].parse::<u32>().unwrap(), e.code());
        assert_eq!(r[1], format!("{e:?}"), "error {}", e.code());
    }

    // §11.4 instructions 15..=27: name and data length vs the executed vectors.
    let ix: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(root().join("vectors/instructions.json")).unwrap(),
    )
    .unwrap();
    let vectors = ix["instructions"].as_array().unwrap();
    let rows = subsection_rows(&doc, "### 11.4 Instructions");
    assert_eq!(rows.len(), 13, "one row per v1.2 instruction tag");
    for (i, r) in rows.iter().enumerate() {
        let tag: u64 = r[0].parse().unwrap();
        assert_eq!(tag, 15 + i as u64);
        let name = r[1].trim_matches('`');
        let mine: Vec<&serde_json::Value> = vectors.iter().filter(|v| v["tag"] == tag).collect();
        assert!(!mine.is_empty(), "tag {tag} has vectors");
        for v in &mine {
            assert_eq!(v["instruction"], name, "tag {tag} name");
            let len = v["data_len"].as_u64().unwrap();
            if r[3] == "2 + 20n" {
                assert_eq!(len, 2 + 20 * v["args"]["n"].as_u64().unwrap(), "{name}");
            } else {
                assert_eq!(len, r[3].parse::<u64>().unwrap(), "{name}");
            }
        }
    }
}

#[test]
fn generation_is_deterministic() {
    let a = vectors::generate();
    let b = vectors::generate();
    assert_eq!(a.files, b.files);
}
