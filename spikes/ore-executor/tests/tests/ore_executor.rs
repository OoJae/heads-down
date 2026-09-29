//! Spike 1(a): run the real mainnet ORE program inside LiteSVM (a local fork)
//! and prove that a program-derived executor can deploy for a user's ORE
//! Automation via CPI, while nobody else can, and that the Heads Down program
//! enforces the production-cost gate that ORE itself does not.
//!
//! Fixtures come from `../fetch-fixtures.sh` (mainnet dumps; gitignored).

use {
    base64::Engine,
    litesvm::LiteSVM,
    solana_account::Account,
    solana_address::Address,
    solana_instruction::{AccountMeta, Instruction},
    solana_keypair::Keypair,
    solana_signer::Signer,
    solana_transaction::Transaction,
    std::path::PathBuf,
};

const ORE: Address = Address::from_str_const("oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv");
const BOARD: Address = Address::from_str_const("BrcSxdp1nXFzou1YyDnQJcPNBNHgoypZmTsyKBSLLXzi");
const CONFIG: Address = Address::from_str_const("9c9X7aDRAF41faiDs94ELjT19UrGnn72wBW9hPsS4Awy");
const TREASURY: Address = Address::from_str_const("45db2FSR4mcXdSVVZbKbwojU6uYDpMyhpEi7cC8nHaWG");
const VAR: Address = Address::from_str_const("BWCaDY96Xe4WkFq1M7UiCCRcChsJ3p51L5KrGzhxgm2E");
const SYSTEM: Address = Address::from_str_const("11111111111111111111111111111111");
const HD: Address = Address::new_from_array([0x4d; 32]); // fixed test program id

const LAMPORTS_PER_SOL: u64 = 1_000_000_000;
const TILE_AMOUNT: u64 = 1_000_000; // 0.001 SOL per tile
const EXECUTOR_FEE: u64 = 5_000; // Discretionary fixed fee per round
const CHECKPOINT_FEE: u64 = 10_000;

// Offsets (steel accounts: 8-byte discriminator first).
const BOARD_START_SLOT: usize = 16;
const BOARD_EMA: usize = 32;
const AUTOMATION_BALANCE: usize = 8 + 8 + 32;
const MINER_CHECKPOINT_FEE: usize = 8 + 32 + 8 + 8;
const ROUND_DEPLOYED: usize = 16;

fn fixtures() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../fixtures")
}

fn read_u64(data: &[u8], off: usize) -> u64 {
    u64::from_le_bytes(data[off..off + 8].try_into().unwrap())
}

fn load_account(address: &str) -> Account {
    let raw = std::fs::read_to_string(fixtures().join(format!("{address}.json")))
        .unwrap_or_else(|_| panic!("missing fixture {address}.json — run ../fetch-fixtures.sh"));
    let v: serde_json::Value = serde_json::from_str(&raw).unwrap();
    let a = &v["account"];
    Account {
        lamports: a["lamports"].as_u64().unwrap(),
        data: base64::engine::general_purpose::STANDARD
            .decode(a["data"][0].as_str().unwrap())
            .unwrap(),
        owner: Address::from_str_const(a["owner"].as_str().unwrap()),
        executable: false,
        rent_epoch: u64::MAX,
    }
}

fn pda(seeds: &[&[u8]], program: &Address) -> (Address, u8) {
    Address::find_program_address(seeds, program)
}

struct Fork {
    svm: LiteSVM,
    user: Keypair,
    round: Address,
    entropy: Address,
    executor: Address,
    executor_bump: u8,
    ema: u64,
}

impl Fork {
    fn new() -> Self {
        let mut svm = LiteSVM::new();
        let f = fixtures();
        let entropy = Address::from_str_const(
            std::fs::read_to_string(f.join("entropy_program_id.txt")).unwrap().trim(),
        );
        let round_addr = std::fs::read_to_string(f.join("round_address.txt")).unwrap();
        let round = Address::from_str_const(round_addr.trim());

        svm.add_program(ORE, &std::fs::read(f.join("ore.so")).unwrap()).unwrap();
        svm.add_program(entropy, &std::fs::read(f.join("entropy.so")).unwrap()).unwrap();
        let hd_so = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../target/deploy/hd_spike.so");
        svm.add_program(HD, &std::fs::read(hd_so).expect("build program first: cargo build-sbf"))
            .unwrap();

        for (addr, key) in [
            (BOARD, "BrcSxdp1nXFzou1YyDnQJcPNBNHgoypZmTsyKBSLLXzi"),
            (CONFIG, "9c9X7aDRAF41faiDs94ELjT19UrGnn72wBW9hPsS4Awy"),
            (TREASURY, "45db2FSR4mcXdSVVZbKbwojU6uYDpMyhpEi7cC8nHaWG"),
            (VAR, "BWCaDY96Xe4WkFq1M7UiCCRcChsJ3p51L5KrGzhxgm2E"),
            (round, round_addr.trim()),
        ] {
            svm.set_account(addr, load_account(key)).unwrap();
        }

        // Put the clock inside the live round window captured in the fixture.
        let board = svm.get_account(&BOARD).unwrap();
        let start_slot = read_u64(&board.data, BOARD_START_SLOT);
        let ema = read_u64(&board.data, BOARD_EMA);
        svm.warp_to_slot(start_slot + 10);

        let user = Keypair::new();
        svm.airdrop(&user.pubkey(), 10 * LAMPORTS_PER_SOL).unwrap();
        let (executor, executor_bump) = pda(&[b"executor"], &HD);

        let mut fork = Fork { svm, user, round, entropy, executor, executor_bump, ema };
        // Fund the executor PDA as a data-less, System-owned account.
        let ix = system_transfer(&fork.user.pubkey(), &fork.executor, LAMPORTS_PER_SOL / 100);
        fork.send(&[ix], &[]).expect("fund executor");
        fork
    }

    fn automation(&self) -> Address {
        pda(&[b"automation", self.user.pubkey().as_ref()], &ORE).0
    }

    fn miner(&self) -> Address {
        pda(&[b"miner", self.user.pubkey().as_ref()], &ORE).0
    }

    fn send(
        &mut self,
        ixs: &[Instruction],
        extra_signers: &[&Keypair],
    ) -> Result<litesvm::types::TransactionMetadata, litesvm::types::FailedTransactionMetadata> {
        let mut signers: Vec<&Keypair> = vec![&self.user];
        signers.extend_from_slice(extra_signers);
        let payer = signers[0].pubkey();
        let tx = Transaction::new_signed_with_payer(
            ixs,
            Some(&payer),
            &signers,
            self.svm.latest_blockhash(),
        );
        let res = self.svm.send_transaction(tx);
        self.svm.expire_blockhash();
        res
    }

    /// ORE AutomateV2: executor = Heads Down PDA, Discretionary (2), fixed fee.
    fn automate_ix(&self, deposit: u64) -> Instruction {
        let mut data = vec![0u8]; // Automate / AutomateV2 tag
        data.extend_from_slice(&TILE_AMOUNT.to_le_bytes()); // amount per tile
        data.extend_from_slice(&deposit.to_le_bytes()); // deposit
        data.extend_from_slice(&EXECUTOR_FEE.to_le_bytes()); // fee
        data.extend_from_slice(&0u64.to_le_bytes()); // mask (unused for Discretionary)
        data.push(2); // strategy = Discretionary
        data.extend_from_slice(&0u64.to_le_bytes()); // reload = false
        // conditions: max_production_cost, min_motherlode, max_motherlode, split, solo, buffer
        data.extend_from_slice(&u64::MAX.to_le_bytes());
        data.extend_from_slice(&0u16.to_le_bytes());
        data.extend_from_slice(&u16::MAX.to_le_bytes());
        data.extend_from_slice(&0u16.to_le_bytes());
        data.extend_from_slice(&0u16.to_le_bytes());
        data.extend_from_slice(&0u64.to_le_bytes());
        assert_eq!(data.len(), 66);
        Instruction {
            program_id: ORE,
            accounts: vec![
                AccountMeta::new(self.user.pubkey(), true),
                AccountMeta::new(self.automation(), false),
                AccountMeta::new(self.executor, false),
                AccountMeta::new(self.miner(), false),
                AccountMeta::new_readonly(SYSTEM, false),
            ],
            data,
        }
    }

    fn ore_accounts(&self, signer: Address, signer_is_signer: bool) -> Vec<AccountMeta> {
        vec![
            AccountMeta::new(signer, signer_is_signer),
            AccountMeta::new(self.user.pubkey(), false),
            AccountMeta::new(self.automation(), false),
            AccountMeta::new(BOARD, false),
            AccountMeta::new(CONFIG, false),
            AccountMeta::new(self.miner(), false),
            AccountMeta::new(self.round, false),
            AccountMeta::new(TREASURY, false),
            AccountMeta::new_readonly(SYSTEM, false),
            AccountMeta::new_readonly(ORE, false),
            AccountMeta::new(VAR, false),
            AccountMeta::new_readonly(self.entropy, false),
        ]
    }

    fn dig_ix(&self, amount: u64, mask: u32, max_cost: u64, bump: u8, executor: Address) -> Instruction {
        let mut data = vec![1u8];
        data.extend_from_slice(&amount.to_le_bytes());
        data.extend_from_slice(&mask.to_le_bytes());
        data.extend_from_slice(&max_cost.to_le_bytes());
        data.push(bump);
        Instruction { program_id: HD, accounts: self.ore_accounts(executor, false), data }
    }

    fn dig(&mut self, amount: u64, mask: u32, max_cost: u64) -> Result<u64, String> {
        let ix = self.dig_ix(amount, mask, max_cost, self.executor_bump, self.executor);
        self.send(&[ix], &[])
            .map(|m| m.compute_units_consumed)
            .map_err(|e| format!("{:?} logs={:?}", e.err, e.meta.logs))
    }

    fn balance(&self, a: &Address) -> u64 {
        self.svm.get_account(a).map(|a| a.lamports).unwrap_or(0)
    }

    fn data(&self, a: &Address) -> Vec<u8> {
        self.svm.get_account(a).unwrap().data
    }
}

fn system_transfer(from: &Address, to: &Address, lamports: u64) -> Instruction {
    let mut data = 2u32.to_le_bytes().to_vec();
    data.extend_from_slice(&lamports.to_le_bytes());
    Instruction {
        program_id: SYSTEM,
        accounts: vec![AccountMeta::new(*from, true), AccountMeta::new(*to, false)],
        data,
    }
}

const FIVE_TILES: u32 = 0b11111;

#[test]
fn pda_executor_deploys_for_user_automation() {
    let mut f = Fork::new();
    let deposit = LAMPORTS_PER_SOL / 10;
    f.send(&[f.automate_ix(deposit)], &[]).expect("automate");

    let auto_before = read_u64(&f.data(&f.automation()), AUTOMATION_BALANCE);
    assert_eq!(auto_before, deposit);
    let round_before = f.data(&f.round);
    let exec_before = f.balance(&f.executor);

    let cu = f.dig(TILE_AMOUNT, FIVE_TILES, u64::MAX).expect("dig via PDA executor");
    println!("dig CU consumed (1 rig, 5 tiles): {cu}");

    let auto_after = read_u64(&f.data(&f.automation()), AUTOMATION_BALANCE);
    assert_eq!(auto_after, deposit - 5 * TILE_AMOUNT - EXECUTOR_FEE, "automation debited");
    let round_after = f.data(&f.round);
    for tile in 0..5 {
        let off = ROUND_DEPLOYED + tile * 8;
        assert_eq!(
            read_u64(&round_after, off) - read_u64(&round_before, off),
            TILE_AMOUNT,
            "tile {tile} credited on the live board"
        );
    }
    for tile in 5..25 {
        let off = ROUND_DEPLOYED + tile * 8;
        assert_eq!(read_u64(&round_after, off), read_u64(&round_before, off));
    }
    assert_eq!(f.balance(&f.executor) - exec_before, EXECUTOR_FEE, "executor PDA earned the fixed fee");
    let exec_acct = f.svm.get_account(&f.executor).unwrap();
    assert_eq!(exec_acct.owner, SYSTEM, "executor stays a System-owned account");
    assert!(exec_acct.data.is_empty(), "executor stays data-less");
}

#[test]
fn program_enforces_production_cost_gate() {
    let mut f = Fork::new();
    f.send(&[f.automate_ix(LAMPORTS_PER_SOL / 10)], &[]).unwrap();
    let ema = f.ema;
    assert!(ema > 0, "fixture board has a production-cost EMA");
    let err = f.dig(TILE_AMOUNT, FIVE_TILES, ema - 1).unwrap_err();
    assert!(err.contains("Custom(1)"), "CostGate expected, got {err}");
    // At or above the EMA it deploys.
    f.dig(TILE_AMOUNT, FIVE_TILES, ema).expect("gate open at ema");
}

#[test]
fn attacker_cannot_deploy_for_user_directly() {
    let mut f = Fork::new();
    f.send(&[f.automate_ix(LAMPORTS_PER_SOL / 10)], &[]).unwrap();
    let attacker = Keypair::new();
    f.svm.airdrop(&attacker.pubkey(), LAMPORTS_PER_SOL).unwrap();
    let mut data = vec![6u8];
    data.extend_from_slice(&TILE_AMOUNT.to_le_bytes());
    data.extend_from_slice(&FIVE_TILES.to_le_bytes());
    let ix = Instruction { program_id: ORE, accounts: f.ore_accounts(attacker.pubkey(), true), data };
    let tx = Transaction::new_signed_with_payer(&[ix], Some(&attacker.pubkey()), &[&attacker], f.svm.latest_blockhash());
    assert!(f.svm.send_transaction(tx).is_err(), "only the configured executor may deploy");
}

#[test]
fn wrong_executor_or_fake_board_rejected() {
    let mut f = Fork::new();
    f.send(&[f.automate_ix(LAMPORTS_PER_SOL / 10)], &[]).unwrap();

    // A different PDA of our program (wrong seeds) is not the executor.
    let (other, other_bump) = pda(&[b"not-executor"], &HD);
    let ix = f.dig_ix(TILE_AMOUNT, FIVE_TILES, u64::MAX, other_bump, other);
    let err = f.send(&[ix], &[]).unwrap_err();
    assert!(format!("{:?}", err.err).contains("Custom(2)"), "InvalidExecutor, got {:?}", err.err);

    // A spoofed board (not owned by ORE) with a fake low EMA must be rejected.
    let fake_board = Keypair::new().pubkey();
    let mut data = f.data(&BOARD);
    data[BOARD_EMA..BOARD_EMA + 8].copy_from_slice(&1u64.to_le_bytes());
    f.svm.set_account(fake_board, Account { lamports: 10_000_000, data, owner: SYSTEM, executable: false, rent_epoch: u64::MAX }).unwrap();
    let mut ix = f.dig_ix(TILE_AMOUNT, FIVE_TILES, 2, f.executor_bump, f.executor);
    ix.accounts[3] = AccountMeta::new(fake_board, false);
    let err = f.send(&[ix], &[]).unwrap_err();
    assert!(format!("{:?}", err.err).contains("Custom(3)"), "InvalidOreAccount, got {:?}", err.err);
}

#[test]
fn executor_pda_pays_checkpoint_fee_through_nested_cpi() {
    let mut f = Fork::new();
    f.send(&[f.automate_ix(LAMPORTS_PER_SOL / 10)], &[]).unwrap();
    f.dig(TILE_AMOUNT, FIVE_TILES, u64::MAX).unwrap();

    // Simulate a miner whose checkpoint fee was consumed (e.g. bot-checkpointed near expiry):
    // ORE then pulls CHECKPOINT_FEE from the *signer* via a System transfer inside its own CPI.
    let miner = f.miner();
    let mut acct = f.svm.get_account(&miner).unwrap();
    acct.data[MINER_CHECKPOINT_FEE..MINER_CHECKPOINT_FEE + 8].copy_from_slice(&0u64.to_le_bytes());
    f.svm.set_account(miner, acct).unwrap();

    let exec_before = f.balance(&f.executor);
    // Deploy to 5 new tiles in the same round: not a first deploy (no executor fee),
    // so the only executor balance change is the checkpoint fee it pays.
    f.dig(TILE_AMOUNT, FIVE_TILES << 5, u64::MAX).expect("dig with checkpoint top-up");
    assert_eq!(exec_before - f.balance(&f.executor), CHECKPOINT_FEE, "PDA signer paid via nested System CPI");
    let fee_now = read_u64(&f.data(&miner), MINER_CHECKPOINT_FEE);
    assert_eq!(fee_now, CHECKPOINT_FEE);
}
