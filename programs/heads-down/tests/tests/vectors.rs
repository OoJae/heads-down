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

#[test]
fn generation_is_deterministic() {
    let a = vectors::generate();
    let b = vectors::generate();
    assert_eq!(a.files, b.files);
}
