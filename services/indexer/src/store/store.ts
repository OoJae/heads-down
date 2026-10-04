/**
 * The indexer's only data access layer. A Store is bound to ONE dataset at construction and
 * every statement filters on it; there is no API that reads across datasets. That is the
 * mechanism behind "simulated data is never mixed with real data".
 */
import type { Db, Param } from "./db.ts";
import type { ExtractedTx } from "../codec/tx.ts";
import type { OreResetEvent } from "../codec/ore.ts";
import type { OreRoundAccount } from "../codec/round.ts";
import type { ConfigAccount, RigAccount, SeekerSeatAccount, ShiftLogAccount } from "../codec/accounts.ts";
import { SKR_SUM_FIELDS, type BuryState, type SkrEventGroup, type SkrSumField } from "../metrics/skr.ts";
import type {
  ArmRow,
  ClosedRow,
  ConfigRow,
  Dataset,
  DatasetInfo,
  DeployRow,
  DigRow,
  EndRow,
  MetricsInput,
  RegisteredRow,
  RigRow,
  RoundRow,
  RoundStateRow,
  SeatRow,
  SeekerRow,
  SkipRow,
} from "../model.ts";

const MAX_PARAMS = 30_000;

const s = (v: bigint | number) => v.toString();
const big = (v: unknown): bigint => BigInt(v as string);
const num = (v: unknown): number => Number(v);
const optNum = (v: unknown): number | null => (v === null || v === undefined ? null : Number(v));
const optBig = (v: unknown): bigint | null => (v === null || v === undefined ? null : BigInt(v as string));

/** Multi-row INSERT ... ON CONFLICT DO NOTHING, chunked under the parameter limit. */
async function insertMany(db: Db, table: string, cols: string[], rows: Param[][], conflict = "ON CONFLICT DO NOTHING"): Promise<void> {
  if (rows.length === 0) return;
  const per = Math.max(1, Math.floor(MAX_PARAMS / cols.length));
  for (let i = 0; i < rows.length; i += per) {
    const chunk = rows.slice(i, i + per);
    const params: Param[] = [];
    const values = chunk.map((r) => {
      if (r.length !== cols.length) throw new Error(`row width ${r.length} != ${cols.length} for ${table}`);
      const ph = r.map((v) => {
        params.push(v);
        return `$${params.length}`;
      });
      return `(${ph.join(",")})`;
    });
    await db.query(`INSERT INTO ${table} (${cols.join(",")}) VALUES ${values.join(",")} ${conflict}`, params);
  }
}

export class DatasetMismatchError extends Error {}

export class Store {
  readonly db: Db;
  readonly dataset: Dataset;

  private constructor(db: Db, dataset: Dataset) {
    this.db = db;
    this.dataset = dataset;
  }

  /**
   * Binds to `dataset`, creating its row on first use. Refuses to bind if the dataset was
   * created for a different program/executor (e.g. a devnet id pointed at mainnet data).
   */
  static async bind(
    db: Db,
    info: { name: Dataset; programId: string; executorPda: string; simSeed?: string | null; simAsOf?: number | null },
  ): Promise<Store> {
    if ((info.name === "simulated") !== (info.simSeed !== undefined && info.simSeed !== null)) {
      throw new DatasetMismatchError("the simulated dataset (and only it) must carry a simulation seed");
    }
    await db.query(
      `INSERT INTO datasets (name, program_id, executor_pda, sim_seed, sim_as_of) VALUES ($1,$2,$3,$4,$5)
       ON CONFLICT (name) DO NOTHING`,
      [info.name, info.programId, info.executorPda, info.simSeed ?? null, info.simAsOf ?? null],
    );
    const [row] = await db.query<{ program_id: string; executor_pda: string }>(
      "SELECT program_id, executor_pda FROM datasets WHERE name = $1",
      [info.name],
    );
    if (!row || row.program_id !== info.programId || row.executor_pda !== info.executorPda) {
      throw new DatasetMismatchError(
        `dataset ${info.name} was created for program ${row?.program_id} / executor ${row?.executor_pda}`,
      );
    }
    return new Store(db, info.name);
  }

  async info(): Promise<DatasetInfo> {
    const [r] = await this.db.query<Record<string, unknown>>(
      "SELECT name, simulated, program_id, executor_pda, sim_seed, sim_as_of::text AS sim_as_of FROM datasets WHERE name = $1",
      [this.dataset],
    );
    if (!r) throw new Error("dataset row missing");
    return {
      name: r.name as Dataset,
      simulated: r.simulated === true,
      programId: r.program_id as string,
      executorPda: r.executor_pda as string,
      simSeed: (r.sim_seed as string | null) ?? null,
      simAsOf: optNum(r.sim_as_of),
    };
  }

  async setSimAsOf(ts: number): Promise<void> {
    await this.db.query("UPDATE datasets SET sim_as_of = $2 WHERE name = $1 AND name = 'simulated'", [this.dataset, ts]);
  }

  // ------------------------------------------------------------------ writes

  /** Idempotent: transactions already stored are skipped (with all their events). Returns # new. */
  async ingestTxs(xs: ExtractedTx[], source: string): Promise<number> {
    if (xs.length === 0) return 0;
    const d = this.dataset;
    return this.db.transaction(async (db) => {
      const unique = [...new Map(xs.map((x) => [x.signature, x])).values()];
      const existing = new Set<string>();
      for (let i = 0; i < unique.length; i += 5000) {
        const sigs = unique.slice(i, i + 5000).map((x) => x.signature);
        const rows = await db.query<{ signature: string }>(
          "SELECT signature FROM txs WHERE dataset = $1 AND signature = ANY($2::text[])",
          [d, `{${sigs.join(",")}}`],
        );
        for (const r of rows) existing.add(r.signature);
      }
      const fresh = unique.filter((x) => !existing.has(x.signature));
      if (fresh.length === 0) return 0;
      await insertMany(
        db,
        "txs",
        ["dataset", "signature", "slot", "block_time", "fee_payer", "failed", "logs_truncated", "source"],
        fresh.map((x) => [d, x.signature, x.slot, x.blockTime, x.feePayer, x.failed, x.logsTruncated, source]),
      );
      const dug: Param[][] = [];
      const skipped: Param[][] = [];
      const armed: Param[][] = [];
      const ended: Param[][] = [];
      const seeker: Param[][] = [];
      const registered: Param[][] = [];
      const closed: Param[][] = [];
      const recorded: Param[][] = [];
      const broken: Param[][] = [];
      const ext: Param[][] = [];
      const heartbeats: Param[][] = [];
      const plans: Param[][] = [];
      const deploys: Param[][] = [];
      const rounds: { event: OreResetEvent; signature: string }[] = [];
      const problems: Param[][] = [];
      for (const x of fresh) {
        const base = [d, x.signature] as Param[];
        for (const { index, event: e, raw } of x.hdEvents) {
          const head = [...base, index, x.slot, x.blockTime, e.rig];
          switch (e.kind) {
            case "RigDug":
              dug.push([...head, s(e.roundId), s(e.lamports), e.mask, s(e.emaEv), raw]);
              break;
            case "RigSkipped":
              skipped.push([...head, s(e.roundId), e.error, raw]);
              break;
            case "ShiftArmed":
              armed.push([...head, s(e.shiftId), raw]);
              break;
            case "ShiftEnded":
              ended.push([...head, s(e.shiftId), s(e.darkRounds), s(e.roundsDug), s(e.lamports), e.reason, raw, 4, null, null, null]);
              break;
            case "ShiftEndedV2":
              ended.push([...head, s(e.shiftId), s(e.darkRounds), s(e.roundsDug), s(e.lamports), e.reason, raw, 10, s(e.startRound), s(e.endRound), e.mode]);
              break;
            case "SeekerVerified":
              seeker.push([...head, e.sgtMint, s(e.memberNumber), raw]);
              break;
            case "RigRegistered":
              registered.push([...head, e.authority, e.tier, e.attestationLevel, raw]);
              break;
            case "RigClosed":
              closed.push([...head, raw]);
              break;
            case "HeartbeatsRecorded":
              recorded.push([...head, s(e.roundId), s(e.darkRoundsAdded), raw]);
              break;
            case "ShiftBroken":
              broken.push([...head, s(e.shiftId), e.reason, raw]);
              break;
          }
        }
        for (const { index, event: e, raw } of x.hdExtEvents) {
          const rig = typeof e.fields.rig === "string" ? e.fields.rig : null;
          // u64 / i64 as strings: a JSON number cannot hold them.
          const fields = JSON.stringify(e.fields, (_k, v: unknown) => (typeof v === "bigint" ? v.toString() : v));
          ext.push([...base, index, x.slot, x.blockTime, e.tag, e.name, rig, fields, raw]);
        }
        for (const h of x.heartbeats) {
          heartbeats.push([
            d, x.signature, h.ixIndex, h.entryIndex, x.slot, x.blockTime, h.rig, h.authority, h.kind, h.fresh, s(h.counter),
            s(h.hbRound), h.leaseRounds, h.boardRound === null ? null : s(h.boardRound), h.applied,
          ]);
        }
        for (const a of x.armPlans) {
          const p = a.plan;
          plans.push([
            d, x.signature, a.ixIndex, x.slot, x.blockTime, a.rig, p.mode, s(p.maxEvCost), s(p.digLamports), p.split, p.solo, p.lease,
            p.flags, s(p.windowStart), s(p.windowEnd), p.counter === null ? null : s(p.counter),
          ]);
        }
        for (const { index, event: e, raw } of x.oreDeploys) {
          deploys.push([...base, index, x.slot, x.blockTime, e.authority, e.signer, s(e.amount), e.mask, s(e.roundId), s(e.strategy), e.totalSquares, s(e.ts), raw]);
        }
        for (const r of x.oreResets) rounds.push({ event: r.event, signature: x.signature });
        for (const p of x.problems) problems.push([d, x.signature, p.location, p.code, p.message.slice(0, 500)]);
        for (const t of x.unknownHdEventTags) problems.push([d, x.signature, `tag ${t}`, "UNKNOWN_EVENT_TAG", `heads_down event tag ${t}`]);
        if (x.logsTruncated) problems.push([d, x.signature, "logs", "LOGS_TRUNCATED", "runtime truncated the logs; heads_down events after the cut are missing"]);
      }
      const ev = ["dataset", "signature", "idx", "slot", "block_time", "rig"];
      await insertMany(db, "ev_rig_dug", [...ev, "round_id", "lamports", "mask", "ema_ev", "raw"], dug);
      await insertMany(db, "ev_rig_skipped", [...ev, "round_id", "error_code", "raw"], skipped);
      await insertMany(db, "ev_shift_armed", [...ev, "shift_id", "raw"], armed);
      await insertMany(db, "ev_shift_ended", [...ev, "shift_id", "dark_rounds", "rounds_dug", "lamports", "reason", "raw", "tag", "start_round", "end_round", "mode"], ended);
      await insertMany(db, "ev_seeker_verified", [...ev, "sgt_mint", "member_number", "raw"], seeker);
      await insertMany(db, "ev_rig_registered", [...ev, "authority", "tier", "attestation_level", "raw"], registered);
      await insertMany(db, "ev_rig_closed", [...ev, "raw"], closed);
      await insertMany(db, "ev_heartbeats_recorded", [...ev, "round_id", "dark_rounds_added", "raw"], recorded);
      await insertMany(db, "ev_shift_broken", [...ev, "shift_id", "reason", "raw"], broken);
      await insertMany(db, "ev_ext", ["dataset", "signature", "idx", "slot", "block_time", "tag", "name", "rig", "fields", "raw"], ext);
      await insertMany(
        db,
        "hd_heartbeats",
        ["dataset", "signature", "ix_idx", "entry_idx", "slot", "block_time", "rig", "authority", "kind", "fresh", "counter", "hb_round", "lease_rounds", "board_round", "applied"],
        heartbeats,
      );
      await insertMany(
        db,
        "hd_arm_plans",
        ["dataset", "signature", "ix_idx", "slot", "block_time", "rig", "mode", "max_ev_cost", "dig_lamports", "split_tiles", "solo_tiles", "lease_rounds", "flags", "window_start", "window_end", "counter"],
        plans,
      );
      await insertMany(
        db,
        "ore_deploys",
        ["dataset", "signature", "idx", "slot", "block_time", "authority", "signer", "amount", "mask", "round_id", "strategy", "total_squares", "ts", "raw"],
        deploys,
      );
      await this.insertRounds(db, rounds.map((r) => ({ event: r.event, resetSignature: r.signature })), "chain");
      await insertMany(db, "ingest_problems", ["dataset", "subject", "location", "code", "message"], problems);
      return fresh.length;
    });
  }

  async upsertRounds(rows: { event: OreResetEvent; resetSignature: string | null }[], source: string): Promise<void> {
    await this.db.transaction((db) => this.insertRounds(db, rows, source));
  }

  /**
   * One page from api.ore.com: its rounds and the note of which round ids have been read (cursor
   * `ore-api`/`covered`), in one transaction, so the note never says more than is stored. One
   * process per dataset is assumed to read api.ore.com: a second one would replace the note with
   * its own, and the rounds its note leaves out are asked for again.
   */
  async storeOreApiPage(rows: { event: OreResetEvent; resetSignature: string | null }[], covered: string): Promise<void> {
    await this.db.transaction(async (db) => {
      await this.insertRounds(db, rows, "ore-api");
      await db.query(
        `INSERT INTO ingest_cursors (dataset, source, key, value) VALUES ($1,'ore-api','covered',$2)
         ON CONFLICT (dataset, source, key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()`,
        [this.dataset, covered],
      );
    });
  }

  /** The oldest round id of the unbroken run of stored rounds that ends at `hi`; null when `hi` itself is not stored. */
  async oreRunBottom(hi: bigint): Promise<bigint | null> {
    const [r] = await this.db.query<{ lo: string }>(
      `SELECT r.round_id::text AS lo FROM ore_rounds r
       WHERE r.dataset = $1 AND r.round_id <= $2
         AND EXISTS (SELECT 1 FROM ore_rounds h WHERE h.dataset = r.dataset AND h.round_id = $2)
         AND NOT EXISTS (SELECT 1 FROM ore_rounds p WHERE p.dataset = r.dataset AND p.round_id = r.round_id - 1)
       ORDER BY r.round_id DESC LIMIT 1`,
      [this.dataset, s(hi)],
    );
    return r ? BigInt(r.lo) : null;
  }

  /** Reset time (unix seconds) of a stored round; null when it is not stored. */
  async oreRoundTime(id: bigint): Promise<number | null> {
    const [r] = await this.db.query<{ ts: unknown }>("SELECT ts FROM ore_rounds WHERE dataset = $1 AND round_id = $2", [this.dataset, s(id)]);
    return optNum(r?.ts);
  }

  private async insertRounds(db: Db, rows: { event: OreResetEvent; resetSignature: string | null }[], source: string) {
    await insertMany(
      db,
      "ore_rounds",
      [
        "dataset", "round_id", "ts", "start_slot", "end_slot", "winning_square", "top_miner", "total_miners",
        "motherlode", "total_deployed", "total_vaulted", "total_winnings", "total_minted", "rng",
        "deployed_winning_square", "reset_signature", "source",
      ],
      rows.map(({ event: e, resetSignature }) => [
        this.dataset, s(e.roundId), s(e.ts), s(e.startSlot), s(e.endSlot), e.winningSquare, e.topMiner,
        s(e.totalMiners), s(e.motherlode), s(e.totalDeployed), s(e.totalVaulted), s(e.totalWinnings),
        s(e.totalMinted), s(e.rng), s(e.deployedWinningSquare), resetSignature, source,
      ]),
    );
  }

  async recordProblem(subject: string, location: string, code: string, message: string): Promise<void> {
    await insertMany(this.db, "ingest_problems", ["dataset", "subject", "location", "code", "message"], [
      [this.dataset, subject.slice(0, 120), location.slice(0, 200), code.slice(0, 60), message.slice(0, 500)],
    ]);
  }

  /**
   * Replaces the account snapshot with a COMPLETE getProgramAccounts scan taken at
   * `contextSlot`: upserts every account and marks the ones no longer returned as closed.
   */
  async replaceAccounts(
    snap: {
      rigs: { address: string; account: RigAccount; data: Uint8Array }[];
      shiftLogs: { address: string; account: ShiftLogAccount; data: Uint8Array }[];
      seats: { address: string; account: SeekerSeatAccount; data: Uint8Array }[];
      config: { address: string; account: ConfigAccount; data: Uint8Array } | null;
    },
    contextSlot: number,
  ): Promise<void> {
    const d = this.dataset;
    const newer = (t: string) => `WHERE ${t}.context_slot <= EXCLUDED.context_slot`;
    await this.db.transaction(async (db) => {
      await insertMany(
        db,
        "acc_rigs",
        ["dataset", "address", "authority", "tier", "state", "attestation_level", "sgt_mint", "shift_id", "lifetime_dark_rounds", "lifetime_rounds_dug", "lifetime_lamports_deployed", "streak", "closed", "context_slot", "data"],
        snap.rigs.map(({ address, account: a, data }) => [
          d, address, a.authority, a.tier, a.state, a.attestationLevel, a.sgtMint, s(a.shiftId), s(a.lifetimeDarkRounds),
          s(a.lifetimeRoundsDug), s(a.lifetimeLamportsDeployed), a.streak, false, contextSlot, data,
        ]),
        `ON CONFLICT (dataset, address) DO UPDATE SET authority=EXCLUDED.authority, tier=EXCLUDED.tier, state=EXCLUDED.state,
         attestation_level=EXCLUDED.attestation_level, sgt_mint=EXCLUDED.sgt_mint, shift_id=EXCLUDED.shift_id,
         lifetime_dark_rounds=EXCLUDED.lifetime_dark_rounds, lifetime_rounds_dug=EXCLUDED.lifetime_rounds_dug,
         lifetime_lamports_deployed=EXCLUDED.lifetime_lamports_deployed, streak=EXCLUDED.streak, closed=FALSE,
         context_slot=EXCLUDED.context_slot, data=EXCLUDED.data, updated_at=now() ${newer("acc_rigs")}`,
      );
      await insertMany(
        db,
        "acc_shift_logs",
        ["dataset", "address", "rig", "shift_id", "start_round", "end_round", "dark_rounds", "rounds_dug", "lamports_deployed", "break_reason", "mode", "start_ts", "end_ts", "closed", "context_slot", "data"],
        snap.shiftLogs.map(({ address, account: a, data }) => [
          d, address, a.rig, s(a.shiftId), s(a.startRound), s(a.endRound), s(a.darkRounds), s(a.roundsDug),
          s(a.lamportsDeployed), a.breakReason, a.mode, s(a.startTs), s(a.endTs), false, contextSlot, data,
        ]),
        `ON CONFLICT (dataset, address) DO UPDATE SET dark_rounds=EXCLUDED.dark_rounds, rounds_dug=EXCLUDED.rounds_dug,
         lamports_deployed=EXCLUDED.lamports_deployed, break_reason=EXCLUDED.break_reason, end_round=EXCLUDED.end_round,
         end_ts=EXCLUDED.end_ts, closed=FALSE, context_slot=EXCLUDED.context_slot, data=EXCLUDED.data ${newer("acc_shift_logs")}`,
      );
      await insertMany(
        db,
        "acc_seeker_seats",
        ["dataset", "address", "sgt_mint", "rig", "authority", "member_number", "verified_slot", "closed", "context_slot", "data"],
        snap.seats.map(({ address, account: a, data }) => [
          d, address, a.sgtMint, a.rig, a.authority, s(a.memberNumber), s(a.verifiedSlot), false, contextSlot, data,
        ]),
        `ON CONFLICT (dataset, address) DO UPDATE SET rig=EXCLUDED.rig, authority=EXCLUDED.authority,
         member_number=EXCLUDED.member_number, verified_slot=EXCLUDED.verified_slot, closed=FALSE,
         context_slot=EXCLUDED.context_slot, data=EXCLUDED.data ${newer("acc_seeker_seats")}`,
      );
      for (const t of ["acc_rigs", "acc_shift_logs", "acc_seeker_seats"]) {
        // Closing is itself an observation at `contextSlot`, so an older scan cannot reopen it.
        await db.query(
          `UPDATE ${t} SET closed = TRUE, context_slot = $2 WHERE dataset = $1 AND context_slot < $2 AND NOT closed`,
          [d, contextSlot],
        );
      }
      if (snap.config) {
        const { address, account: c, data } = snap.config;
        await insertMany(
          db,
          "acc_config",
          ["dataset", "address", "paused", "crank_fee", "executor_fee", "bury_bps", "context_slot", "data"],
          [[d, address, c.paused, s(c.crankFee), s(c.executorFee), c.buryBps, contextSlot, data]],
          `ON CONFLICT (dataset) DO UPDATE SET paused=EXCLUDED.paused, crank_fee=EXCLUDED.crank_fee,
           executor_fee=EXCLUDED.executor_fee, bury_bps=EXCLUDED.bury_bps, context_slot=EXCLUDED.context_slot,
           data=EXCLUDED.data ${newer("acc_config")}`,
        );
      }
    });
  }

  async getCursor(source: string, key: string): Promise<string | null> {
    const [r] = await this.db.query<{ value: string }>(
      "SELECT value FROM ingest_cursors WHERE dataset = $1 AND source = $2 AND key = $3",
      [this.dataset, source, key],
    );
    return r?.value ?? null;
  }

  async setCursor(source: string, key: string, value: string): Promise<void> {
    await this.db.query(
      `INSERT INTO ingest_cursors (dataset, source, key, value) VALUES ($1,$2,$3,$4)
       ON CONFLICT (dataset, source, key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()`,
      [this.dataset, source, key, value],
    );
  }

  /**
   * Notes that an ingest pass finished at `at` (unix seconds) and whether it succeeded; /v1/health
   * shows it. Kept with the cursors, so it survives a restart and a separate `ingest` process reports too.
   */
  async recordPoll(at: number, ok: boolean): Promise<void> {
    const rows: Param[][] = [[this.dataset, "ingest", "last-poll", `${at}:${ok ? "ok" : "failed"}`]];
    if (ok) rows.push([this.dataset, "ingest", "last-ok-poll", String(at)]);
    await insertMany(
      this.db,
      "ingest_cursors",
      ["dataset", "source", "key", "value"],
      rows,
      "ON CONFLICT (dataset, source, key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()",
    );
  }

  // ------------------------------------------------------------------ reads

  async loadMetricsInput(): Promise<MetricsInput> {
    const d = [this.dataset];
    const q = <T>(sql: string) => this.db.query<Record<string, unknown>>(sql, d).then((rows) => rows as T);
    const [digs, skips, arms, ends, seekers, deploys, rounds, rigs, seats, config, registered, closedRigs] = await Promise.all([
      q<Record<string, unknown>[]>(
        `SELECT d.signature, d.idx, d.slot, d.block_time, d.rig, d.round_id::text AS round_id, d.lamports::text AS lamports, d.mask,
                d.ema_ev::text AS ema_ev, t.fee_payer
         FROM ev_rig_dug d JOIN txs t ON t.dataset = d.dataset AND t.signature = d.signature
         WHERE d.dataset = $1 ORDER BY d.slot, d.signature, d.idx`,
      ),
      q<Record<string, unknown>[]>(
        `SELECT signature, block_time, rig, round_id::text AS round_id, error_code FROM ev_rig_skipped WHERE dataset = $1 ORDER BY slot, signature, idx`,
      ),
      q<Record<string, unknown>[]>(
        `SELECT signature, block_time, rig, shift_id::text AS shift_id FROM ev_shift_armed WHERE dataset = $1 ORDER BY slot, signature, idx`,
      ),
      q<Record<string, unknown>[]>(
        `SELECT signature, block_time, rig, shift_id::text AS shift_id, dark_rounds::text AS dark_rounds, rounds_dug::text AS rounds_dug,
                lamports::text AS lamports, reason, tag, start_round::text AS start_round, end_round::text AS end_round, mode
         FROM ev_shift_ended WHERE dataset = $1 ORDER BY slot, signature, idx`,
      ),
      q<Record<string, unknown>[]>(
        `SELECT signature, slot, block_time, rig, sgt_mint, member_number::text AS member_number FROM ev_seeker_verified WHERE dataset = $1 ORDER BY slot, signature, idx`,
      ),
      q<Record<string, unknown>[]>(
        `SELECT signature, idx, block_time, authority, amount::text AS amount, mask, round_id::text AS round_id, total_squares, ts
         FROM ore_deploys WHERE dataset = $1 ORDER BY slot, signature, idx`,
      ),
      q<Record<string, unknown>[]>(
        `SELECT round_id::text AS round_id, ts, winning_square, top_miner, total_miners::text AS total_miners, motherlode::text AS motherlode,
                total_deployed::text AS total_deployed, total_minted::text AS total_minted,
                deployed_winning_square::text AS deployed_winning_square, reset_signature
         FROM ore_rounds WHERE dataset = $1 ORDER BY ore_rounds.round_id`,
      ),
      q<Record<string, unknown>[]>(
        `SELECT address, authority, tier, state, sgt_mint, lifetime_dark_rounds::text AS lifetime_dark_rounds,
                lifetime_rounds_dug::text AS lifetime_rounds_dug, lifetime_lamports_deployed::text AS lifetime_lamports_deployed,
                streak, closed
         FROM acc_rigs WHERE dataset = $1 ORDER BY address`,
      ),
      q<Record<string, unknown>[]>(
        `SELECT address, sgt_mint, rig, member_number::text AS member_number, closed FROM acc_seeker_seats WHERE dataset = $1 ORDER BY address`,
      ),
      q<Record<string, unknown>[]>(
        `SELECT address, paused, crank_fee::text AS crank_fee, executor_fee::text AS executor_fee FROM acc_config WHERE dataset = $1`,
      ),
      q<Record<string, unknown>[]>(
        `SELECT signature, slot, block_time, rig, authority, tier, attestation_level FROM ev_rig_registered WHERE dataset = $1 ORDER BY slot, signature, idx`,
      ),
      q<Record<string, unknown>[]>(`SELECT signature, slot, block_time, rig FROM ev_rig_closed WHERE dataset = $1 ORDER BY slot, signature, idx`),
    ]);
    const roundStates = await this.loadRoundStates();
    return {
      digs: digs.map(
        (r): DigRow => ({
          signature: r.signature as string,
          idx: num(r.idx),
          slot: num(r.slot),
          blockTime: optNum(r.block_time),
          rig: r.rig as string,
          roundId: big(r.round_id),
          lamports: big(r.lamports),
          mask: num(r.mask),
          emaEv: big(r.ema_ev),
          feePayer: r.fee_payer as string,
        }),
      ),
      skips: skips.map(
        (r): SkipRow => ({
          signature: r.signature as string,
          blockTime: optNum(r.block_time),
          rig: r.rig as string,
          roundId: big(r.round_id),
          errorCode: num(r.error_code),
        }),
      ),
      arms: arms.map(
        (r): ArmRow => ({ signature: r.signature as string, blockTime: optNum(r.block_time), rig: r.rig as string, shiftId: big(r.shift_id) }),
      ),
      ends: ends.map(
        (r): EndRow => ({
          signature: r.signature as string,
          blockTime: optNum(r.block_time),
          rig: r.rig as string,
          shiftId: big(r.shift_id),
          darkRounds: big(r.dark_rounds),
          roundsDug: big(r.rounds_dug),
          lamports: big(r.lamports),
          reason: num(r.reason),
          tag: num(r.tag) === 10 ? 10 : 4,
          startRound: optBig(r.start_round),
          endRound: optBig(r.end_round),
          mode: optNum(r.mode),
        }),
      ),
      seekers: seekers.map(
        (r): SeekerRow => ({
          signature: r.signature as string,
          slot: num(r.slot),
          blockTime: optNum(r.block_time),
          rig: r.rig as string,
          sgtMint: r.sgt_mint as string,
          memberNumber: big(r.member_number),
        }),
      ),
      deploys: deploys.map(
        (r): DeployRow => ({
          signature: r.signature as string,
          idx: num(r.idx),
          blockTime: optNum(r.block_time),
          authority: r.authority as string,
          amount: big(r.amount),
          mask: num(r.mask),
          roundId: big(r.round_id),
          totalSquares: num(r.total_squares),
          ts: num(r.ts),
        }),
      ),
      rounds: rounds.map(
        (r): RoundRow => ({
          roundId: big(r.round_id),
          ts: num(r.ts),
          winningSquare: optNum(r.winning_square),
          topMiner: r.top_miner as string,
          totalMiners: big(r.total_miners),
          motherlode: big(r.motherlode),
          totalDeployed: big(r.total_deployed),
          totalMinted: big(r.total_minted),
          deployedWinningSquare: big(r.deployed_winning_square),
          resetSignature: (r.reset_signature as string | null) ?? null,
        }),
      ),
      rigs: rigs.map(
        (r): RigRow => ({
          address: r.address as string,
          authority: r.authority as string,
          tier: num(r.tier),
          state: num(r.state),
          sgtMint: (r.sgt_mint as string | null) ?? null,
          lifetimeDarkRounds: big(r.lifetime_dark_rounds),
          lifetimeRoundsDug: big(r.lifetime_rounds_dug),
          lifetimeLamportsDeployed: big(r.lifetime_lamports_deployed),
          streak: num(r.streak),
          closed: r.closed === true,
        }),
      ),
      seats: seats.map(
        (r): SeatRow => ({
          address: r.address as string,
          sgtMint: r.sgt_mint as string,
          rig: r.rig as string,
          memberNumber: big(r.member_number),
          closed: r.closed === true,
        }),
      ),
      registered: registered.map(
        (r): RegisteredRow => ({
          signature: r.signature as string,
          slot: num(r.slot),
          blockTime: optNum(r.block_time),
          rig: r.rig as string,
          authority: r.authority as string,
          tier: num(r.tier),
          attestationLevel: num(r.attestation_level),
        }),
      ),
      roundStates,
      closedRigs: closedRigs.map(
        (r): ClosedRow => ({ signature: r.signature as string, slot: num(r.slot), blockTime: optNum(r.block_time), rig: r.rig as string }),
      ),
      config: config[0]
        ? ({
            address: config[0].address as string,
            paused: config[0].paused === true,
            crankFee: big(config[0].crank_fee),
            executorFee: big(config[0].executor_fee),
          } satisfies ConfigRow)
        : null,
    };
  }

  /**
   * `lastSlot` and `lastBlockTime` are those of the newest stored transaction, so they stand still
   * whenever no heads_down transaction lands. Whether ingestion itself is running is in the
   * `lastPoll*` fields ({@link recordPoll}): all null until a pass has finished for this dataset.
   */
  async health(): Promise<{
    txs: number;
    failedTxs: number;
    truncatedTxs: number;
    problems: { code: string; count: number }[];
    lastSlot: number | null;
    lastBlockTime: number | null;
    lastPollAt: number | null;
    lastPollOk: boolean | null;
    lastOkPollAt: number | null;
  }> {
    const [t] = await this.db.query<Record<string, unknown>>(
      `SELECT count(*)::int AS n, count(*) FILTER (WHERE failed)::int AS failed, count(*) FILTER (WHERE logs_truncated)::int AS truncated,
              max(slot)::text AS last_slot, max(block_time)::text AS last_bt
       FROM txs WHERE dataset = $1`,
      [this.dataset],
    );
    const problems = await this.db.query<{ code: string; n: number }>(
      "SELECT code, count(*)::int AS n FROM ingest_problems WHERE dataset = $1 GROUP BY code ORDER BY code",
      [this.dataset],
    );
    const polls = new Map(
      (await this.db.query<{ key: string; value: string }>("SELECT key, value FROM ingest_cursors WHERE dataset = $1 AND source = 'ingest'", [this.dataset])).map(
        (r) => [r.key, r.value] as const,
      ),
    );
    const last = /^(\d{1,15}):(ok|failed)$/.exec(polls.get("last-poll") ?? "");
    const lastOk = /^\d{1,15}$/.exec(polls.get("last-ok-poll") ?? "");
    return {
      txs: num(t?.n ?? 0),
      failedTxs: num(t?.failed ?? 0),
      truncatedTxs: num(t?.truncated ?? 0),
      problems: problems.map((p) => ({ code: p.code, count: num(p.n) })),
      lastSlot: optNum(t?.last_slot),
      lastBlockTime: optNum(t?.last_bt),
      lastPollAt: last ? Number(last[1]) : null,
      lastPollOk: last ? last[2] === "ok" : null,
      lastOkPollAt: lastOk ? Number(lastOk[0]) : null,
    };
  }

  // ------------------------------------------------------------------ SKR (v1.2 events)

  /** Counts and sums of the SKR events (tags 11..=23), grouped by name and by their kind-like field. */
  async skrEventGroups(): Promise<SkrEventGroup[]> {
    // Guarded by a digits test: a name can be an amount in one event and an address in another
    // (`bond` is the bonded SKR of a Stack event and the FocusBond account of a bond event).
    const sums = SKR_SUM_FIELDS.map((f) => `COALESCE(sum(CASE WHEN fields->>'${f}' ~ '^[0-9]+$' THEN (fields->>'${f}')::numeric END), 0)::text AS ${f}`).join(", ");
    const rows = await this.db.query<Record<string, unknown>>(
      `SELECT name,
              COALESCE(fields->>'kind', fields->>'recipient_kind', fields->>'source_kind', fields->>'result') AS variant,
              count(*)::int AS n, ${sums}
       FROM ev_ext WHERE dataset = $1 AND tag >= 11 AND tag <= 23
       GROUP BY 1, 2 ORDER BY 1, 2`,
      [this.dataset],
    );
    return rows.map((r) => ({
      name: r.name as string,
      variant: optNum(r.variant),
      count: num(r.n),
      sums: Object.fromEntries(SKR_SUM_FIELDS.map((f) => [f, big(r[f])])) as Record<SkrSumField, bigint>,
    }));
  }

  /** The Bury auction after its latest event, or null before the first lot. */
  async buryState(): Promise<BuryState | null> {
    const latest = (name: string) =>
      this.db.query<{ name: string; fields: unknown }>(
        `SELECT name, fields::text AS fields FROM ev_ext WHERE dataset = $1 AND name = ANY($2::text[])
         ORDER BY slot DESC, signature DESC, idx DESC LIMIT 1`,
        [this.dataset, name],
      );
    const parse = (r: { fields: unknown } | undefined) => (r ? (JSON.parse(r.fields as string) as Record<string, string | number>) : null);
    const any = parse((await latest("{BuryLotAdded,BuryAuctionSold}"))[0]);
    if (!any) return null;
    const lot = parse((await latest("{BuryLotAdded}"))[0]);
    const sale = parse((await latest("{BuryAuctionSold}"))[0]);
    return {
      lotSkr: BigInt(any.lot_skr ?? any.lot_remaining ?? 0),
      lastPrice: sale ? BigInt(sale.price ?? 0) : null,
      startPrice: lot ? BigInt(lot.start_price ?? 0) : null,
      startSlot: lot ? BigInt(lot.start_slot ?? 0) : null,
    };
  }

  // ------------------------------------------------------------------ skips

  /**
   * RigSkipped rows, newest first, keyset-paginated on (slot, signature, idx). `after` is the
   * cursor of the last row of the previous page.
   */
  async listSkips(f: SkipFilter & { limit: number; after?: SkipCursor | null }): Promise<{ rows: SkipListRow[]; next: SkipCursor | null }> {
    const params: Param[] = [this.dataset];
    const where = this.skipWhere(f, params);
    if (f.after) {
      params.push(f.after.slot, f.after.signature, f.after.idx);
      where.push(`(slot, signature, idx) < ($${params.length - 2}::bigint, $${params.length - 1}, $${params.length}::int)`);
    }
    params.push(f.limit + 1);
    const rows = await this.db.query<Record<string, unknown>>(
      `SELECT signature, idx, slot, block_time, rig, round_id::text AS round_id, error_code
       FROM ev_rig_skipped WHERE ${where.join(" AND ")}
       ORDER BY slot DESC, signature DESC, idx DESC LIMIT $${params.length}`,
      params,
    );
    const out = rows.slice(0, f.limit).map(
      (r): SkipListRow => ({
        signature: r.signature as string,
        idx: num(r.idx),
        slot: num(r.slot),
        blockTime: optNum(r.block_time),
        rig: r.rig as string,
        roundId: big(r.round_id),
        errorCode: num(r.error_code),
      }),
    );
    const last = out[out.length - 1];
    return { rows: out, next: rows.length > f.limit && last ? { slot: last.slot, signature: last.signature, idx: last.idx } : null };
  }

  /** Skip counts by error code (and the total) for a filter, ignoring pagination. */
  async skipHistogram(f: SkipFilter): Promise<{ code: number; count: number }[]> {
    const params: Param[] = [this.dataset];
    const where = this.skipWhere(f, params);
    const rows = await this.db.query<{ error_code: string | number; n: number }>(
      `SELECT error_code, count(*)::int AS n FROM ev_rig_skipped WHERE ${where.join(" AND ")} GROUP BY error_code ORDER BY error_code`,
      params,
    );
    return rows.map((r) => ({ code: num(r.error_code), count: num(r.n) }));
  }

  private skipWhere(f: SkipFilter, params: Param[]): string[] {
    const where = ["dataset = $1"];
    if (f.rig) {
      params.push(f.rig);
      where.push(`rig = $${params.length}`);
    }
    if (f.errorCode !== undefined && f.errorCode !== null) {
      params.push(f.errorCode);
      where.push(`error_code = $${params.length}`);
    }
    if (f.fromTime !== undefined && f.fromTime !== null) {
      params.push(f.fromTime);
      where.push(`block_time >= $${params.length}`);
    }
    if (f.toTime !== undefined && f.toTime !== null) {
      params.push(f.toTime);
      where.push(`block_time <= $${params.length}`);
    }
    return where;
  }

  // ------------------------------------------------------------------ haul

  /** Everything about one rig's shifts that the haul needs to pick a shift and replay its streak. */
  async rigShifts(rig: string): Promise<RigShifts> {
    const d = this.dataset;
    const [ends, arms, registered, closed, logs, acct] = await Promise.all([
      this.db.query<Record<string, unknown>>(
        `SELECT signature, idx, slot, block_time, shift_id::text AS shift_id, dark_rounds::text AS dark_rounds, rounds_dug::text AS rounds_dug,
                lamports::text AS lamports, reason, tag, start_round::text AS start_round, end_round::text AS end_round, mode
         FROM ev_shift_ended WHERE dataset = $1 AND rig = $2 ORDER BY slot, signature, idx`,
        [d, rig],
      ),
      this.db.query<Record<string, unknown>>(
        `SELECT signature, idx, slot, block_time, shift_id::text AS shift_id FROM ev_shift_armed WHERE dataset = $1 AND rig = $2 ORDER BY slot, signature, idx`,
        [d, rig],
      ),
      this.db.query<Record<string, unknown>>(
        `SELECT signature, idx, slot, block_time, authority FROM ev_rig_registered WHERE dataset = $1 AND rig = $2 ORDER BY slot, signature, idx`,
        [d, rig],
      ),
      this.db.query<Record<string, unknown>>(`SELECT slot FROM ev_rig_closed WHERE dataset = $1 AND rig = $2 ORDER BY slot`, [d, rig]),
      this.db.query<Record<string, unknown>>(
        `SELECT address, shift_id::text AS shift_id, start_round::text AS start_round, end_round::text AS end_round, dark_rounds::text AS dark_rounds,
                rounds_dug::text AS rounds_dug, lamports_deployed::text AS lamports_deployed, break_reason, mode, start_ts::text AS start_ts,
                end_ts::text AS end_ts, closed
         FROM acc_shift_logs WHERE dataset = $1 AND rig = $2`,
        [d, rig],
      ),
      this.db.query<Record<string, unknown>>(
        `SELECT authority, tier, streak, closed, context_slot FROM acc_rigs WHERE dataset = $1 AND address = $2`,
        [d, rig],
      ),
    ]);
    const a = acct[0];
    return {
      ends: ends.map((r) => ({
        signature: r.signature as string,
        idx: num(r.idx),
        slot: num(r.slot),
        blockTime: optNum(r.block_time),
        shiftId: big(r.shift_id),
        darkRounds: big(r.dark_rounds),
        roundsDug: big(r.rounds_dug),
        lamports: big(r.lamports),
        reason: num(r.reason),
        tag: num(r.tag) === 10 ? 10 : 4,
        startRound: optBig(r.start_round),
        endRound: optBig(r.end_round),
        mode: optNum(r.mode),
      })),
      arms: arms.map((r) => ({ signature: r.signature as string, idx: num(r.idx), slot: num(r.slot), blockTime: optNum(r.block_time), shiftId: big(r.shift_id) })),
      registered: registered.map((r) => ({ signature: r.signature as string, idx: num(r.idx), slot: num(r.slot), blockTime: optNum(r.block_time), authority: r.authority as string })),
      closedSlots: closed.map((r) => num(r.slot)),
      shiftLogs: logs.map((r) => ({
        address: r.address as string,
        shiftId: big(r.shift_id),
        startRound: big(r.start_round),
        endRound: big(r.end_round),
        darkRounds: big(r.dark_rounds),
        roundsDug: big(r.rounds_dug),
        lamportsDeployed: big(r.lamports_deployed),
        breakReason: num(r.break_reason),
        mode: num(r.mode),
        startTs: big(r.start_ts),
        endTs: big(r.end_ts),
        closed: r.closed === true,
      })),
      account: a ? { authority: a.authority as string, tier: num(a.tier), streak: num(a.streak), closed: a.closed === true, contextSlot: num(a.context_slot) } : null,
    };
  }

  /**
   * The activity of one rig inside one shift (slots [fromSlot, toSlot]) and the ORE outcomes of
   * rounds [startRound, endRound], plus those of any round it dug outside that range.
   */
  async shiftActivity(rig: string, q: { fromSlot: number; toSlot: number; startRound: bigint; endRound: bigint; armSignature: string | null }): Promise<ShiftActivity> {
    const d = this.dataset;
    const [digs, heartbeats, plans, broken] = await Promise.all([
      this.db.query<Record<string, unknown>>(
        `SELECT signature, idx, slot, block_time, round_id::text AS round_id, lamports::text AS lamports, mask
         FROM ev_rig_dug WHERE dataset = $1 AND rig = $2 AND slot BETWEEN $3 AND $4 ORDER BY slot, signature, idx`,
        [d, rig, q.fromSlot, q.toSlot],
      ),
      this.db.query<Record<string, unknown>>(
        `SELECT signature, slot, kind, fresh, counter::text AS counter, hb_round::text AS hb_round, lease_rounds, applied, authority
         FROM hd_heartbeats WHERE dataset = $1 AND rig = $2 AND slot BETWEEN $3 AND $4 ORDER BY slot, signature, ix_idx, entry_idx`,
        [d, rig, q.fromSlot, q.toSlot],
      ),
      q.armSignature
        ? this.db.query<Record<string, unknown>>(
            `SELECT lease_rounds, flags FROM hd_arm_plans WHERE dataset = $1 AND rig = $2 AND signature = $3 ORDER BY ix_idx LIMIT 1`,
            [d, rig, q.armSignature],
          )
        : Promise.resolve([] as Record<string, unknown>[]),
      this.db.query<Record<string, unknown>>(
        `SELECT slot, block_time, reason FROM ev_shift_broken WHERE dataset = $1 AND rig = $2 AND slot BETWEEN $3 AND $4 ORDER BY slot`,
        [d, rig, q.fromSlot, q.toSlot],
      ),
    ]);
    const sigs = [...new Set(digs.map((r) => r.signature as string))];
    const deploys = sigs.length
      ? await this.db.query<Record<string, unknown>>(
          `SELECT signature, authority, amount::text AS amount, mask, round_id::text AS round_id
           FROM ore_deploys WHERE dataset = $1 AND signature = ANY($2::text[]) ORDER BY signature, idx`,
          [d, `{${sigs.join(",")}}`],
        )
      : [];
    const beyond = [...new Set(digs.map((r) => big(r.round_id)))].filter((r) => r < q.startRound || r > q.endRound);
    const outcomes = await this.roundOutcomeRows(q.startRound, q.endRound, beyond);
    const plan = plans[0];
    return {
      digs: digs.map((r) => ({ signature: r.signature as string, slot: num(r.slot), blockTime: optNum(r.block_time), roundId: big(r.round_id), lamports: big(r.lamports), mask: num(r.mask) })),
      heartbeats: heartbeats.map((r) => ({
        signature: r.signature as string,
        slot: num(r.slot),
        kind: r.kind as "dig" | "record",
        fresh: r.fresh === true,
        counter: big(r.counter),
        hbRound: big(r.hb_round),
        leaseRounds: num(r.lease_rounds),
        applied: r.applied === null || r.applied === undefined ? null : r.applied === true,
        authority: (r.authority as string | null) ?? null,
      })),
      plan: plan ? { lease: num(plan.lease_rounds), flags: num(plan.flags) } : null,
      broken: broken.map((r) => ({ slot: num(r.slot), blockTime: optNum(r.block_time), reason: num(r.reason) })),
      deploys: deploys.map((r) => ({ signature: r.signature as string, authority: r.authority as string, amount: big(r.amount), mask: num(r.mask), roundId: big(r.round_id) })),
      ...outcomes,
    };
  }

  /** ResetEvents and Round-account snapshots for rounds [from, to] and the listed `also` rounds. */
  async roundOutcomeRows(from: bigint, to: bigint, also: readonly bigint[] = []): Promise<{ resets: RoundRow[]; states: RoundStateRow[] }> {
    const d = this.dataset;
    const extra = `{${also.join(",")}}`;
    const [resets, states] = await Promise.all([
      this.db.query<Record<string, unknown>>(
        `SELECT round_id::text AS round_id, ts, winning_square, top_miner, total_miners::text AS total_miners, motherlode::text AS motherlode,
                total_deployed::text AS total_deployed, total_minted::text AS total_minted,
                deployed_winning_square::text AS deployed_winning_square, reset_signature
         FROM ore_rounds WHERE dataset = $1 AND (round_id BETWEEN $2 AND $3 OR round_id = ANY($4::numeric[])) ORDER BY ore_rounds.round_id`,
        [d, s(from), s(to), extra],
      ),
      this.db.query<Record<string, unknown>>(
        `SELECT round_id::text AS round_id, data, context_slot FROM ore_round_state
         WHERE dataset = $1 AND (round_id BETWEEN $2 AND $3 OR round_id = ANY($4::numeric[])) ORDER BY ore_round_state.round_id`,
        [d, s(from), s(to), extra],
      ),
    ]);
    return {
      resets: resets.map(roundRowOf),
      states: states.map((r) => ({ roundId: big(r.round_id), data: toBytes(r.data), contextSlot: num(r.context_slot) })),
    };
  }

  // ------------------------------------------------------------------ ORE round resolver

  /**
   * Rounds whose outcome the indexer still needs from chain, newest first: every round a Heads
   * Down DeployEvent landed in without a Round-account snapshot (exact per-square totals), plus,
   * when `withShiftRounds`, the rounds a haul lists for the 50 most recently ended shifts (the
   * first `maxPerShift` of each) that have neither a snapshot nor a ResetEvent.
   */
  async roundsToResolve(limit: number, withShiftRounds: boolean, maxPerShift = 4096): Promise<bigint[]> {
    const d = this.dataset;
    // ORDER BY the numeric id: ordering by the `round_id` output alias would sort its ::text cast ("5001" > "14000").
    const dug = await this.db.query<{ round_id: string }>(
      `SELECT x.rid::text AS round_id FROM (
         SELECT DISTINCT o.round_id AS rid FROM ore_deploys o WHERE o.dataset = $1
           AND NOT EXISTS (SELECT 1 FROM ore_round_state s WHERE s.dataset = o.dataset AND s.round_id = o.round_id)
           AND NOT EXISTS (SELECT 1 FROM ore_round_missing m WHERE m.dataset = o.dataset AND m.round_id = o.round_id)
       ) x ORDER BY x.rid DESC LIMIT $2`,
      [d, limit],
    );
    const out = dug.map((r) => BigInt(r.round_id));
    if (withShiftRounds && out.length < limit) {
      // The set difference runs in Postgres: no round list crosses the wire unless it needs resolving.
      const shift = await this.db.query<{ round_id: string }>(
        `SELECT w.round_id::text AS round_id FROM (
           SELECT DISTINCT generate_series(e.start_round::numeric, LEAST(e.end_round::numeric, e.start_round::numeric + $3::numeric - 1)) AS round_id
           FROM (SELECT start_round, end_round FROM ev_shift_ended WHERE dataset = $1 AND tag = 10 ORDER BY slot DESC LIMIT 50) e
         ) w
         WHERE NOT EXISTS (SELECT 1 FROM ore_round_state s WHERE s.dataset = $1 AND s.round_id = w.round_id)
           AND NOT EXISTS (SELECT 1 FROM ore_rounds r WHERE r.dataset = $1 AND r.round_id = w.round_id)
           AND NOT EXISTS (SELECT 1 FROM ore_round_missing m WHERE m.dataset = $1 AND m.round_id = w.round_id)
         ORDER BY w.round_id DESC LIMIT $2`,
        [d, limit, maxPerShift],
      );
      const seen = new Set(out);
      for (const r of shift) {
        if (out.length >= limit) break;
        const x = BigInt(r.round_id);
        if (!seen.has(x)) out.push(x);
      }
    }
    return out;
  }

  async upsertRoundStates(rows: { address: string; account: OreRoundAccount; data: Uint8Array; contextSlot: number }[], source: string): Promise<void> {
    await insertMany(
      this.db,
      "ore_round_state",
      ["dataset", "round_id", "address", "deployed", "slot_hash", "expires_at", "motherlode", "top_miner", "rewards_total", "total_vaulted",
        "total_returned_sol", "total_miners", "context_slot", "source", "data"],
      rows.map(({ address, account: a, data, contextSlot }) => [
        this.dataset, s(a.id), address, a.deployed.map(s).join(","), a.slotHash, s(a.expiresAt), s(a.motherlode), a.topMiner,
        s(a.rewards.reduce((x, y) => x + y, 0n)), s(a.totalVaulted), s(a.totalReturnedSol), s(a.totalMiners), contextSlot, source, data,
      ]),
      `ON CONFLICT (dataset, round_id) DO UPDATE SET deployed = EXCLUDED.deployed, slot_hash = EXCLUDED.slot_hash, expires_at = EXCLUDED.expires_at,
       motherlode = EXCLUDED.motherlode, top_miner = EXCLUDED.top_miner, rewards_total = EXCLUDED.rewards_total, total_vaulted = EXCLUDED.total_vaulted,
       total_returned_sol = EXCLUDED.total_returned_sol, total_miners = EXCLUDED.total_miners, context_slot = EXCLUDED.context_slot,
       source = EXCLUDED.source, data = EXCLUDED.data WHERE ore_round_state.context_slot <= EXCLUDED.context_slot`,
    );
  }

  async markRoundsMissing(ids: bigint[]): Promise<void> {
    await insertMany(this.db, "ore_round_missing", ["dataset", "round_id"], ids.map((id) => [this.dataset, s(id)]));
  }

  /** Dug rounds (Heads Down DeployEvents) that have no ResetEvent yet, newest first. */
  async dugRoundsWithoutReset(limit: number): Promise<bigint[]> {
    const rows = await this.db.query<{ round_id: string }>(
      `SELECT x.rid::text AS round_id FROM (
         SELECT DISTINCT o.round_id AS rid FROM ore_deploys o WHERE o.dataset = $1
           AND NOT EXISTS (SELECT 1 FROM ore_rounds r WHERE r.dataset = o.dataset AND r.round_id = o.round_id)
       ) x ORDER BY x.rid DESC LIMIT $2`,
      [this.dataset, limit],
    );
    return rows.map((r) => BigInt(r.round_id));
  }

  /** All Round-account snapshots (summary ORE-mined fallback when a ResetEvent is missing). */
  async loadRoundStates(): Promise<RoundStateRow[]> {
    const rows = await this.db.query<Record<string, unknown>>(
      `SELECT round_id::text AS round_id, data, context_slot FROM ore_round_state WHERE dataset = $1 ORDER BY ore_round_state.round_id`,
      [this.dataset],
    );
    return rows.map((r) => ({ roundId: big(r.round_id), data: toBytes(r.data), contextSlot: num(r.context_slot) }));
  }
}

export interface SkipFilter {
  rig?: string | null;
  errorCode?: number | null;
  fromTime?: number | null;
  toTime?: number | null;
}
export interface SkipCursor {
  slot: number;
  signature: string;
  idx: number;
}
export interface SkipListRow {
  signature: string;
  idx: number;
  slot: number;
  blockTime: number | null;
  rig: string;
  roundId: bigint;
  errorCode: number;
}


export interface RigShifts {
  ends: {
    signature: string;
    idx: number;
    slot: number;
    blockTime: number | null;
    shiftId: bigint;
    darkRounds: bigint;
    roundsDug: bigint;
    lamports: bigint;
    reason: number;
    tag: 4 | 10;
    startRound: bigint | null;
    endRound: bigint | null;
    mode: number | null;
  }[];
  arms: { signature: string; idx: number; slot: number; blockTime: number | null; shiftId: bigint }[];
  registered: { signature: string; idx: number; slot: number; blockTime: number | null; authority: string }[];
  closedSlots: number[];
  shiftLogs: {
    address: string;
    shiftId: bigint;
    startRound: bigint;
    endRound: bigint;
    darkRounds: bigint;
    roundsDug: bigint;
    lamportsDeployed: bigint;
    breakReason: number;
    mode: number;
    startTs: bigint;
    endTs: bigint;
    closed: boolean;
  }[];
  account: { authority: string; tier: number; streak: number; closed: boolean; contextSlot: number } | null;
}

export interface ShiftActivity {
  digs: { signature: string; slot: number; blockTime: number | null; roundId: bigint; lamports: bigint; mask: number }[];
  heartbeats: {
    signature: string;
    slot: number;
    kind: "dig" | "record";
    fresh: boolean;
    counter: bigint;
    hbRound: bigint;
    leaseRounds: number;
    applied: boolean | null;
    authority: string | null;
  }[];
  plan: { lease: number; flags: number } | null;
  broken: { slot: number; blockTime: number | null; reason: number }[];
  deploys: { signature: string; authority: string; amount: bigint; mask: number; roundId: bigint }[];
  resets: RoundRow[];
  states: RoundStateRow[];
}

function toBytes(v: unknown): Uint8Array {
  if (v instanceof Uint8Array) return new Uint8Array(v);
  throw new Error("expected BYTEA");
}

function roundRowOf(r: Record<string, unknown>): RoundRow {
  return {
    roundId: big(r.round_id),
    ts: num(r.ts),
    winningSquare: optNum(r.winning_square),
    topMiner: r.top_miner as string,
    totalMiners: big(r.total_miners),
    motherlode: big(r.motherlode),
    totalDeployed: big(r.total_deployed),
    totalMinted: big(r.total_minted),
    deployedWinningSquare: big(r.deployed_winning_square),
    resetSignature: (r.reset_signature as string | null) ?? null,
  };
}
