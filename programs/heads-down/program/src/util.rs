//! Small shared helpers: an exact-length instruction-data reader, account
//! checks, the clock, and the shared P-256 signal verification.

use pinocchio::{
    error::ProgramError,
    sysvars::{clock::Clock, Sysvar},
    AccountView, ProgramResult,
};

use crate::{
    error::HdError,
    message,
    state::{Config, Rig},
};

/// Bounds-checked little-endian reader over instruction data. `finish`
/// rejects trailing bytes, so every instruction has one exact length.
pub struct Reader<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    /// Wrap `data`.
    pub fn new(data: &'a [u8]) -> Self {
        Self { data, pos: 0 }
    }

    /// Next `N` bytes.
    pub fn array<const N: usize>(&mut self) -> Result<[u8; N], HdError> {
        let end = self.pos.checked_add(N).ok_or(HdError::InvalidInstruction)?;
        let out: [u8; N] = self
            .data
            .get(self.pos..end)
            .and_then(|s| s.try_into().ok())
            .ok_or(HdError::InvalidInstruction)?;
        self.pos = end;
        Ok(out)
    }

    /// Next byte.
    pub fn u8(&mut self) -> Result<u8, HdError> {
        Ok(self.array::<1>()?[0])
    }

    /// Next u16 LE.
    pub fn u16(&mut self) -> Result<u16, HdError> {
        Ok(u16::from_le_bytes(self.array()?))
    }

    /// Next u32 LE.
    pub fn u32(&mut self) -> Result<u32, HdError> {
        Ok(u32::from_le_bytes(self.array()?))
    }

    /// Next u64 LE.
    pub fn u64(&mut self) -> Result<u64, HdError> {
        Ok(u64::from_le_bytes(self.array()?))
    }

    /// Next i64 LE.
    pub fn i64(&mut self) -> Result<i64, HdError> {
        Ok(i64::from_le_bytes(self.array()?))
    }

    /// Bytes not yet read.
    pub fn remaining(&self) -> usize {
        self.data.len().saturating_sub(self.pos)
    }

    /// Require that every byte was consumed.
    pub fn finish(self) -> Result<(), HdError> {
        if self.pos == self.data.len() {
            Ok(())
        } else {
            Err(HdError::InvalidInstruction)
        }
    }
}

/// `account` must have signed.
#[inline]
pub fn require_signer(account: &AccountView) -> ProgramResult {
    if account.is_signer() {
        Ok(())
    } else {
        Err(ProgramError::MissingRequiredSignature)
    }
}

/// `account` must be writable.
#[inline]
pub fn require_writable(account: &AccountView) -> ProgramResult {
    if account.is_writable() {
        Ok(())
    } else {
        Err(ProgramError::InvalidAccountData)
    }
}

/// The Clock sysvar, read by syscall (never from an account).
#[inline]
pub fn clock() -> Result<Clock, ProgramError> {
    Clock::get()
}

/// Load the Config and check its address is the canonical PDA.
pub fn load_config(
    account: &AccountView,
) -> Result<pinocchio::account::Ref<'_, Config>, ProgramError> {
    if account.address() != &crate::CONFIG_ID {
        return Err(HdError::InvalidAccountTag.into());
    }
    crate::state::load::<Config>(account)
}

/// `authority` must be the rig's authority and have signed.
pub fn require_rig_authority(rig: &Rig, authority: &AccountView) -> ProgramResult {
    if authority.address().as_array() != &rig.authority {
        return Err(HdError::Unauthorized.into());
    }
    require_signer(authority)
}

/// How a tighten-only action (arm / break / freeze) is authorized.
#[derive(Clone, Copy, Debug)]
pub enum SignalAuth {
    /// The rig's wallet signed the transaction.
    Wallet,
    /// A P-256 message from the rig's Keystore key, verified through the
    /// secp256r1 precompile at `(ix_index, sig_index)`.
    P256 {
        /// Strictly increasing counter bound into the message.
        counter: u64,
        /// Top-level index of the Secp256r1SigVerify instruction.
        ix_index: u8,
        /// Entry within it.
        sig_index: u8,
    },
}

impl SignalAuth {
    /// Parse `mode u8` (0 wallet, 1 P-256) and, for P-256, the trailing
    /// `counter u64 | ix u8 | sig u8`.
    pub fn parse_mode(mode: u8) -> Result<bool, HdError> {
        match mode {
            0 => Ok(false),
            1 => Ok(true),
            _ => Err(HdError::InvalidInstruction),
        }
    }

    /// Read the P-256 tail.
    pub fn read_p256(r: &mut Reader<'_>) -> Result<Self, HdError> {
        Ok(Self::P256 {
            counter: r.u64()?,
            ix_index: r.u8()?,
            sig_index: r.u8()?,
        })
    }
}

/// Authorize a tighten-only action on `rig`: either the wallet signed, or a
/// fresh P-256 message (counter > `rig.hb_counter`) whose preimage `build`
/// produces verified through the secp256r1 precompile. On success with
/// P-256 the counter is consumed.
pub fn authorize_signal<F>(
    rig: &mut Rig,
    authority: &AccountView,
    instructions_sysvar: Option<&AccountView>,
    auth: SignalAuth,
    build: F,
) -> ProgramResult
where
    F: FnOnce(u64) -> Preimage,
{
    if authority.address().as_array() != &rig.authority {
        return Err(HdError::Unauthorized.into());
    }
    match auth {
        SignalAuth::Wallet => require_signer(authority),
        SignalAuth::P256 {
            counter,
            ix_index,
            sig_index,
        } => {
            if counter <= rig.hb_counter.get() {
                return Err(HdError::StaleHeartbeat.into());
            }
            let sysvar = instructions_sysvar.ok_or(ProgramError::NotEnoughAccountKeys)?;
            let digest = build(counter).digest();
            p256_introspect::verify_secp256r1_signature(
                sysvar,
                u16::from(ix_index),
                sig_index,
                &rig.p256_pubkey,
                &digest,
            )?;
            rig.hb_counter.set(counter);
            Ok(())
        }
    }
}

/// A preimage of one of the fixed lengths.
pub enum Preimage {
    /// BREAK / FREEZE.
    Signal([u8; message::SIGNAL_LEN]),
    /// PLAN.
    Plan([u8; message::PLAN_LEN]),
}

impl Preimage {
    /// SHA-256 of the preimage (what the key signed).
    pub fn digest(&self) -> [u8; 32] {
        match self {
            Preimage::Signal(p) => message::digest(p),
            Preimage::Plan(p) => message::digest(p),
        }
    }
}
