//! v1.2 (SKR) reference client: pinned ids, SPL Token account surgery for
//! the fork, PDAs, and one builder per instruction (tags 15..=27), all
//! following `INTERFACE.md` §11.

use std::str::FromStr;

use heads_down::{instructions::HeartbeatEntry, state};

use crate::*;

// ---- ids ------------------------------------------------------------------------

/// SKR mint (classic SPL Token, 6 decimals).
pub const SKR_MINT: Address = hd::token::SKR_MINT;
/// ORE mint (classic SPL Token, 11 decimals).
pub const ORE_MINT: Address = hd::ore::MINT_ADDRESS;
/// SPL Token.
pub const SPL_TOKEN: Address = hd::token::SPL_TOKEN_PROGRAM_ID;
/// Associated Token Account program.
pub const ATA_PROGRAM: Address = hd::token::ATA_PROGRAM_ID;
/// BuryVault PDA `["bury"]`.
pub const BURY: Address = hd::BURY_ID;
/// One whole SKR.
pub const ONE_SKR: u64 = hd::token::ONE_SKR;
/// One whole ORE (atoms).
pub const ONE_ORE: u64 = hd::ore::ONE_ORE;

/// ORE stake program `stakecNP3FpiExZPCgZfqRgumVzi6dNqnfrjwXyTgeH`.
pub fn ore_stake_id() -> Address {
    Address::from_str("stakecNP3FpiExZPCgZfqRgumVzi6dNqnfrjwXyTgeH").unwrap()
}
/// ORE stake Treasury `["treasury"]`.
pub fn stake_treasury() -> Address {
    Address::find_program_address(&[b"treasury"], &ore_stake_id()).0
}
/// ORE stake Vesting `["vesting"]`.
pub fn stake_vesting() -> Address {
    Address::find_program_address(&[b"vesting"], &ore_stake_id()).0
}
/// `ATA(owner, mint)` under classic SPL Token.
pub fn ata(owner: &Address, mint: &Address) -> Address {
    Address::find_program_address(&[owner.as_ref(), SPL_TOKEN.as_ref(), mint.as_ref()], &ATA_PROGRAM)
        .0
}
/// ORE Treasury's ORE ATA.
pub fn treasury_ore() -> Address {
    ata(&TREASURY, &ORE_MINT)
}
/// ORE stake Treasury's ORE ATA.
pub fn stake_treasury_ore() -> Address {
    ata(&stake_treasury(), &ORE_MINT)
}

// ---- PDAs -------------------------------------------------------------------------

/// StackTable `["stack", host, table_id]`.
pub fn table_pda(host: &Address, table_id: u64) -> Address {
    Address::find_program_address(&[hd::STACK_SEED, host.as_ref(), &table_id.to_le_bytes()], &HD).0
}
/// StackSeat `["stackseat", table, key]` (key = SGT mint at remote tables,
/// else the rig).
pub fn stack_seat_pda(table: &Address, key: &Address) -> Address {
    Address::find_program_address(&[hd::STACK_SEAT_SEED, table.as_ref(), key.as_ref()], &HD).0
}
/// FocusBond `["bond", rig, shift_id]`.
pub fn bond_pda(rig: &Address, shift_id: u64) -> Address {
    Address::find_program_address(&[hd::BOND_SEED, rig.as_ref(), &shift_id.to_le_bytes()], &HD).0
}
/// GiftEscrow `["gift", sender, nonce]`.
pub fn gift_pda(sender: &Address, nonce: u64) -> Address {
    Address::find_program_address(&[hd::GIFT_SEED, sender.as_ref(), &nonce.to_le_bytes()], &HD).0
}

// ---- token surgery (the fork cannot mint SKR or ORE) ------------------------------------

/// A 165-byte initialized SPL Token account.
pub fn token_account_bytes(mint: &Address, owner: &Address, amount: u64) -> Vec<u8> {
    let mut d = vec![0u8; 165];
    d[..32].copy_from_slice(mint.as_ref());
    d[32..64].copy_from_slice(owner.as_ref());
    d[64..72].copy_from_slice(&amount.to_le_bytes());
    d[108] = 1; // Initialized
    d
}

/// Rent-exempt lamports of a 165-byte token account.
pub const TOKEN_ACCOUNT_RENT: u64 = 2_039_280;

impl Env {
    /// Load the v1.2 fixtures: the ORE stake program, the SKR and ORE mints,
    /// the ORE Treasury's ORE ATA and the stake Treasury / Vesting / ORE
    /// ATA. The stake Vesting is pinned fully vested and started 2 h before
    /// T0, so ORE `bury`'s `distribute` behaves the same whenever the
    /// fixtures were fetched (the suite's clock is 2026-09-29).
    pub fn load_skr_fixtures(&mut self) {
        let f = fixtures();
        self.svm
            .add_program(
                ore_stake_id(),
                &std::fs::read(f.join("ore_stake.so"))
                    .expect("tests/fixtures/ore_stake.so missing: run fetch-fixtures.sh (v1.2)"),
            )
            .unwrap();
        for a in [
            SKR_MINT,
            ORE_MINT,
            treasury_ore(),
            stake_treasury(),
            stake_treasury_ore(),
            stake_vesting(),
        ] {
            self.svm
                .set_account(a, load_fixture_account(&f.join(format!("{a}.json"))))
                .unwrap();
        }
        let v = stake_vesting();
        let mut vesting = self.account(&v);
        let initial = u64_at(&vesting.data, 8);
        vesting.data[16..24].copy_from_slice(&initial.to_le_bytes());
        vesting.data[24..32].copy_from_slice(&(T0 - 7_200).to_le_bytes());
        self.svm.set_account(v, vesting).unwrap();
    }

    /// Write an SPL Token account at `address`.
    pub fn set_token_account(&mut self, address: &Address, mint: &Address, owner: &Address, amount: u64) {
        self.svm
            .set_account(
                *address,
                Account {
                    lamports: TOKEN_ACCOUNT_RENT,
                    data: token_account_bytes(mint, owner, amount),
                    owner: SPL_TOKEN,
                    executable: false,
                    rent_epoch: 0,
                },
            )
            .unwrap();
    }

    /// Give `owner` an SKR ATA holding `amount` base units; returns it.
    pub fn fund_skr(&mut self, owner: &Address, amount: u64) -> Address {
        let a = ata(owner, &SKR_MINT);
        self.set_token_account(&a, &SKR_MINT, owner, amount);
        a
    }

    /// Give `owner` an ORE ATA holding `amount` atoms; returns it.
    pub fn fund_ore(&mut self, owner: &Address, amount: u64) -> Address {
        let a = ata(owner, &ORE_MINT);
        self.set_token_account(&a, &ORE_MINT, owner, amount);
        a
    }

    /// Token balance (0 if the account is missing).
    pub fn token_balance(&self, a: &Address) -> u64 {
        self.svm
            .get_account(a)
            .filter(|acc| acc.data.len() == 165)
            .map(|acc| u64_at(&acc.data, 64))
            .unwrap_or(0)
    }

    /// A mint's supply.
    pub fn mint_supply(&self, mint: &Address) -> u64 {
        u64_at(&self.account(mint).data, 36)
    }

    /// Decode a StackTable.
    pub fn stack_table(&self, a: &Address) -> state::StackTable {
        *bytemuck_view::<state::StackTable>(&self.account(a).data)
    }
    /// Decode a StackSeat.
    pub fn stack_seat(&self, a: &Address) -> state::StackSeat {
        *bytemuck_view::<state::StackSeat>(&self.account(a).data)
    }
    /// Decode a FocusBond.
    pub fn focus_bond(&self, a: &Address) -> state::FocusBond {
        *bytemuck_view::<state::FocusBond>(&self.account(a).data)
    }
    /// Decode a GiftEscrow.
    pub fn gift(&self, a: &Address) -> state::GiftEscrow {
        *bytemuck_view::<state::GiftEscrow>(&self.account(a).data)
    }
    /// Decode the BuryVault.
    pub fn bury_vault(&self) -> state::BuryVault {
        *bytemuck_view::<state::BuryVault>(&self.account(&BURY).data)
    }
    /// `true` if `a` holds no account (closed).
    pub fn is_closed(&self, a: &Address) -> bool {
        self.svm
            .get_account(a)
            .map(|x| x.lamports == 0 && x.data.is_empty())
            .unwrap_or(true)
    }

    /// `init_bury_vault` paid by the cranker, plus both vault ATAs.
    pub fn init_bury_vault(&mut self) {
        let c = self.cranker.pubkey();
        ok(self.send(
            &[
                ix_init_bury_vault(&c),
                ix_create_ata(&c, &BURY, &SKR_MINT),
                ix_create_ata(&c, &BURY, &ORE_MINT),
            ],
            &[],
        ));
    }

    /// Move the ORE Board to `round` (fixture surgery).
    pub fn set_board_round(&mut self, round: u64) {
        self.poke_u64(&BOARD, 8, round);
        self.board_round = round;
    }

    /// Load the real mainnet SGT `label` (`"member-20"` or
    /// `"member-121035"`, from `crates/sgt-verify/fixtures`) and give it to
    /// `holder`: a frozen Token-2022 ATA holding it, bytes as Solana Mobile
    /// leaves them after a move. Needs the mainnet build. Returns `(mint,
    /// token account)`.
    pub fn give_real_sgt(&mut self, label: &str, holder: &Address) -> (Address, Address) {
        let manifest: serde_json::Value = serde_json::from_str(
            &std::fs::read_to_string(sgt_fixtures().join("manifest.json")).unwrap(),
        )
        .unwrap();
        let entry = manifest["sgts"]
            .as_array()
            .unwrap()
            .iter()
            .find(|s| s["label"] == label)
            .unwrap_or_else(|| panic!("no SGT fixture {label}"));
        let mint = Address::from_str(entry["mint"].as_str().unwrap()).unwrap();
        let mut mint_acc = load_fixture_account(&sgt_fixtures().join(format!("{label}/mint.json")));
        mint_acc.rent_epoch = 0;
        self.svm.set_account(mint, mint_acc).unwrap();
        let token_account = sgt_verify::testkit::associated_token_address(holder, &mint);
        self.svm
            .set_account(
                token_account,
                Account {
                    lamports: SOL / 100,
                    data: sgt_verify::testkit::sgt_token_account_bytes(&mint, holder, true),
                    owner: token_2022_id(),
                    executable: false,
                    rent_epoch: 0,
                },
            )
            .unwrap();
        (mint, token_account)
    }

    /// Registrar attestation by account surgery (level, expiry slot): for
    /// tests of the attested-only rules, which are not about the voucher.
    pub fn set_attestation(&mut self, rig: &Address, level: u8, expiry_slot: u64) {
        let mut acc = self.account(rig);
        acc.data[73] = level;
        acc.data[112..120].copy_from_slice(&expiry_slot.to_le_bytes());
        self.svm.set_account(*rig, acc).unwrap();
    }
}

// ---- generic builders --------------------------------------------------------------------

/// ATA program `CreateIdempotent` (tag 1).
pub fn ix_create_ata(payer: &Address, owner: &Address, mint: &Address) -> Instruction {
    Instruction {
        program_id: ATA_PROGRAM,
        accounts: vec![
            AccountMeta::new(*payer, true),
            AccountMeta::new(ata(owner, mint), false),
            AccountMeta::new_readonly(*owner, false),
            AccountMeta::new_readonly(*mint, false),
            AccountMeta::new_readonly(SYSTEM, false),
            AccountMeta::new_readonly(SPL_TOKEN, false),
        ],
        data: vec![1],
    }
}

// ---- Stack -----------------------------------------------------------------------------------

/// `open_stack` parameters.
#[derive(Clone, Copy, Debug)]
pub struct StackParams {
    /// Host-chosen id.
    pub table_id: u64,
    /// SKR base units per seat.
    pub bond: u64,
    /// Window start round.
    pub start_round: u64,
    /// Window end round.
    pub end_round: u64,
    /// Grace gaps.
    pub grace_gaps: u32,
    /// `stack_flags`.
    pub flags: u8,
    /// Seat limit.
    pub max_seats: u8,
}

/// `open_stack` data (after the tag).
pub fn open_stack_data(p: &StackParams) -> Vec<u8> {
    let mut data = vec![hd::tag::OPEN_STACK];
    data.extend_from_slice(&p.table_id.to_le_bytes());
    data.extend_from_slice(&p.bond.to_le_bytes());
    data.extend_from_slice(&p.start_round.to_le_bytes());
    data.extend_from_slice(&p.end_round.to_le_bytes());
    data.extend_from_slice(&p.grace_gaps.to_le_bytes());
    data.push(p.flags);
    data.push(p.max_seats);
    data
}

/// `open_stack` (the table's SKR vault ATA must exist: see [`ix_create_ata`]).
pub fn ix_open_stack(host: &Address, p: &StackParams) -> Instruction {
    let table = table_pda(host, p.table_id);
    Instruction {
        program_id: HD,
        accounts: vec![
            AccountMeta::new(*host, true),
            AccountMeta::new(table, false),
            AccountMeta::new_readonly(ata(&table, &SKR_MINT), false),
            AccountMeta::new_readonly(BOARD, false),
            AccountMeta::new_readonly(SYSTEM, false),
        ],
        data: open_stack_data(p),
    }
}

/// `join_stack`. `sgt` = `(sgt token account, sgt mint)` when the table needs
/// a verified Seeker; `seat_key` is the SGT mint at remote tables, else the rig.
pub fn ix_join_stack(
    authority: &Address,
    table: &Address,
    seat_key: &Address,
    sgt: Option<(Address, Address)>,
) -> Instruction {
    let mut accounts = vec![
        AccountMeta::new(*authority, true),
        AccountMeta::new_readonly(rig_pda(authority), false),
        AccountMeta::new(*table, false),
        AccountMeta::new(stack_seat_pda(table, seat_key), false),
        AccountMeta::new(ata(authority, &SKR_MINT), false),
        AccountMeta::new(ata(table, &SKR_MINT), false),
        AccountMeta::new_readonly(BOARD, false),
        AccountMeta::new_readonly(SPL_TOKEN, false),
        AccountMeta::new_readonly(SYSTEM, false),
    ];
    if let Some((token_account, mint)) = sgt {
        accounts.push(AccountMeta::new_readonly(token_account, false));
        accounts.push(AccountMeta::new_readonly(mint, false));
    }
    Instruction {
        program_id: HD,
        accounts,
        data: vec![hd::tag::JOIN_STACK],
    }
}

/// `stack_checkin` for `(seat, rig, entry)` triples of one table.
pub fn ix_stack_checkin(table: &Address, seats: &[(Address, Address, HeartbeatEntry)]) -> Instruction {
    let mut data = vec![hd::tag::STACK_CHECKIN, seats.len() as u8];
    let mut accounts = vec![
        AccountMeta::new_readonly(BOARD, false),
        AccountMeta::new_readonly(ix_sysvar_id(), false),
        AccountMeta::new_readonly(*table, false),
    ];
    for (seat, rig, e) in seats {
        data.push(e.hb_ix);
        data.push(e.hb_sig_index);
        data.extend_from_slice(&e.counter.to_le_bytes());
        data.extend_from_slice(&e.round_id.to_le_bytes());
        data.push(e.lease_rounds);
        data.push(0);
        accounts.push(AccountMeta::new(*seat, false));
        accounts.push(AccountMeta::new(*rig, false));
    }
    Instruction {
        program_id: HD,
        accounts,
        data,
    }
}

/// `settle_stack` with every seat of the table.
pub fn ix_settle_stack(table: &Address, seats: &[Address]) -> Instruction {
    let mut accounts = vec![
        AccountMeta::new(*table, false),
        AccountMeta::new_readonly(BOARD, false),
        AccountMeta::new(ata(table, &SKR_MINT), false),
        AccountMeta::new(BURY, false),
        AccountMeta::new(ata(&BURY, &SKR_MINT), false),
        AccountMeta::new_readonly(SPL_TOKEN, false),
    ];
    for s in seats {
        accounts.push(AccountMeta::new(*s, false));
    }
    Instruction {
        program_id: HD,
        accounts,
        data: vec![hd::tag::SETTLE_STACK],
    }
}

/// `claim_stack` for `seat` (owned by `authority`).
pub fn ix_claim_stack(table: &Address, seat: &Address, authority: &Address) -> Instruction {
    Instruction {
        program_id: HD,
        accounts: vec![
            AccountMeta::new(*table, false),
            AccountMeta::new(*seat, false),
            AccountMeta::new(*authority, false),
            AccountMeta::new(ata(authority, &SKR_MINT), false),
            AccountMeta::new(ata(table, &SKR_MINT), false),
            AccountMeta::new_readonly(SPL_TOKEN, false),
        ],
        data: vec![hd::tag::CLAIM_STACK],
    }
}

// ---- Focus Bond ------------------------------------------------------------------------------

/// `lock_focus_bond` (the bond's SKR vault ATA must exist).
pub fn ix_lock_focus_bond(authority: &Address, shift_id: u64, amount: u64) -> Instruction {
    let rig = rig_pda(authority);
    let bond = bond_pda(&rig, shift_id);
    let mut data = vec![hd::tag::LOCK_FOCUS_BOND];
    data.extend_from_slice(&shift_id.to_le_bytes());
    data.extend_from_slice(&amount.to_le_bytes());
    Instruction {
        program_id: HD,
        accounts: vec![
            AccountMeta::new(*authority, true),
            AccountMeta::new_readonly(rig, false),
            AccountMeta::new(bond, false),
            AccountMeta::new(ata(authority, &SKR_MINT), false),
            AccountMeta::new(ata(&bond, &SKR_MINT), false),
            AccountMeta::new_readonly(shift_log_pda(&rig, shift_id), false),
            AccountMeta::new_readonly(SPL_TOKEN, false),
            AccountMeta::new_readonly(SYSTEM, false),
        ],
        data,
    }
}

/// `release_focus_bond`.
pub fn ix_release_focus_bond(authority: &Address, shift_id: u64) -> Instruction {
    let rig = rig_pda(authority);
    let bond = bond_pda(&rig, shift_id);
    Instruction {
        program_id: HD,
        accounts: vec![
            AccountMeta::new(bond, false),
            AccountMeta::new_readonly(shift_log_pda(&rig, shift_id), false),
            AccountMeta::new(ata(&bond, &SKR_MINT), false),
            AccountMeta::new(ata(authority, &SKR_MINT), false),
            AccountMeta::new(*authority, false),
            AccountMeta::new_readonly(SPL_TOKEN, false),
        ],
        data: vec![hd::tag::RELEASE_FOCUS_BOND],
    }
}

/// `forfeit_focus_bond`.
pub fn ix_forfeit_focus_bond(authority: &Address, shift_id: u64) -> Instruction {
    let rig = rig_pda(authority);
    let bond = bond_pda(&rig, shift_id);
    Instruction {
        program_id: HD,
        accounts: vec![
            AccountMeta::new(bond, false),
            AccountMeta::new_readonly(shift_log_pda(&rig, shift_id), false),
            AccountMeta::new_readonly(rig, false),
            AccountMeta::new(ata(&bond, &SKR_MINT), false),
            AccountMeta::new(BURY, false),
            AccountMeta::new(ata(&BURY, &SKR_MINT), false),
            AccountMeta::new(*authority, false),
            AccountMeta::new_readonly(SPL_TOKEN, false),
        ],
        data: vec![hd::tag::FORFEIT_FOCUS_BOND],
    }
}

// ---- Gift a Rig -------------------------------------------------------------------------------

/// `create_gift`.
pub fn ix_create_gift(
    sender: &Address,
    nonce: u64,
    recipient_kind: u8,
    recipient: &Address,
    lamports: u64,
) -> Instruction {
    let mut data = vec![hd::tag::CREATE_GIFT];
    data.extend_from_slice(&nonce.to_le_bytes());
    data.push(recipient_kind);
    data.extend_from_slice(recipient.as_ref());
    data.extend_from_slice(&lamports.to_le_bytes());
    Instruction {
        program_id: HD,
        accounts: vec![
            AccountMeta::new(*sender, true),
            AccountMeta::new(gift_pda(sender, nonce), false),
            AccountMeta::new_readonly(SYSTEM, false),
        ],
        data,
    }
}

/// `claim_gift` (with the SGT accounts for an SGT-mint gift).
pub fn ix_claim_gift(
    claimer: &Address,
    gift: &Address,
    sender: &Address,
    sgt: Option<(Address, Address)>,
) -> Instruction {
    let mut accounts = vec![
        AccountMeta::new(*claimer, true),
        AccountMeta::new(*gift, false),
        AccountMeta::new(*sender, false),
    ];
    if let Some((token_account, mint)) = sgt {
        accounts.push(AccountMeta::new_readonly(token_account, false));
        accounts.push(AccountMeta::new_readonly(mint, false));
    }
    Instruction {
        program_id: HD,
        accounts,
        data: vec![hd::tag::CLAIM_GIFT],
    }
}

/// `refund_gift`.
pub fn ix_refund_gift(gift: &Address, sender: &Address) -> Instruction {
    Instruction {
        program_id: HD,
        accounts: vec![AccountMeta::new(*gift, false), AccountMeta::new(*sender, false)],
        data: vec![hd::tag::REFUND_GIFT],
    }
}

// ---- Bury auction ------------------------------------------------------------------------------

/// `init_bury_vault`.
pub fn ix_init_bury_vault(payer: &Address) -> Instruction {
    Instruction {
        program_id: HD,
        accounts: vec![
            AccountMeta::new(*payer, true),
            AccountMeta::new(BURY, false),
            AccountMeta::new_readonly(SYSTEM, false),
        ],
        data: vec![hd::tag::INIT_BURY_VAULT],
    }
}

/// `bury_auction_buy` (the buyer's ORE and SKR ATAs).
pub fn ix_bury_auction_buy(buyer: &Address, skr_amount: u64, max_ore: u64) -> Instruction {
    let mut data = vec![hd::tag::BURY_AUCTION_BUY];
    data.extend_from_slice(&skr_amount.to_le_bytes());
    data.extend_from_slice(&max_ore.to_le_bytes());
    Instruction {
        program_id: HD,
        accounts: vec![
            AccountMeta::new_readonly(*buyer, true),
            AccountMeta::new(ata(buyer, &ORE_MINT), false),
            AccountMeta::new(ata(buyer, &SKR_MINT), false),
            AccountMeta::new(BURY, false),
            AccountMeta::new(ata(&BURY, &ORE_MINT), false),
            AccountMeta::new(ata(&BURY, &SKR_MINT), false),
            AccountMeta::new(BOARD, false),
            AccountMeta::new(ORE_MINT, false),
            AccountMeta::new(TREASURY, false),
            AccountMeta::new(treasury_ore(), false),
            AccountMeta::new(stake_treasury(), false),
            AccountMeta::new(stake_treasury_ore(), false),
            AccountMeta::new(stake_vesting(), false),
            AccountMeta::new_readonly(SPL_TOKEN, false),
            AccountMeta::new_readonly(ORE, false),
            AccountMeta::new_readonly(ore_stake_id(), false),
        ],
        data,
    }
}
