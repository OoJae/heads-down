/**
 * The indexer's only data access layer. A Store is bound to ONE dataset at construction and
 * every statement filters on it; there is no API that reads across datasets. That is the
 * mechanism behind "simulated data is never mixed with real data".
 */
import type { Db, Param } from "./db.ts";
import type { ExtractedTx } from "../codec/tx.ts";
import type { OreResetEvent } from "../codec/ore.ts";
import type { ConfigAccount, RigAccount, SeekerSeatAccount, ShiftLogAccount } from "../codec/accounts.ts";
import type {
  ArmRow,
  ConfigRow,
  Dataset,
  DatasetInfo,
  DeployRow,
  DigRow,
  EndRow,
  MetricsInput,
  RigRow,
  RoundRow,
  SeatRow,
  SeekerRow,
  SkipRow,
} from "../model.ts";

const MAX_PARAMS = 30_000;

const s = (v: bigint | number) => v.toString();
const big = (v: unknown): bigint => BigInt(v as string);
const num = (v: unknown): number => Number(v);
const optNum = (v: unknown): number | null => (v === null || v === undefined ? null : Number(v));

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
        ["dataset", "signature", "slot", "block_time", "failed", "logs_truncated", "source"],
        fresh.map((x) => [d, x.signature, x.slot, x.blockTime, x.failed, x.logsTruncated, source]),
      );
      const dug: Param[][] = [];
      const skipped: Param[][] = [];
      const armed: Param[][] = [];
      const ended: Param[][] = [];
      const seeker: Param[][] = [];
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
              ended.push([...head, s(e.shiftId), s(e.darkRounds), s(e.roundsDug), s(e.lamports), e.reason, raw]);
              break;
            case "SeekerVerified":
              seeker.push([...head, e.sgtMint, s(e.memberNumber), raw]);
              break;
          }
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
      await insertMany(db, "ev_shift_ended", [...ev, "shift_id", "dark_rounds", "rounds_dug", "lamports", "reason", "raw"], ended);
      await insertMany(db, "ev_seeker_verified", [...ev, "sgt_mint", "member_number", "raw"], seeker);
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

  // ------------------------------------------------------------------ reads

  async loadMetricsInput(): Promise<MetricsInput> {
    const d = [this.dataset];
    const q = <T>(sql: string) => this.db.query<Record<string, unknown>>(sql, d).then((rows) => rows as T);
    const [digs, skips, arms, ends, seekers, deploys, rounds, rigs, seats, config] = await Promise.all([
      q<Record<string, unknown>[]>(
        `SELECT signature, idx, slot, block_time, rig, round_id::text AS round_id, lamports::text AS lamports, mask, ema_ev::text AS ema_ev
         FROM ev_rig_dug WHERE dataset = $1 ORDER BY slot, signature, idx`,
      ),
      q<Record<string, unknown>[]>(
        `SELECT signature, block_time, rig, round_id::text AS round_id, error_code FROM ev_rig_skipped WHERE dataset = $1 ORDER BY slot, signature, idx`,
      ),
      q<Record<string, unknown>[]>(
        `SELECT signature, block_time, rig, shift_id::text AS shift_id FROM ev_shift_armed WHERE dataset = $1 ORDER BY slot, signature, idx`,
      ),
      q<Record<string, unknown>[]>(
        `SELECT signature, block_time, rig, shift_id::text AS shift_id, dark_rounds::text AS dark_rounds, rounds_dug::text AS rounds_dug,
                lamports::text AS lamports, reason
         FROM ev_shift_ended WHERE dataset = $1 ORDER BY slot, signature, idx`,
      ),
      q<Record<string, unknown>[]>(
        `SELECT signature, block_time, rig, sgt_mint, member_number::text AS member_number FROM ev_seeker_verified WHERE dataset = $1 ORDER BY slot, signature, idx`,
      ),
      q<Record<string, unknown>[]>(
        `SELECT signature, idx, block_time, authority, amount::text AS amount, mask, round_id::text AS round_id, total_squares, ts
         FROM ore_deploys WHERE dataset = $1 ORDER BY slot, signature, idx`,
      ),
      q<Record<string, unknown>[]>(
        `SELECT round_id::text AS round_id, ts, winning_square, top_miner, total_miners::text AS total_miners, motherlode::text AS motherlode,
                total_deployed::text AS total_deployed, total_minted::text AS total_minted,
                deployed_winning_square::text AS deployed_winning_square, reset_signature
         FROM ore_rounds WHERE dataset = $1 ORDER BY round_id`,
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
    ]);
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
        }),
      ),
      seekers: seekers.map(
        (r): SeekerRow => ({
          signature: r.signature as string,
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

  async health(): Promise<{ txs: number; failedTxs: number; truncatedTxs: number; problems: { code: string; count: number }[]; lastSlot: number | null; lastBlockTime: number | null }> {
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
    return {
      txs: num(t?.n ?? 0),
      failedTxs: num(t?.failed ?? 0),
      truncatedTxs: num(t?.truncated ?? 0),
      problems: problems.map((p) => ({ code: p.code, count: num(p.n) })),
      lastSlot: optNum(t?.last_slot),
      lastBlockTime: optNum(t?.last_bt),
    };
  }
}
