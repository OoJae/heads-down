//! Spike 1(a): prove a program-derived address (PDA) can act as the executor of a
//! user's ORE Automation by signing ORE `deploy` through `invoke_signed`, and
//! that the program — not ORE — enforces the production-cost gate.
//!
//! Instruction `dig` (tag 1):
//!   data = [1, amount: u64 LE, mask: u32 LE, max_cost: u64 LE, executor_bump: u8]
//!   accounts = [executor PDA (w), authority (w), automation (w), board (w), config (w),
//!               miner (w), round (w), treasury (w), system program, ORE program,
//!               entropy var (w), entropy program]
#![no_std]

use pinocchio::{
    cpi::{invoke_signed, Signer},
    error::ProgramError,
    instruction::{InstructionAccount, InstructionView},
    no_allocator, nostd_panic_handler, program_entrypoint, seeds, AccountView, Address,
    ProgramResult,
};

program_entrypoint!(process_instruction);
no_allocator!();
nostd_panic_handler!();

pub const ORE_PROGRAM_ID: Address = Address::from_str_const("oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv");
pub const ORE_BOARD: Address = Address::from_str_const("BrcSxdp1nXFzou1YyDnQJcPNBNHgoypZmTsyKBSLLXzi");
pub const EXECUTOR_SEED: &[u8] = b"executor";

/// ORE accounts are steel accounts: 8-byte discriminator, then the struct.
/// Board = { round_id, start_slot, end_slot, production_cost_ema } (all u64).
const BOARD_PRODUCTION_COST_EMA_OFFSET: usize = 8 + 8 * 3;
const ORE_DEPLOY_TAG: u8 = 6;

#[repr(u32)]
pub enum SpikeError {
    InvalidInstruction = 0,
    CostGate = 1,
    InvalidExecutor = 2,
    InvalidOreAccount = 3,
}

impl From<SpikeError> for ProgramError {
    fn from(e: SpikeError) -> Self {
        ProgramError::Custom(e as u32)
    }
}

pub fn process_instruction(
    program_id: &Address,
    accounts: &mut [AccountView],
    data: &[u8],
) -> ProgramResult {
    match data.first() {
        Some(1) => dig(program_id, accounts, &data[1..]),
        _ => Err(SpikeError::InvalidInstruction.into()),
    }
}

#[inline(never)]
fn dig(program_id: &Address, accounts: &mut [AccountView], args: &[u8]) -> ProgramResult {
    if args.len() != 21 {
        return Err(SpikeError::InvalidInstruction.into());
    }
    let amount = u64::from_le_bytes(args[0..8].try_into().unwrap());
    let mask = u32::from_le_bytes(args[8..12].try_into().unwrap());
    let max_cost = u64::from_le_bytes(args[12..20].try_into().unwrap());
    let bump = args[20];

    let [executor, authority, automation, board, config, miner, round, treasury, system_program, ore_program, var, entropy_program, ..] =
        accounts
    else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };

    // The executor must be this program's canonical executor PDA.
    let bump_seed = [bump];
    let expected = Address::create_program_address(&[EXECUTOR_SEED, &bump_seed], program_id)
        .map_err(|_| ProgramError::from(SpikeError::InvalidExecutor))?;
    if executor.address() != &expected {
        return Err(SpikeError::InvalidExecutor.into());
    }

    // Only ever CPI into the real ORE program, and only read the real Board.
    if ore_program.address() != &ORE_PROGRAM_ID
        || board.address() != &ORE_BOARD
        || !board.owned_by(&ORE_PROGRAM_ID)
    {
        return Err(SpikeError::InvalidOreAccount.into());
    }

    // Program-level production-cost gate. ORE stores `max_production_cost` in the
    // Automation but does not enforce it in `deploy`, so Heads Down must.
    let ema = {
        let data = board.try_borrow()?;
        if data.len() < BOARD_PRODUCTION_COST_EMA_OFFSET + 8 {
            return Err(SpikeError::InvalidOreAccount.into());
        }
        u64::from_le_bytes(
            data[BOARD_PRODUCTION_COST_EMA_OFFSET..BOARD_PRODUCTION_COST_EMA_OFFSET + 8]
                .try_into()
                .unwrap(),
        )
    };
    if ema > max_cost {
        return Err(SpikeError::CostGate.into());
    }

    // ORE `deploy`: [tag, amount u64 LE, squares u32 LE].
    let mut ix_data = [0u8; 13];
    ix_data[0] = ORE_DEPLOY_TAG;
    ix_data[1..9].copy_from_slice(&amount.to_le_bytes());
    ix_data[9..13].copy_from_slice(&mask.to_le_bytes());

    let metas = [
        InstructionAccount::writable_signer(executor.address()),
        InstructionAccount::writable(authority.address()),
        InstructionAccount::writable(automation.address()),
        InstructionAccount::writable(board.address()),
        InstructionAccount::writable(config.address()),
        InstructionAccount::writable(miner.address()),
        InstructionAccount::writable(round.address()),
        InstructionAccount::writable(treasury.address()),
        InstructionAccount::readonly(system_program.address()),
        InstructionAccount::readonly(ore_program.address()),
        InstructionAccount::writable(var.address()),
        InstructionAccount::readonly(entropy_program.address()),
    ];
    let ix = InstructionView {
        program_id: &ORE_PROGRAM_ID,
        data: &ix_data,
        accounts: &metas,
    };
    let signer_seeds = seeds!(EXECUTOR_SEED, &bump_seed);
    let signer = Signer::from(&signer_seeds);
    invoke_signed(
        &ix,
        &[
            &*executor,
            &*authority,
            &*automation,
            &*board,
            &*config,
            &*miner,
            &*round,
            &*treasury,
            &*system_program,
            &*ore_program,
            &*var,
            &*entropy_program,
        ],
        &[signer],
    )
}
