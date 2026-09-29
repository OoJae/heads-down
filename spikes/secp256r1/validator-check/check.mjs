// Spike 1(b) cross-check on a real Agave validator (solana-test-validator).
//
// Signs with Node's built-in crypto (OpenSSL, random k), a third ECDSA
// implementation beside RustCrypto p256 and the precompile's OpenSSL verify,
// and produces DER exactly like Android's SHA256withECDSA.
//
//   node check.mjs forge <fake-sysvar-account.json> <fixture.json>
//   node check.mjs run   <fixture.json>              (RPC_URL, PROGRAM_ID env)
import crypto from 'node:crypto';
import fs from 'node:fs';
import {
  Connection,
  Keypair,
  LAMPORTS_PER_SOL,
  PublicKey,
  Transaction,
  TransactionInstruction,
} from '@solana/web3.js';

const SECP256R1 = new PublicKey('Secp256r1SigVerify1111111111111111111111111');
const IX_SYSVAR = new PublicKey('Sysvar1nstructions1111111111111111111111111');
const N = 0xffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551n;
const HALF_N = N >> 1n;
const ERR = { // p256-introspect codes
  NotSecp256r1Instruction: 0x25600004,
  ForeignInstructionIndex: 0x25600007,
  PublicKeyMismatch: 0x2560000d,
  MessageMismatch: 0x2560000e,
  InvalidInstructionsSysvar: 0x25600001,
};
const PRECOMPILE_INVALID_SIGNATURE = 2;

// ------------------------------------------------------------ P-256 ---

const to32 = (x) => Buffer.from(x.toString(16).padStart(64, '0'), 'hex');
const toBig = (b) => BigInt('0x' + (Buffer.from(b).toString('hex') || '0'));

function newKey() {
  const { privateKey, publicKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const spki = publicKey.export({ type: 'spki', format: 'der' }); // 91 bytes, like PublicKey.getEncoded()
  if (spki.length !== 91) throw new Error('unexpected SPKI length');
  const point = spki.subarray(26); // 04 || x || y
  const compressed = Buffer.concat([Buffer.from([point[64] & 1 ? 3 : 2]), point.subarray(1, 33)]);
  return { privateKey, compressed };
}

/** DER ECDSA-Sig-Value -> { r, s } as BigInt. */
function parseDer(der) {
  if (der[0] !== 0x30 || der[1] !== der.length - 2) throw new Error('bad DER');
  let i = 2;
  const int = () => {
    if (der[i] !== 0x02) throw new Error('bad DER int');
    const len = der[i + 1];
    const v = toBig(der.subarray(i + 2, i + 2 + len));
    i += 2 + len;
    return v;
  };
  const r = int();
  const s = int();
  if (i !== der.length) throw new Error('trailing DER');
  return { r, s };
}

function keystoreSign(key, msg) {
  const der = crypto.sign('sha256', msg, key.privateKey); // DER, s not normalized
  const { r, s } = parseDer(der);
  const low = s > HALF_N ? N - s : s;
  return { raw: Buffer.concat([to32(r), to32(low)]), wasHigh: s > HALF_N, r, s: low };
}

// ------------------------------------------------------- instructions ---

function precompileData(entries, index = 0xffff) {
  const header = 2 + 14 * entries.length;
  const parts = [];
  const offsets = Buffer.alloc(header);
  offsets[0] = entries.length;
  let pos = header;
  entries.forEach(({ sig, pk, msg }, i) => {
    const pkOff = pos; pos += 33;
    const sigOff = pos; pos += 64;
    const msgOff = pos; pos += msg.length;
    parts.push(pk, sig, msg);
    const o = 2 + 14 * i;
    [sigOff, index, pkOff, index, msgOff, msg.length, index].forEach((v, k) => offsets.writeUInt16LE(v, o + 2 * k));
  });
  return Buffer.concat([offsets, ...parts]);
}

const precompileIx = (data) => new TransactionInstruction({ programId: SECP256R1, keys: [], data });

function verifyIx(programId, precompileIndex, sigIndex, pk, msg, sysvar = IX_SYSVAR) {
  const head = Buffer.alloc(4);
  head[0] = 0; // verify_heartbeat
  head.writeUInt16LE(precompileIndex, 1);
  head[3] = sigIndex;
  return new TransactionInstruction({
    programId,
    keys: [{ pubkey: sysvar, isSigner: false, isWritable: false }],
    data: Buffer.concat([head, pk, msg]),
  });
}

function heartbeat(round, counter) {
  const m = Buffer.alloc(101);
  m.write('HDv1', 0);
  m.fill(0xa1, 36, 68);
  m.writeBigUInt64LE(BigInt(round), 68);
  m.writeBigUInt64LE(BigInt(counter), 76);
  m[84] = 1;
  return m;
}

/** Byte-for-byte instructions-sysvar image (solana-instructions-sysvar layout). */
function forgeSysvar(ixs, current) {
  const parts = [];
  const header = Buffer.alloc(2 + 2 * ixs.length);
  header.writeUInt16LE(ixs.length, 0);
  let pos = header.length;
  ixs.forEach(({ programId, data }, i) => {
    header.writeUInt16LE(pos, 2 + 2 * i);
    const body = Buffer.concat([Buffer.from([0, 0]), programId.toBuffer(), Buffer.alloc(2), data]);
    body.writeUInt16LE(data.length, 34);
    parts.push(body);
    pos += body.length;
  });
  const tail = Buffer.alloc(2);
  tail.writeUInt16LE(current, 0);
  return Buffer.concat([header, ...parts, tail]);
}

// --------------------------------------------------------------- forge ---

if (process.argv[2] === 'forge') {
  const [accountPath, fixturePath] = process.argv.slice(3);
  const victim = newKey();
  const msg = heartbeat(42, 1);
  const bogus = keystoreSign(newKey(), msg).raw; // well-formed, but not by the victim
  const fakeAddr = Keypair.generate().publicKey;
  const data = forgeSysvar(
    [
      { programId: SECP256R1, data: precompileData([{ sig: bogus, pk: victim.compressed, msg }]) },
      { programId: PublicKey.default, data: Buffer.alloc(0) },
    ],
    1,
  );
  fs.writeFileSync(accountPath, JSON.stringify({
    pubkey: fakeAddr.toBase58(),
    account: {
      lamports: LAMPORTS_PER_SOL,
      data: [data.toString('base64'), 'base64'],
      owner: 'Sysvar1111111111111111111111111111111111111', // even claims the sysvar owner
      executable: false,
      rentEpoch: 0,
      space: data.length,
    },
  }));
  fs.writeFileSync(fixturePath, JSON.stringify({
    fakeSysvar: fakeAddr.toBase58(),
    victimPk: victim.compressed.toString('hex'),
    msg: msg.toString('hex'),
  }));
  console.log(fakeAddr.toBase58());
  process.exit(0);
}

// ----------------------------------------------------------------- run ---

const fixture = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));
const connection = new Connection(process.env.RPC_URL ?? 'http://127.0.0.1:18899', 'confirmed');
const programId = new PublicKey(process.env.PROGRAM_ID);
const payer = Keypair.generate();
await connection.confirmTransaction(await connection.requestAirdrop(payer.publicKey, 10 * LAMPORTS_PER_SOL));

/** Send (no preflight, so failures land on-chain) and return { err, meta }. */
async function send(ixs) {
  const tx = new Transaction().add(...ixs);
  tx.feePayer = payer.publicKey;
  const { blockhash, lastValidBlockHeight } = await connection.getLatestBlockhash();
  tx.recentBlockhash = blockhash;
  tx.sign(payer);
  const sig = await connection.sendRawTransaction(tx.serialize(), { skipPreflight: true });
  await connection.confirmTransaction({ signature: sig, blockhash, lastValidBlockHeight }, 'confirmed');
  const got = await connection.getTransaction(sig, { commitment: 'confirmed', maxSupportedTransactionVersion: 0 });
  return { sig, err: got.meta.err, meta: got.meta, size: tx.serialize().length };
}

const results = [];
function expect(name, { err, meta }, want) {
  const ok = JSON.stringify(err) === JSON.stringify(want);
  results.push(ok);
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}: err=${JSON.stringify(err)}` +
    (err === null ? `  CU=${meta.computeUnitsConsumed} fee=${meta.fee}` : ''));
}
const custom = (ix, code) => ({ InstructionError: [ix, { Custom: code }] });

const key = newKey();
const msg = heartbeat(42, 1);

// 1. Positive, and specifically one whose Keystore-style signature was high-S.
let s1;
do { s1 = keystoreSign(key, msg); } while (!s1.wasHigh);
expect('positive (normalized from high-S DER)',
  await send([precompileIx(precompileData([{ sig: s1.raw, pk: key.compressed, msg }])),
    verifyIx(programId, 0, 0, key.compressed, msg)]), null);

// 2. High-S twin rejected by the precompile.
const high = Buffer.concat([to32(s1.r), to32(N - s1.s)]);
expect('high-S rejected by precompile',
  await send([precompileIx(precompileData([{ sig: high, pk: key.compressed, msg }])),
    verifyIx(programId, 0, 0, key.compressed, msg)]), custom(0, PRECOMPILE_INVALID_SIGNATURE));

// 3. Wrong message.
expect('wrong message',
  await send([precompileIx(precompileData([{ sig: s1.raw, pk: key.compressed, msg }])),
    verifyIx(programId, 0, 0, key.compressed, heartbeat(42, 2))]), custom(1, ERR.MessageMismatch));

// 4. Wrong pubkey.
const other = newKey();
expect('wrong pubkey',
  await send([precompileIx(precompileData([{ sig: s1.raw, pk: key.compressed, msg }])),
    verifyIx(programId, 0, 0, other.compressed, msg)]), custom(1, ERR.PublicKeyMismatch));

// 5. Offsets pointing into another instruction (Wormhole-class substitution).
const attacker = newKey();
const aMsg = heartbeat(42, 9);
const carrier = precompileData([{ sig: keystoreSign(attacker, aMsg).raw, pk: attacker.compressed, msg: aMsg }]);
const forged = Buffer.from(carrier);
key.compressed.copy(forged, 16);
forged.fill(0x42, 49, 113);
msg.copy(forged, 113);
for (const at of [4, 8, 14]) forged.writeUInt16LE(0, at);
expect('offsets into another instruction',
  await send([precompileIx(carrier), precompileIx(forged), verifyIx(programId, 1, 0, key.compressed, msg)]),
  custom(2, ERR.ForeignInstructionIndex));

// 6. Spoofed instructions sysvar (preloaded with --account).
expect('spoofed instructions sysvar',
  await send([verifyIx(programId, 0, 0, Buffer.from(fixture.victimPk, 'hex'), Buffer.from(fixture.msg, 'hex'),
    new PublicKey(fixture.fakeSysvar))]), custom(0, ERR.InvalidInstructionsSysvar));

// 7. Precompile missing.
expect('precompile missing',
  await send([verifyIx(programId, 0, 0, key.compressed, msg)]), custom(0, ERR.NotSecp256r1Instruction));

// 8. Replay round 42's signature over round 43's message.
expect('replayed signature, different message',
  await send([precompileIx(precompileData([{ sig: s1.raw, pk: key.compressed, msg: heartbeat(43, 2) }])),
    verifyIx(programId, 0, 0, key.compressed, heartbeat(43, 2))]), custom(0, PRECOMPILE_INVALID_SIGNATURE));

// 9. The legacy-tx maximum measured in LiteSVM: 7 signatures over 32-byte
//    messages (SHA-256 digests of the heartbeat) in one precompile instruction.
const seven = Array.from({ length: 7 }, (_, i) => {
  const k = newKey();
  const m = crypto.createHash('sha256').update(heartbeat(42, i)).digest();
  return { sig: keystoreSign(k, m).raw, pk: k.compressed, msg: m };
});
const r9 = await send([precompileIx(precompileData(seven))]);
expect(`7 P-256 sigs, 32-byte msgs, legacy tx of ${r9.size} bytes`, r9, null);

const failed = results.filter((ok) => !ok).length;
console.log(`\n${results.length - failed}/${results.length} validator checks passed`);
process.exit(failed ? 1 : 0);
