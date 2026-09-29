# CHAMPION SPEC

> **Corrections after the day-1 spikes (2026-09-29).** Where this spec and
> [`programs/heads-down/INTERFACE.md`](../programs/heads-down/INTERFACE.md) disagree, **INTERFACE.md wins**.
> - **Price gate:** ORE stores `max_production_cost` but does **not** enforce it (`deploy.rs` checks only the
>   Motherlode bounds). The heads_down program enforces a **Motherlode-aware** gate on
>   `Board.production_cost_ema` and `Treasury.motherlode` ([docs/ORE.md](ORE.md)).
> - **Dig cadence:** the phone heartbeats every round off-chain. The program **digs in concentrated chunks
>   (≥ 0.001 SOL) on least-crowded split tiles only when the gate opens**, instead of deploying every round.
>   Fixed per-dig fees make per-round micro-deploys 36–90% more expensive than buying at nightly budgets.
>   The gated chunk design beats buying by about 1.4–5.5% ([ml/forecaster/RESULTS.md](../ml/forecaster/RESULTS.md)).
> - **Foreman Cost Forecaster:** demoted to **advisory**. It showed no skill over the live on-chain rule.
> - **Heartbeats:** the phone signs `SHA-256(preimage)` (32 bytes) so 7 heartbeats fit per v0 transaction.
>   On-chain verification happens only at dig time.
> - **Burying:** use ORE's permissionless `bury` instruction. The "grams" address `GHRBYPA4…` is an
>   ordinary wallet whose burning cannot be verified from ORE's source ([docs/ORE.md](ORE.md) §10).
> - **Proven on a mainnet fork:** a heads_down PDA executor deploys through ORE CPI, attackers can't, and the
>   PDA can pay ORE's checkpoint fee ([spikes/ore-executor](../spikes/ore-executor/README.md)).
> - **Device:** the build targets any Android. It is developed and filmed on a Redmi 14C (no gyroscope, virtual
>   proximity sensor), with a guest tier on any phone and an SGT-verified Seeker tier.

## PRODUCT_DEFINITION
Heads Down is a native Android (Kotlin) app for Solana Seeker. It gives the phone a job during the hours it would otherwise sit idle.

The core loop:
- Clock in by tapping a Quick Settings tile. After the first weekly refuel, you can instead just lay the phone face-down on its charger inside the window the on-device AI has planned.
- One Seed Vault double-tap funds a capped shift inside your own ORE Automation account.
- From then on your Seeker mines ORE on the live 5x5 board every ~78-second round, but only while it stays face-down, screen-off and unused.
- Every deploy must carry a fresh heartbeat signed by the phone's hardware-backed Keystore P-256 key, verified on-chain with the secp256r1 precompile. So no server, crank or team key can mine your SOL, and the funds never leave ORE's own Automation account.
- Pick the phone up and the rig cools within 10 seconds.
- At your alarm, a full-screen haul reveal replays the night's rounds, then offers one double-tap to clock out. You claim the ORE or keep it unrefined. If mining was the more expensive route that night, the same tap buys the rest at market, so every shift ends with more ORE.

Around the core:
- Rooms show who is 'dark' right now and hold a synchronized morning Muster.
- Stack is an SKR-bonded self-control contest, played at a table or in a regional room.
- Gift a Rig turns SKR sent to any .skr name into a live, SGT-locked rig.
- The on-device AI Foreman plans each shift, forecasts when mining beats buying, and tells a pickup from a table bump.

Primary users:
(1) The large majority of the ~121k SGT holders whose Seeker is a drawer or second phone, which gets a nightly job and a reason to stay charged.
(2) ORE-curious Seekers who want native mobile mining without running a bot or signing every round.
(3) Anyone who wants a nightstand or desk ritual. Heads Down honestly measures only the time the Seeker itself is set down.

What it is not: yield, a lottery, a 24/7 autominer, a custody product, or 'proof of focus' beyond Seeker screen-off time.

## POSITIONING_AND_NAME
NAME: Heads Down.
- Tagline: 'Your Seeker's night shift.'
- One-sentence story: 'Face-down, your Seeker mines ORE; pick it up and the rig goes cold, and nothing but your phone can switch it on.'
- Theme line: 'Clock in face-down.'
- ORE framing: 'Proof of Mobile, shipped.' This is the native Seeker ORE experience ORE advertised in 2025 ('mine ORE on Seeker, coming soon') and never shipped. The official ORE app has not been updated since Oct 2025.
- Category claim: the first phone-gated, trustless ORE rig. RefinORE, Orestack and Oreminer mine 24/7 from servers. Heads Down mines only while a verified Seeker is set down, and the phone's own hardware key gates it on-chain.
- Versus Forest, DeepWork, Sleepagotchi and UpRock: those pay out in points or emissions, or just count idle time. Heads Down turns idle time into hard-money accumulation with no emissions.

HONESTY RULES (UI, deck and transcript):
- Never say earn, yield, stake, passive income or 'proof of focus'.
- Do say dig, haul, bond, and 'accumulate ORE by the cheaper route'.
- Always show the EV meter and the effective price paid per ORE against market.
- Frame it as 'give your idle Seeker a job', not 'cure doomscrolling', because the Seeker is often a second phone.
- Do not use Solana Mobile's 'Less scrolling. More seeking.' as the founding line; it referred to dApp exploration. A wink at most.

VOICE: warm, dry and Lagos-honest ('my phone dey mine while I sleep').

VISUAL IDENTITY:
- Night-shift palette: charcoal, ember-orange rig heat, ORE gold for hauls.
- AOD-friendly glyphs and pixel accents as a nod to Radiants ('Do not forsake the pretty things').
- ORE is the primary featured brand on the rig, the reveal and share cards.

NAME CHECK before launch: search the dApp Store catalog (SeekerTracker API), GitHub and X. Fallback names: 'Face Down', 'Dark Shift', 'Nightstand'.

## CORE_LOOP
NIGHT (clock in, about 5 seconds)
- Trigger: bedtime. The widget shows 'rig cold', and a Foreman nudge arrives at your usual bedtime ('Tonight 23:10-06:50; cheaper to mine than buy until ~04:00').
- Action: swipe down and tap the 'Heads Down' tile next to Do Not Disturb. A non-exported translucent trampoline opens the Seed Vault sheet for one double-tap plus fingerprint on ONE transaction.
  - That transaction does the weekly refuel or per-shift top-up of your ORE Automation, sets ORE-native conditions, arms the Rig, and optionally adds an SKR Focus Bond or an SKR-to-SOL fuel swap.
  - Then lay the phone face-down on the charger.
- After the first weekly refuel, laying the phone face-down inside a high-confidence Foreman window arms the shift with the P-256 key alone. That is zero taps, within the weekly caps your wallet signed.
- While running:
  - A Live Update / AOD chip shows 'Heads Down 1:12 · 55 rounds · rig hot'.
  - The foreground service signs a heartbeat for each ORE round.
  - The permissionless crank digs only for rigs with a fresh heartbeat.
  - There is exactly one 'arm' thunk and no per-round buzzing at night.
- Breaks:
  - Screen-on, unlock, or a classifier-confirmed pickup sends a signed BREAK. The chip shows 'Rig cooling 10…' and then 'cold'.
  - Put the phone back down and it re-arms after a 10-second settle.
  - Losing the network means no heartbeat, so no mining and no loss.

MORNING (clock out, about 10 seconds)
- The exact alarm aligns to your system alarm and opens a full-screen haul reveal:
  - rounds replayed on the board in 3 seconds;
  - tile hits, solo tiles and Motherlode near-misses;
  - dark hours and the time of your first pickup;
  - effective price per ORE against market.
- One double-tap clocks out:
  - claim the ORE, or keep it unrefined to earn a share of the 10% refining fee;
  - an optional 'buy the rest at market' leg via Jupiter if mining was the more expensive route;
  - the ShiftLog is sealed and the streak goes up by 1.
- Then Muster: each room's synchronized reveal (who stayed dark, the room's haul) and a spoiler-free share grid.

DAY (optional): the QS tile or an App Shortcut starts a 25/50/90-minute Day Shift desk rig with optional 'feel the rig' per-round haptics. It uses the same trustless gate.

WEEKLY:
- the refuel double-tap;
- Stack nights;
- a Sunday rhythm report plus a Foreman accuracy card ('predicted $78/ORE, realized $74');
- Gift a Rig to wake a friend's or parent's drawer Seeker.

HOOK MODEL:
- Trigger: bedtime, the charger and the alarm, which are existing daily habits needing no content pipeline, plus room pushes.
- Action: one double-tap or zero taps.
- Variable reward: the haul, near-misses, Motherlode odds, room outcomes and Stack results.
- Investment:
  - a forgiving streak with 2 automatic freezes a month;
  - an SGT-keyed dark-hours ledger;
  - unrefined ORE earning a refining share;
  - rig cosmetics that level with dark hours, never with spend;
  - room seats and a Stack record.

WITHOUT AN AIRDROP OR IN A BAD-EV WEEK: focus-only shifts (zero SOL) still count for streaks, rooms, Stack and the ledger. 'Buy mode' keeps the morning ritual going as an ORE accumulation habit.

## FEATURE_SET
- RIG / trustless phone gate. Your ORE Automation's executor is the Heads Down Executor PDA. A permissionless crank can dig for a rig only when that rig's Keystore P-256 heartbeat for the current ORE round verifies on-chain (secp256r1 precompile, checked instructions sysvar, offsets confined to the same instruction). The program, not the cranker, computes the amount and the tiles.
- RIG / custody stays in ORE. The authority is your Seed Vault wallet. ORE enforces deploy-or-return. You can withdraw or close at any time. There is no Heads Down vault for mining funds.
- RIG / shift modes. Night Shift (charger, planned window, exact-alarm clock-out). Day Shift (25/50/90-minute desk rig). Motherlode Hunter preset (ORE-native min_motherlode). Focus-only (zero SOL, which still counts for streaks, rooms and Stack).
- RIG / mine-or-buy engine (from STASH). The heads_down program enforces a Motherlode-aware production-cost gate (ORE does not enforce `max_production_cost`). At clock-out, a 'buy the rest at market' leg runs via Jupiter when mining was pricier. A weekly scoreboard compares effective price per ORE with market. Buy mode is available for bad-EV stretches.
- RIG / budgets. A weekly refuel cap, per-shift cap, per-round cap and expiry, all signed by the wallet. The P-256 key and the AI can only tighten them, never raise them. Hard ceilings are shown in the UI.
- RIG / crowd-aware placement computed on-chain. The program reads the live Round's per-tile totals and picks the least-crowded split tiles plus the plan's solo count, and deploys late in the round. The crank has no discretion.
- RIG / heartbeat leases. Solo shifts may sign short leases of 3 rounds or fewer to save battery. Stack requires a heartbeat every round. Offline means no mining.
- RIG / honest EV meter. Shows expected cost per dark hour, ORE production cost against price, ORE buried over 7 days, and a 'buying is cheaper right now' banner.
- RIG / claims and refining. Keep ORE unrefined to share the 10% refining fee. Partial claims. Optional stORE via ORE's stake program. Haul shown in local currency (NGN, KRW, BRL, INR, PHP, USD).
- RIG / instant Freeze and Revoke. Freeze stops all digs using the device key alone; unfreezing needs Seed Vault. Revoke closes the Automation or re-points its executor, from a long-press on the QS tile or from the lock screen via unlockAndRun and Seed Vault.
- RIG / circuit breaker. The program refuses to CPI when ORE account layouts or versions don't match pinned expectations, so funds simply stay in users' Automations. A nightly fork CI runs against live ORE.
- RITUAL / QS tile beside Do Not Disturb, flip-to-arm, Foreman auto-arm, and a non-exported trampoline into MWA.
- RITUAL / Live Update and AOD chip for the user-started shift ('rig hot', 'cooling 10…'), with an Android 15 ongoing-notification fallback. No price tickers or countdowns.
- RITUAL / exact-alarm full-screen haul reveal. A 120 Hz board replay, near-miss highlights, first-pickup time and dark hours. The alarm aligns to AlarmManager.getNextAlarmClock.
- RITUAL / haptic language. An arm thunk, a cooling tick, a reveal drumroll, and the Motherlode flourish saved for the morning. It never wakes you at 3 am. Optional per-round 'feel the rig' for Day Shift only.
- RITUAL / Glance widgets. Rig heat, overnight haul, streak, the room's dark-presence row, and a Chronometer ticking without updates. Refreshed via Helius webhooks and FCM.
- RITUAL / optional Do Not Disturb automation during shifts. App Shortcuts: 25-min shift, Night shift, Start a Stack, Gift a rig.
- RITUAL / forgiving streak with 2 automatic freezes a month. Rig cosmetics level with dark hours, never with spend. Sunday rhythm report.
- CREW / Rooms. Private rooms by invite, plus public regional rooms (Lagos, Abuja, Nairobi, Manila, Seoul, Sao Paulo, Global Desk) so no friends are needed. Seats are sybil-proof by SGT mint.
- CREW / dark presence, the inverse of social media's online dot. Strict rooms, where one break costs the room streak.
- CREW / Muster: a synchronized per-room morning reveal (who stayed dark, room haul, standout hits), verifiable from on-chain ShiftLogs, plus a spoiler-free share grid for X and Telegram.
- CREW / an automatic room feed generated from real on-chain outcomes. .skr names everywhere. dApp Store deep-link and QR invites. An opt-in, country-level Night Map.
- STAKES (SKR) / in-person Stack. Tables are found over Nearby, each seat posts an SKR bond, and one phone can relay heartbeats for the whole table. The classifier ignores table bumps. Settlement is permissionless and fail-closed.
- STAKES (SKR) / remote Stack in regional rooms, labelled 'honor-plus'. Requires an attested rig. Gaps beyond grace count as breaks. Bonds are capped. Pull claims and timeout refunds.
- STAKES (SKR) / Focus Bond. Lock SKR on a declared solo shift. An early break sends it to the Bury auction, never to the team.
- STAKES (SKR) / Gift a Rig (from STASH). SKR sent to any .skr name is swapped to SOL in the sender's transaction and escrowed against the recipient's SGT mint. The recipient claims it as a pre-funded rig already digging. 30-day refund. Available from ACTION_PROCESS_TEXT on any selected .skr name.
- STAKES (SKR) / SKR fuel. An atomic Jupiter SKR-to-SOL swap inside the refuel transaction.
- STAKES (SKR) / Bury auction. Forfeited SKR is sold in a no-oracle descending-price auction for ORE, and the ORE goes straight to ORE's bury address.
- FOREMAN (AI) / Shift Planner. An on-device rhythm model predicts idle windows, proposes and auto-arms shifts, and allocates the weekly budget across nights.
- FOREMAN (AI) / Cost Forecaster. An on-device model forecasts production cost against price over the shift window, sets the gate threshold and amount curve, recommends the buy leg, and reports forecast against realized each morning.
- FOREMAN (AI) / pickup-vs-bump classifier. An IMU model arbitrates motion events while the screen is off, which is what makes table Stack fair. Screen-on and unlock stay hard breaks. The demo shows a live bump-vs-pickup test.
- FOREMAN (AI) / template-authoritative plain-language explanations: why the rig stayed cold, what the executor can and cannot do, and the effective price paid. An optional on-device LLM may paraphrase but never sits on a decision path.
- TRUST / open-source crates: sgt-verify (in-program SGT verification plus a spoof suite, crediting ORE's claim_seeker) and p256-introspect (secp256r1 introspection, low-S and DER helpers, Android Keystore test vectors).
- TRUST / THREAT_MODEL.md with a worst case for every key. An immutable-v1 path. Fuzzing plus Kani proofs. Reproducible builds. The Radiants audit run early and clean. Key Attestation registrar.
- TRUST / privacy. Sensor and usage data never leave the device. No location on-chain. Room regions are coarse and opt-in. Optional secondary Seed Vault account as the rig authority. Clear disclosure that rig activity times are public, as for any ORE miner.
- PROOF / public dashboard: rigs, nightly active rigs, retention cohorts, dark hours, rounds dug, SOL deployed, ORE mined vs bought vs buried, Heads Down's share of ORE miners per round by hour, and Stack/Gift/Bury stats. CSV export for ORE milestones.
- PROOF / founder build-hours ledger ('my rig mined while I coded'). Quest-ready actions for Solana Mobile Season 3: a 25-min Day Shift, 7 Night Shifts, win a Stack, gift a rig.

## ANDROID_SURFACES
- Quick Settings tile 'Heads Down' beside Do Not Disturb. Uses TileService with the requestAddTileService prompt, and its subtitle shows state ('rig hot · 1:12'). startActivityAndCollapse opens a non-exported translucent trampoline into MWA. Long-press goes to Revoke via unlockAndRun.
- Flip-to-arm through a foreground service of type specialUse. It fuses the gravity/accelerometer (face-down), proximity, significant-motion wake-up sensor, ACTION_SCREEN_ON and USER_PRESENT broadcasts, and charging state, and runs an on-device LiteRT pickup-vs-bump classifier.
- Android 16 Live Update using NotificationCompat.ProgressStyle, setRequestPromotedOngoing and POST_PROMOTED_NOTIFICATIONS for the user-started shift: status-bar chip, lock screen and AOD ('Heads Down · 1:12 · 55 rounds', 'Rig cooling 10…'). Falls back gracefully to an Android 15 ongoing notification. No crypto tickers and no future countdowns, per Google's rules.
- Exact alarm (USE_EXACT_ALARM) plus a full-screen intent as the Night Shift clock-out that opens the haul reveal, aligned to the system's next alarm.
- Seed Vault fingerprint plus side-button double-tap through MWA (clientlib-ktx 2.0.3) for refuel/clock-in, clock-out (claim and buy leg), bonds, gifts and revoke. That is two value-bearing signatures per day.
- Android Keystore P-256 rig key (StrongBox where available, non-exportable) with Key Attestation. It signs heartbeats, plans, BREAK and FREEZE messages, which the secp256r1 precompile verifies on-chain.
- Jetpack Glance 1.2 widgets: rig heat, overnight haul and streak, plus a room 'dark' presence row. A RemoteViews Chronometer ticks the shift without updates. Refreshed by Helius webhooks via FCM data messages.
- Composed haptics with VibrationEffect.Composition, plus Android 16 envelope haptics where present: arm thunk, cooling tick, reveal drumroll, and the Motherlode flourish in the morning. An audible-thunk and AOD fallback covers Seeker's weak motor.
- Nearby Connections (BLE/Wi-Fi) for in-person Stack tables: discovery, signed co-presence, and one phone relaying the whole table's heartbeats.
- Optional Do Not Disturb automation during shifts via notification-policy access, runtime-gated.
- App Shortcuts: 25-min shift, Night shift, Start a Stack, Gift a rig.
- ACTION_PROCESS_TEXT 'Gift a rig' on any .skr name selected in Telegram, X or Chrome, plus a share-sheet target.
- 120 Hz Compose board replay and reveal animations. A spoiler-free haul grid via the share sheet. dApp Store deep links (solanadappstore://details?id=…) and QR codes for room and gift invites.
- SGT verified in-program as the device seat. .skr names shown instead of addresses everywhere.

## ONCHAIN_ARCHITECTURE
PROGRAM: heads_down
- One program written in Pinocchio, targeting under 3k lines of code, with a Shank IDL feeding Codama clients.
- External programs have pinned IDs and are owner-checked:
  - ORE oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv: Board BrcSxdp1nXFzou1YyDnQJcPNBNHgoypZmTsyKBSLLXzi, Treasury 45db2FSR4mcXdSVVZbKbwojU6uYDpMyhpEi7cC8nHaWG, Config 9c9X7aDRAF41faiDs94ELjT19UrGnn72wBW9hPsS4Awy. Round, Miner and Automation accounts are re-derived from their seeds.
  - Token-2022 (SGT reads) and SPL Token (SKR SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3, ORE mint oreoU2P8bN6jkk3jbaiVxYnG1dCXcYxwhwyK9jSybcp).
  - System, Secp256r1SigVerify1111111111111111111111111, the Ed25519 precompile, and the instructions sysvar Sysvar1nstructions1111111111111111111111111.
  - ORE's permissionless `bury` instruction (tag 24) for Bury-auction proceeds (not the unverifiable `GHRBYPA4…` wallet).

ACCOUNTS
- Config [b'config']:
  - registrar Ed25519 pubkey;
  - fixed crank fee per rig-round, sized to measured crank cost;
  - bury share in bps;
  - pinned ORE layout and version hashes;
  - a 72h timelocked pending-change slot.
  - It has no withdraw authority.
- Executor [b'executor']:
  - A data-less, System-owned PDA funded above rent-exempt. It is the executor on every user's ORE Automation.
  - Strategy is Discretionary with a fixed per-round fee sized to crank cost. Use DiscretionaryBps at 10 bps or less only if a fixed fee proves unusable.
  - It signs ORE deploy through invoke_signed and fronts ORE's CHECKPOINT_FEE by System transfer, which is why it must be data-less and System-owned.
  - It receives the automation fee and reimburses crankers a fixed amount per rig-round. Any surplus goes to the bury path.
- Rig [b'rig', sgt_mint] (zero-copy):
  - holder wallet, sgt_mint, P-256 pubkey (33-byte compressed);
  - attestation level (0 unattested / 1 TEE / 2 StrongBox) and registrar expiry;
  - Automation address;
  - wallet-signed caps: weekly budget, per-shift and per-round amounts, expiry;
  - P-256-signed plan, which may only tighten: gate threshold, tile policy, window, lease length;
  - state: Idle / Armed / Down / Cooling / Broken / Frozen;
  - shift_id, heartbeat counter, last_hb_round, gap counter;
  - amount spent this shift and this week;
  - lifetime dark rounds, streak, freezes, weekly ShiftLog Merkle root, version.
- ShiftLog [b'shift', sgt_mint, shift_id]:
  - start and end round, dark rounds, rounds dug, lamports deployed (counted by the program), break reason, mode.
  - Closable after 30 days because the weekly Merkle root is kept on the Rig. Rent is cheap after SIMD-0437.
- Room [b'room', room_id] and Seat [b'seat', room, sgt_mint]:
  - name hash, coarse region code, private or public, strict flag, muster time, host.
  - Seats are one per SGT mint.
- StackTable [b'stack', table_id] and StackSeat [b'stackseat', table, sgt_mint]:
  - Table: in-person or remote, SKR bond, window [start_round, end_round], grace gaps, forfeit split (default 80% to finishers and 20% to bury; if nobody finishes, 100% to bury), an SKR vault ATA owned by the table PDA, and a settled flag.
  - Seat: bond, finished or broken, claimed.
- FocusBond [b'bond', sgt_mint, shift_id]: an SKR vault.
- GiftEscrow [b'gift', recipient_sgt_mint, nonce]: lamports, sender, 30-day expiry.
- BuryVault [b'bury'] plus auction state: lots of forfeited SKR with a descending ORE price.

INSTRUCTIONS
- register_rig(p256_pubkey, optional registrar attestation)
  - The signer must be the current SGT holder. The in-program SGT check requires:
    - the token account is owned by the Token-2022 program, its owner equals the signer, and its amount is 1;
    - the mint is owned by Token-2022;
    - TokenGroupMember.group equals GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te;
    - the mint authority equals GT2zuHVaZQYZSyQMgJPLzvkmyztfyXg2NJunqFp4p3A4.
  - The check credits ORE's claim_seeker from Sep 2025.
  - An optional Ed25519 registrar attestation over (sgt_mint, pubkey, level, expiry) is verified by precompile introspection.
  - Init-only: the account can never be reinitialized.
- rebind_rig: after a permissioned in-Seed-Vault move of the SGT, the new holder takes over, the old holder loses control, and the Rig's state is preserved.
- rotate_key: signed by the holder.
- refuel(caps, expiry)
  - Signed by the holder.
  - The same transaction carries ORE automate: deposit, executor = the Executor PDA, Discretionary fixed fee equal to Config.executor_fee, per-tile amount, optional min_motherlode, and reload (ORE does not enforce max_production_cost; the program gates).
  - Optionally it also carries a Jupiter SKR/USDC-to-SOL swap.
- arm_shift(plan): P-256-signed with no wallet, or signed by the holder. It checks the plan against caps and expiry and opens a new shift_id.
- dig(rigs[], hb_ix_index[])
  - Permissionless.
  - Per rig, the checks are:
    - locate the Secp256r1SigVerify instruction through the instructions sysvar, with the sysvar address itself checked;
    - require every offset to point inside that same instruction (the Wormhole class);
    - require the pubkey to equal rig.p256;
    - require the message to equal H('HDv1' ‖ program_id ‖ rig ‖ ore_round_id ‖ counter ‖ state=DOWN ‖ shift_id ‖ lease_end), where ore_round_id equals the owner-checked Board.round_id and the counter is greater than rig.counter;
    - require the shift to be active and not broken or frozen, with budget remaining;
    - apply the program-level gate: the same on-chain production-cost value ORE uses for its condition must not exceed the plan threshold.
  - The program computes the amount and a least-crowded tile mask from the owner-checked Round.
  - It CPIs ORE deploy with the Executor PDA as signer, then reloads the ORE accounts after the CPI.
  - It updates counters and spend, and reimburses the cranker.
  - Duplicate rigs within one call are rejected.
  - In tx v1 a single dig carries roughly 6 to 10 rigs, so 1,000 rigs is about 100 to 170 transactions per round.
- heartbeat(rigs[]): permissionless relay of P-256 heartbeats for zero-budget and Stack participants. It updates last_hb_round and gaps and makes no CPI.
- break_shift and freeze_rig: P-256-signed. Unfreezing needs the holder.
- end_shift: writes the ShiftLog and updates streak and freezes.
- create_room, join_room, leave_room.
- Stack:
  - open_stack creates a table.
  - join_stack transfers the SKR bond and requires attestation level 1 or higher.
  - settle_stack is permissionless after end_round. A seat finishes if it is not broken, its last_hb_round is at or after end_round, and its gaps are within grace.
  - claim_stack is pull-based.
  - refund_stack is a timeout refund.
- Focus bonds: lock_focus_bond, release_focus_bond, and forfeit_focus_bond (permissionless once a break is proven on-chain).
- Gifts:
  - create_gift: the sender's transaction swaps SKR to SOL via Jupiter and deposits the lamports.
  - claim_gift: only the current holder of that SGT, verified in-program, can claim. The same transaction may include ORE automate and register_rig.
  - refund_gift: to the sender after expiry.
- bury_auction_buy: the buyer transfers ORE to the bury address at the current Dutch price and receives the SKR lot. No oracle is involved.

UPGRADES
- During beta, the upgrade authority is a Squads multisig behind a 72h timelock. Config changes are timelocked too.
- After audit the upgrade authority is revoked, making v1 immutable.
- Future ORE changes are handled by deploying v2 at a new address; users re-point their Automation executor with one double-tap.

INVARIANTS (fuzzed; Kani proofs where tractable)
(1) No instruction moves value out of a user's Automation except ORE's own deploy-or-return.
(2) dig succeeds only with a fresh, correctly bound P-256 signature for the current ORE round and a strictly increasing counter.
(3) Spend per round, per shift and per week stays within the wallet-signed caps; P-256 plans can only tighten.
(4) There is one Rig per sgt_mint, and it is always controlled by the current holder.
(5) Stack conservation: payouts plus bury equal total bonds, and each seat claims at most once.
(6) A gift pays out only to the current SGT holder, or to the sender after expiry.
(7) There is no admin withdraw path anywhere. Executor lamports flow only to ORE fees, crank reimbursement or bury.
(8) Every account has a discriminator, bumps are canonical, math is checked, accounts are never reinitialized, and closed accounts are zeroed.

WHY ON-CHAIN: custody stays in ORE, the phone gate has to be enforceable without trusting the team, seats have to be sybil-proof, and bonds between strangers have to settle without a referee.

## OFFCHAIN_SERVICES
(1) Crank (Rust with tokio, open source).
- Subscribes to Helius LaserStream gRPC on the ORE Board, Round and Treasury.
- Ingests heartbeats and builds tx v1 dig batches of 6-10 rigs.
- Times deploys late in the round and sends them through Helius Sender with a Jito tip.
- Idempotent per (rig, round). Monitors CU and error patterns, and auto-pauses on ORE layout changes.
- Anyone can run a competing crank; reimbursement from the Executor PDA makes it self-funding.

(2) Heartbeat intake.
- The phone's foreground service holds an authenticated WebSocket (SIWS session), and round-reset events are pushed down the same socket.
- Heartbeats are mirrored to a public Nostr relay so third-party crankers can dig. The team is a convenience, not a gate.

(3) Registrar.
- Verifies Android Key Attestation chains:
  - the RKP P-384 root;
  - TEE or StrongBox security level;
  - verifiedBootState VERIFIED and deviceLocked;
  - attestationApplicationId matching the Heads Down package and release-cert digest;
  - a challenge bound to the registration nonce.
- Returns an Ed25519 attestation, publishes attestation transcripts, and rotates its key only through a timelock.

(4) Auth. SIWS with server-issued, single-use nonces that expire in 10 minutes and are pinned to solana:mainnet. The program still enforces SGT uniqueness by mint.

(5) Kora fee relayer for sponsored heartbeat and zero-budget transactions, rate-limited to one per rig per round.

(6) Push and presence.
- Helius webhooks feed FCM data messages for the widget, Live Update and Muster.
- A rooms presence service derives who is dark from the heartbeat stream. It only ever sees coarse state, never sensor data.
- The Muster scheduler runs per room time zone.

(7) Indexer and public dashboard.
- Helius Parsed Events plus program-account snapshots go into Postgres, which serves a public API and dashboard.
- Metrics: rigs, nightly active rigs, D1/D7/D14 cohorts, dark hours, rounds dug, SOL deployed, ORE mined vs bought vs buried, Heads Down's share of ORE miners per round by hour, and Stack, Gift and Bury stats.
- CSV export for ORE milestone reports.

(8) Quotes and models.
- A Jupiter quote proxy for the buy leg.
- Foreman models ship as signed files inside app releases; no remote code.
- Training notebooks and model cards are open, and api.ore.com history snapshots are archived for backtests.

(9) Ops.
- Railway or Fly deployment. Secrets live only in environment variables; the Helius key sits behind a proxy and never goes in the APK.
- A public liveness page and alerting.

## AI_FEATURE
FOREMAN: an on-device AI crew boss that plans, prices and polices every shift, bounded by the limits you signed. All three models run on LiteRT on the Dimensity 7300 CPU. No sensor or usage data leaves the phone.

(1) SHIFT PLANNER (rhythm model)
- Inputs: the app's own foreground-service logs. That means screen on/off, charging sessions, face-down intervals, alarm times and past shift outcomes. It does not need UsageStats.
- Model: a small sequence model (a GRU, or gradient-boosted trees exported to TFLite). It predicts P(idle) for each 15-minute slot over the next 24 hours.
- Outputs:
  - tonight's proposed window;
  - zero-tap auto-arm when you lay the phone face-down on the charger inside a high-confidence window;
  - a spread of the weekly budget across nights to hit your weekly ORE goal.
- It powers the Sunday rhythm report and the 'first pickup after bedtime' trend.

(2) COST FORECASTER (mine-or-buy)
- Trained offline in an open notebook on api.ore.com history: production-cost EMA, price, miners and SOL per round, Motherlode size and hour of day.
- On device it forecasts production cost against market over the shift window. It then sets the plan's program-level gate threshold, the per-round amount curve and the solo/split mix, and recommends the morning buy leg.
- Accountability: every morning it shows its forecast against the realized price ('Foreman said $78/ORE; you paid $74').
- A published backtest must beat both 'always buy' and 'always mine'. If it doesn't, it gets demoted to advisory.

(3) PICKUP/BUMP CLASSIFIER
- An IMU window model over accelerometer, gyroscope and proximity. It arbitrates motion events while the screen is off, so nightstand and table bumps don't count as breaks. That is essential for fair in-person Stack.
- Screen-on and unlock stay hard breaks.
- The policy is fail-closed and documented, with a target of at least 99% pickup recall. A model card is published.
- The demo shows a live bump-vs-pickup test.

BOUNDS
- The Foreman can only tighten caps signed by Seed Vault. It can never raise a budget, move funds, or sign anything other than P-256 plans and heartbeats.
- ORE stores the Automation's max_production_cost condition but does not enforce it: deploy.rs checks only min/max_motherlode (ORE commit b92c504). The Heads Down program enforces the production-cost gate itself in dig, against Board.production_cost_ema (lamports per whole ORE, a 20-round EMA updated in ORE's reset.rs), using the tighter of the wallet-signed ceiling and the plan threshold. See docs/ORE.md.

EXPLAINER
- Deterministic, template-authoritative cards explain why the rig stayed cold at 3 am, what the executor can and cannot do, and the effective price paid.
- An optional on-device LLM may paraphrase them; Gemma 3 1B is English-only. It never sits on a decision path, and it gets cut if it adds nothing.

NARRATION for the deck and transcript: 'AI: three on-device models plan the shift, forecast when mining beats buying, and tell pickups from bumps; they can only tighten your signed limits; no sensor data leaves the phone.'

Do not claim sleep tracking or health outcomes. Heads Down measures only Seeker idle time.

## SKR_INTEGRATION
SKR is the night shift's collateral and gifting currency. It is never staked, never an emission reward and never a checkout token. The word 'stake' appears nowhere.

(1) STACK: the SKR-bonded self-control contest. This is the hardware-verified contest pattern that won MONOLITH's SKR prize for Seek.
- In-person tables are discovered over Nearby, and any connected phone can relay the table's heartbeats.
- Remote tables run in regional rooms and are labelled 'honor-plus'.
- Settlement is permissionless, from on-chain secp256r1-verified heartbeats, and fail-closed: a gap beyond grace or a signed BREAK loses.
- Forfeits go 80% pro rata to finishers and 20% to the Bury auction. Claims are pull-based, with timeout refunds.
- Joining requires an attested rig, and bonds are capped.

(2) FOCUS BOND: a solo commitment. You lock SKR on a declared shift, and an early break sends it to the Bury auction, never to the team.

(3) GIFT A RIG
- Send SKR to any .skr name. It is swapped to SOL inside your transaction and escrowed against the recipient's SGT mint, so only a real Seeker can claim it.
- It arrives as a pre-funded Night Shift rig that is already digging. Unclaimed gifts refund after 30 days.
- This is the viral loop that wakes drawer Seekers.

(4) SKR FUEL: pay for a refuel in SKR through an atomic Jupiter swap inside the clock-in transaction. This is a convenience, not the novelty.

(5) BURY AUCTION: forfeited SKR is sold in a no-oracle, descending-price auction for ORE, and that ORE goes straight to ORE's bury address. Your flinch makes ORE scarcer, which ties together both sponsors' tokens.

METRICS to show: SKR bonded, forfeited and buried as ORE; Stack tables settled; gifts sent and claimed.

SUBMISSION skrIntegration FIELD (draft): 'SKR is Heads Down's commitment collateral and gifting currency. Seekers bond SKR in Stack contests, at the table or in regional rooms, and in solo Focus Bonds. Both settle permissionlessly on-chain from each phone's hardware-key heartbeats. Forfeits pay the people who held out; the rest is auctioned for ORE that is burned. Gift a Rig turns SKR sent to any .skr name into a live, SGT-locked ORE rig that only a real Seeker can claim. No staking, no emissions, no leaderboard payouts.'

## ORE_INTEGRATION
ORE is the product, not a payout token.

ORE TOUCHPOINTS
- One ORE Automation per user:
  - the authority is the user's Seed Vault wallet (or an optional secondary Seed Vault account);
  - the executor is the Heads Down Executor PDA;
  - the strategy is Discretionary with a fixed crank-cost fee.
- Native conditions and settings: min_motherlode for the Hunter preset, per-tile amount and reload, all under deploy-or-return custody. The cost gate is enforced by heads_down, not ORE.
- deploy by CPI from dig, plus checkpoint.
- Partial claims, and the refining-fee share for unrefined ORE, shown in the UI.
- Optional stORE through ORE's stake and LST programs.
- ORE's `bury` instruction for Bury-auction proceeds.
- Live Board, Round and Treasury data through Helius LaserStream.
- api.ore.com: /stats/history for the EV meter and Forecaster, /events/motherlode for near-miss replays, /users for display names.

WHAT ORE GETS
- The first native Seeker ORE client since ORE's own app went stale in Oct 2025, with SGT-verified rigs. That revives the idea behind ORE's removed claim_seeker instruction.
- New miners per round. ORE sees about 150-165 today, so a few hundred nightly rigs is visible on ORE's own board.
- Buy pressure from the morning buy leg.
- Burned ORE from SKR forfeits.
- ORE branding on the rig, the reveal, share cards, the deck and every launch post.

ORE stays the primary featured product. SKR and SOL only fund or collateralize ORE activity, and no competing mining products are supported.

MILESTONE PROPOSAL (to agree with ORE after the hackathon, reported monthly from the public dashboard):
- M1: live on the dApp Store with the trustless executor on mainnet, plus 250 SGT-verified rigs.
- M2: 1,000 rigs and 300 nightly active rigs, with Heads Down at 25% or more of ORE miners per round during 00:00-06:00 local in two regions.
- M3: immutable v1, a third-party crank running, and cumulative ORE mined, bought and buried published.

The match goes to the single strongest ORE integration in the top 10. The pitch to ORE is therefore the trustless phone gate plus verifiable usage, not a skin over an autominer.

## SECURITY_AND_THREAT_MODEL
PRINCIPLE: every key has a bounded worst case, shown on screen and written in THREAT_MODEL.md. Custody reuses ORE Automation instead of rebuilding it.

KEYS
- User Seed Vault key
  - Signs refuel/arm, clock-out, bonds, gifts and revoke through standard MWA.
  - Transactions are simulated and shown before signing.
  - Success is shown only after confirmation with err==null, with blockheight expiry and timeouts handled.
- Rig P-256 Keystore key
  - Non-exportable, StrongBox where available, attested at registration.
  - Signs plans, heartbeats, BREAK and FREEZE. It cannot move funds.
  - Worst case if the app or device is compromised: your armed weekly budget is deployed into ORE only when the program's gate is at or below the cost ceiling you capped (it buys you ORE at a price you capped), plus the outcomes of Stack tables you joined.
  - Mitigations: instant Freeze with the device key, and Revoke through Seed Vault.
- Crank and relayer (anyone)
  - Liveness only. They cannot deploy without a fresh heartbeat, cannot pick amounts or tiles, and cannot touch bonds.
  - If every crank dies, nothing mines and nobody loses anything.
- Registrar Ed25519 key
  - Vouches that a key is hardware-backed.
  - If compromised, software keys can be registered and capped Stack tables gamed. It has no custody.
  - Key rotation is timelocked and transcripts are published.
- Upgrade authority: Squads multisig plus a 72h timelock during beta, then revoked. After that, ORE changes are handled by a v2 deploy and user re-pointing, not by upgrades.
- Team servers (push, presence, dashboard): no signing authority over funds.
- ORE upstream: program ID pinned; every ORE account owner-checked and re-derived; account layouts version-checked behind a circuit breaker; IDL pinned; nightly CI fork tests against live ORE (about 15 ORE program commits in Sept 2026).

ON-CHAIN CHECKS, mapped to the Radiants audit taxonomy
- Signer and owner checks, plus PDA re-derivation on every account, including foreign ORE, Token-2022 and SGT accounts.
- Discriminators against type cosplay.
- Pinned program IDs for every CPI (arbitrary-CPI).
- Duplicate-account rejection in the batched dig.
- Canonical bumps, and separate seeds per purpose so no PDA is shared.
- No reinitialization, and closes that zero data and wipe the discriminator.
- Checked math throughout.
- Relayer-paid rent only for SGT-gated accounts, with per-rig rate limits (rent DoS).
- No external price oracle on any value path: the bury path uses a Dutch auction, and the buy leg is a user-signed Jupiter swap with slippage protection.
- Accounts reloaded after every CPI (stale data).
- The Executor PDA signer is passed only to ORE deploy (signer passthrough).
- The instructions-sysvar address is checked, and secp256r1 offsets must point into the same instruction (the Wormhole class).
- Low-S normalization and DER-to-raw conversion on the client; high-S signatures are rejected by tests.
- Bounded Token-2022 extension parsing for the SGT. SKR is classic SPL.

TESTS
- LiteSVM and Mollusk unit tests.
- Surfpool mainnet-fork tests against the live ORE binary.
- Trident fuzzing of dig, settle and bury.
- Kani proofs for cap arithmetic, Stack conservation and gap logic.
- SGT spoof suite: wrong group, wrong authority, zero balance, moved SGT, fake Token-2022 mint.
- P-256 suite: replay, wrong round, stale counter, high-S, offsets pointing into another instruction.
- Reproducible verifiable builds.
- The Radiants audit, run in week 1 and again before submission.

ANDROID
- Permissions kept minimal and justified: FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS, POST_PROMOTED_NOTIFICATIONS, USE_EXACT_ALARM, USE_FULL_SCREEN_INTENT. Notification-policy access and the Nearby/Bluetooth permissions are optional and runtime-gated. No UsageStats and no location.
- Trampoline and tile activities are not exported and trust no intent extras.
- Room and gift deep links are parsed strictly and never auto-sign.
- Nearby payloads are signed and size-bounded.
- FCM and widget paths never sign without a foreground Seed Vault confirmation.
- MWA auth tokens are encrypted with the Keystore.
- SIWS nonces are single-use.
- A lint rule blocks android.util.Log in release builds.
- No secrets in the APK.
- The app passes the AlignAI MWA fixtures: setup-connect, account-network-session, signing-completion, rejection-recovery and platform-configuration.

PRIVACY
- Only a state bit and counters go on-chain.
- Rig activity times are public, like any ORE miner's, and this is disclosed.
- No location on-chain; room regions are coarse and opt-in.
- An optional secondary Seed Vault account can be the rig authority, which unlinks the rig from primary holdings and keeps the primary wallet's history clean.

STACK HONESTY
- Remote Stack is labelled 'honor-plus', because a rooted device with a leaked keybox could spoof heartbeats.
- Bonds are capped, the in-person mode is primary, and gaps beyond grace count as breaks.

ECONOMIC HONESTY
- No percentage fee on mining, only a fixed crank-cost fee.
- Revenue comes from a Jupiter referral fee on the user-signed buy leg.
- The EV meter and effective price are always visible.

## TECH_STACK_AND_REPO_LAYOUT
MOBILE
- Native Kotlin with Jetpack Compose (Material 3, 120 Hz animations). minSdk 31, targetSdk 36. This is the same path SeekerClaw took to win MONOLITH.
- MWA via mobile-wallet-adapter-clientlib-ktx 2.0.3, with web3-solana and rpc-core. Seeker Connect for Kotlin once it ships.
- Glance 1.2 widgets.
- androidx.core 1.19: NotificationCompat.ProgressStyle and setRequestPromotedOngoing, with an Android 15 fallback.
- System APIs: TileService; a foreground service of type specialUse; SensorManager (gravity, proximity, significant motion); VibrationEffect.Composition; AlarmManager exact alarms plus full-screen intents; Android Keystore P-256 with StrongBox where present and Key Attestation; Nearby Connections.
- LiteRT (TFLite) for the three models, and optionally LiteRT-LM.
- Hilt, Room, DataStore, and OkHttp/Ktor WebSocket.

PROGRAM
- Rust with Pinocchio. Shank IDL feeding Codama clients: Rust for the crank and tests; Kotlin instruction builders generated where possible, otherwise hand-written with golden-byte tests.
- The ore-api crate for ORE account layouts.

OFF-CHAIN
- Rust (tokio, LaserStream gRPC, Helius Sender/Jito) for the crank and registrar.
- TypeScript or Rust for push, presence and the indexer.
- Postgres, a Next.js dashboard, and a Kora relayer.
- Hosted on Railway or Fly.

TESTS AND CI
- Program: LiteSVM, Mollusk, Surfpool, Trident, Kani, proptest.
- Android: JUnit, Robolectric, Compose UI tests, and instrumented tests on a real Seeker.
- GitHub Actions: verifiable program build, nightly fork tests against live ORE, a reproducible APK build, and Radiants audit prep.

REPO: a public monorepo from day one with granular commits.
- heads-down/programs/heads-down: Pinocchio program (state, instructions, checks.rs, ore_cpi.rs, errors.rs)
- crates/sgt-verify: open-source SGT verifier and spoof suite
- crates/p256-introspect: secp256r1 introspection and low-S/DER helpers, with Keystore test vectors
- crates/hd-client: Codama-generated Rust client
- crank: LaserStream ingest, heartbeat intake, tx v1 batcher, circuit breaker
- registrar: Key Attestation verifier and SIWS nonce service
- services/push-presence: Helius webhooks to FCM, room presence, Muster scheduler
- services/indexer: Parsed Events and account snapshots to Postgres and the public API
- dashboard: public traction site
- android:
  - app: navigation and DI
  - core/wallet: MWA, SIWS, auth-token vault, trampoline
  - core/chain: transaction builders, RPC, Kotlin bindings
  - core/keys: Keystore P-256, attestation, heartbeat signer
  - feature/shift: FGS state machine, sensor fusion, leases, DND
  - feature/reveal: exact alarm, full-screen haul, clock-out
  - feature/rooms, feature/stack, feature/gift
  - ml: LiteRT models and feature extractors
  - surface/tile, surface/widget, surface/liveupdate, surface/haptics
- ml: notebooks for the rhythm model, forecaster and classifier, with exported .tflite files and model cards
- tests: fork, fuzz and kani
- docs: THREAT_MODEL.md, ECONOMICS.md, ORE.md, SKR.md, PRIVACY.md, DEMO.md
- README: install the APK, run a crank locally, verify the build

## DEMO_VIDEO_SCRIPT
Three minutes, filmed on real Seekers, with uncut signing.

0:00-0:08 COLD OPEN (macro, 23:04, no music)
- A Seeker on a wooden nightstand beside a glass of water.
- A thumb swipes down, taps the 'Heads Down' tile next to Do Not Disturb, then side-button double-tap and fingerprint on the Seed Vault sheet.
- The phone goes face-down onto its charger. One 'arm' thunk ripples the water, and the AOD chip reads 'Heads Down · rig hot'.
- VO: 'Most Seekers sleep in a drawer. Mine works the night shift.'

0:08-0:22 HOOK
- Title cards over the face-down phone:
  - 'About 121k Seekers. Most sit idle (SeekerTracker)'
  - 'ORE, 2025: Proof of mobile, coming soon'
  - 'Autominers don't care if you're asleep'
- VO: 'Heads Down: face-down, my Seeker mines ORE. Pick it up and the rig goes cold. And nothing but this phone can switch it on. Not a server, not us.'

0:22-0:48 CLOCK-IN (on-device capture at 1x)
- The Foreman plan card: 'Tonight 23:10-06:50 · cap 0.04 SOL · mine only while ORE production cost < $80 · Foreman: mining beats buying until ~04:00 · 5 least-crowded split + 2 solo tiles'.
- Double-tap, then Solscan showing one transaction containing ORE automate and Heads Down arm.
- VO (plain, criterion-level): 'One signature funds tonight's shift inside ORE's own Automation account. On-device AI planned it. It learned when this phone sits idle and forecast when mining ORE is cheaper than buying. It can tighten the limits I signed, never raise them. No sensor data leaves the phone.'

0:48-1:12 TRUSTLESS BEAT (split screen: phone on the left, terminal and Solscan on the right)
- Lift the phone. The Live Update shows 'Rig cooling · 10…9', then 'cold'.
- The crank tries to dig for this rig, and Solscan shows it failed with 'HeadsDown: StaleHeartbeat'.
- Put the phone back down. At the next round Solscan shows a success, with a Secp256r1SigVerify instruction and a CPI into ORE deploy.
- VO: 'Every dig must carry a fresh signature from this phone's hardware key, checked on-chain by Solana's secp256r1 precompile. No heartbeat, no deploy. Worst case if my phone is compromised: my capped budget buys ORE below the price I capped.'

1:12-1:40 MORNING HAUL (real footage from the build month)
- The 07:00 alarm opens the full-screen reveal. 312 rounds replay on the 5x5 board in 3 seconds, and a solo tile flares at 03:14: 'missed the Motherlode by one tile'.
- Card: 'Mined 0.031 ORE at $71 effective vs $86 market. Mining was pricier 04:10-06:50: buy the remaining 0.012 ORE?' Double-tap to clock out. Streak 23.
- Muster card: 'Lagos Night Shift: 41 of 47 stayed dark.' Then the share grid.
- VO: 'When mining costs more than buying, Heads Down buys. Every shift ends with more ORE, by the cheaper route. It isn't yield. It's accumulation, and I see what I paid.'

1:40-2:05 STACK (a real dinner table, four Seekers)
- The phones pair over Nearby, and each posts a 200 SKR bond with a double-tap.
- A hand bumps the table and all four chips stay hot, labelled 'bump, not a pickup'.
- A hand reaches for one phone and it buzzes red.
- The Solscan settle shows the bonds split to the three who held out, with 20% auctioned for ORE that is burned.
- VO: 'SKR is the collateral for self-control. It's a bond, not staking. It settles on-chain from the phones' own heartbeats, and forfeits end up burning ORE.'

2:05-2:18 GIFT A RIG
- Select 'mum.skr' in Telegram, choose 'Gift a rig', send 500 SKR with a double-tap.
- Cut to a drawer Seeker: 'You've been gifted a rig.' She claims it, and the rig is digging.
- VO: 'SKR sent to any .skr name arrives as a live, SGT-locked ORE rig. Only a real Seeker can claim it.'

2:18-2:45 PROOF (the public dashboard, scrolled live)
- Stats shown: SGT-verified rigs, nightly active rigs, D7 retention, dark hours, rounds dug, ORE mined vs bought vs buried, and Heads Down's share of ORE miners per round at night.
- The founder's ShiftLog: '200+ build hours logged, the rig mined while I coded'.
- Repo badges: THREAT_MODEL.md, immutable program, open sgt-verify and p256 crates, fuzzing and Kani, Radiants audit clean.
- VO reads the numbers plainly, then gives one sentence each on AI, SKR, ORE and security.

2:45-3:00 CLOSE
- The face-down Seeker in the dark with its AOD chip glowing.
- Title: 'Heads Down. Put it down. It digs.' Subtitle: 'Live on the Solana dApp Store · powered by ORE'.

PRODUCTION NOTES
- Never script a Motherlode.
- Test the ripple shot on day 3. If the motor is too weak, use the audible thunk plus the AOD chip.
- Narrate every claim factually for the AI transcript reader, with no text addressed to reviewers.
- Keep it to 3:00.

## PITCH_DECK_OUTLINE
15-17 slides. Every slide makes factual, criterion-tagged claims for the AI pre-screen, and nothing is addressed to reviewers.

(1) Title: 'Heads Down: your Seeker's night shift', powered by ORE. Image: the face-down Seeker with its AOD chip.
(2) The idle-Seeker problem: about 121k SGT devices; roughly 118.9k .skr IDs dormant on their primary wallets; about 9k DAU and falling; Seekers are often second phones that sit in drawers.
(3) ORE's unshipped promise: 'Proof of mobile, coming soon' in 2025; claim_seeker removed; the ORE app stale since Oct 2025; about 150-165 miners per round; the existing autominers run 24/7 from servers.
(4) The ritual: a loop diagram of bedtime clock-in (one double-tap), dark rounds, morning haul and clock-out (one double-tap), then Muster. Plus focus-only mode.
(5) Only on Seeker: Seed Vault double-tap, SGT seats, Keystore P-256 with secp256r1, sensors and classifier, QS tile, Live Update/AOD, Glance, haptics, Nearby.
(6) The trustless rig: an architecture diagram running Seed Vault -> ORE Automation (custody) <- Executor PDA <- permissionless dig <- phone heartbeat. Headline: 'No server, no team key can mine your SOL.' Include the StaleHeartbeat Solscan screenshot.
(7) Worst-case table for each key: user, rig key, crank, registrar, upgrade authority, servers, ORE upstream.
(8) Honest economics:
- chart of production cost against price;
- the mine-or-buy engine and effective price vs market;
- a fixed crank-cost fee, no percentage;
- revenue from the Jupiter referral fee on the user-signed buy leg;
- never 'yield'.
(9) AI, the Foreman: three on-device models, their bounds, a forecast-vs-realized accuracy chart, the classifier model card, and the privacy statement.
(10) SKR: Stack (the Seek pattern), Focus Bond, Gift a Rig, SKR fuel and the Bury auction. No staking. Live metrics.
(11) ORE: primary product, touchpoints, share of miners per round at night, ORE bought and buried, and the milestone proposal.
(12) Social: rooms, public regional rooms, dark presence, Muster and the share grid.
(13) Traction: dashboard numbers, D1/D7/D14 cohorts, streak distribution, testers by region, and the founder's build-hours ledger, all with explorer links.
(14) Security and open source: the crates, THREAT_MODEL, fuzzing and Kani, reproducible builds, the immutability plan, Radiants audit status and Android hardening.
(15) Landscape: a matrix of RefinORE, Orestack, Oreminer, the ORE app, Forest, DeepWork, Sleepagotchi and UpRock, scored on phone-gated, trustless executor, mine-or-buy, social and SGT.
(16) Ecosystem impact and roadmap: reactivating drawer Seekers, a Season 3 quest pack, the Spotlight 'Real-World Utility' theme, public-good crates, third-party crankers over Nostr, an immutable v1, and SMS-OEM devices as guest rigs without bonds.
(17) Founder: Lagos, solo with AI agents, the rig that mined while he coded, and why this founder.

Appendix: a criterion map with one factual line each for Stickiness/PMF, UX, UI, Innovation, Demo, AI, SKR Integration and Ecosystem Impact.

## TRACTION_AND_LAUNCH_PLAN
PHASE 0 (days 1-3)
- Make the repo public.
- Post a daily build log on X in a human voice (Beeman: no AI-written posts).
- The founder's own rig runs every night from night one: fork first, then mainnet with tiny caps. Post explorer links.

PHASE 1 (week 1)
- Closed alpha APK for 20 SGT-verified testers from the Lagos/Abuja Seeker Telegram, Radiants Discord, ORE Discord and Superteam Nigeria.
- Triage bugs every night and publish device findings (Doze, battery, haptics).

PHASE 2 (weeks 2-3)
- Open beta targeting 100-300 SGT-verified rigs across 3-5 seeded regional rooms (Lagos, Abuja, Nairobi, Manila, Seoul/Global).
- A weekly public Stack night.
- The first in-person Stack at a Seeker or Superteam meetup, filmed for the demo.
- A Gift a Rig push: 'gift your mum's drawer Seeker a rig'.

PHASE 3
- Submit to the dApp Store (3-5 day review) before judging opens on Oct 10. Winners must publish within 30 days anyway.
- Pitch Solana Mobile a Season 3 quest pack (intro: complete a 25-min Day Shift; deep: 7 Night Shifts, win a Stack, gift a rig) and a Spotlight 'Real-World Utility' slot.
- Agree a co-announcement and milestones with ORE (Hardhat Chad).
- Keep shipping and posting through judging (Oct 10-Nov 8). Technical review and interviews reward velocity.

METRICS for the deck and demo, all verifiable on the public dashboard with explorer links:
- SGT-verified rigs and nightly active rigs;
- D1/D7/D14 retention cohorts and streak distribution;
- dark hours and rounds dug;
- SOL deployed, and ORE mined vs bought vs buried;
- Heads Down's share of ORE miners per round by hour;
- Stack tables settled;
- SKR bonded, forfeited and buried;
- gifts claimed.

RULES
- No vanity counts.
- Never pay testers SKR or other emissions.
- No referral bounties, so bot-like farming never shows up in the numbers.

POST-HACKATHON
- Immutable v1 after audit.
- Third-party crank docs and Nostr-published heartbeats.
- Localization.
- Guest rigs without bonds for non-SGT SMS-OEM devices.
- Monthly ORE milestone reports.

## DERISKING_EXPERIMENTS
- Day 1: mainnet-fork spike on Surfpool against the live ORE binary. The user calls ORE automate with the executor set to a data-less, System-owned Executor PDA (Discretionary, fixed fee). Then heads_down::dig verifies a P-256 heartbeat and CPIs ORE deploy through invoke_signed. Confirm the CHECKPOINT_FEE flow, the automation-fee credit, reload, that native conditions (max_production_cost, min_motherlode) are enforced under Discretionary, whether checkpoint is permissionless, CPI depth, CU per rig, and the maximum rigs per tx v1. PASS: the trustless default ships. FAIL: fall back to a KMS executor with crank-enforced heartbeats and the same published bound (armed budget buys ORE at or below the capped production cost), and lead the pitch with the bound.
- Days 1-2: secp256r1 end to end from a real Seeker. Sign with a Keystore P-256 key (StrongBox if present) using SHA256withECDSA, convert DER to raw r‖s, normalize to low-S, compress the pubkey, then verify with the precompile instruction plus introspection (checked sysvar, same-instruction offsets). Prove that replay, wrong-round, stale-counter and high-S signatures are rejected. Measure signatures per transaction.
- Days 2-3: device truths on a real Seeker.
- Which MR9 build and Android 16 QPR level, and whether Live Update promotion works or needs the fallback.
- Whether a specialUse FGS survives 8h of Doze on the charger while holding a WebSocket.
- Heartbeat delivery rate on flaky Nigerian networks, and Day Shift battery drain off the charger.
- Vibration motor strength for the ripple shot.
- Fidelity of the face-down, proximity and significant-motion sensors.
- Exact alarm plus full-screen intent over the lock screen.
- Latency from QS tile to trampoline to Seed Vault.
- Contents of the Key Attestation chain and StrongBox presence.
- Google Play services for FCM and Nearby.
- Day 3: Seed Vault Wallet transaction limits. Check the clock-in size (ORE automate + Heads Down refuel/arm + optional Jupiter SKR-to-SOL swap), whether the production wallet supports tx v1 or needs v0 with a lookup table, how signAndSend behaves, and whether a secondary account can be selected as a private rig authority.
- Days 3-4: run the Radiants automated audit on the public repo skeleton and fix every finding. Add lint rules banning release logging and exported components.
- Week 1: classifier data and accuracy. Collect at least 2k labelled bump, pickup and slide events on real Seekers across nightstand, desk and dinner table. Target at least 99% pickup recall, with screen-on as a hard signal. Publish a model card.
- Week 1: forecaster backtest on api.ore.com history. Does the gate plus buy leg beat 'always buy' and 'always mine' on effective price per ORE over the last 60 days? Publish the result. If it doesn't, simplify to ORE-native max_production_cost plus the buy leg and demote the forecaster to advisory.
- Week 1: confirm three things with the organisers and sponsors. With Radiants office hours: which rubric the judges' form actually uses, and how the AI screen is used. With ORE: what 'strongest qualifying integration' means and which milestone metrics count. With Solana Mobile: whether executor deploys that reference a user's wallet as a writable non-signer affect Activity Tracking or bot scoring.
- Weeks 1-2: Nearby Connections on Seeker for in-person Stack. Test discovery, a 4-phone table, and one phone relaying heartbeats while the others are offline.
- Week 2: adversarial tests on Stack settlement. Cover airplane mode before a pickup, force-stop, clock skew, relay withholding, duplicate seats, and griefing with forged BREAKs (rejected because BREAKs are signature-bound).
- Week 2: name and brand check for 'Heads Down' across the dApp Store catalog, GitHub and X handles. Switch to a fallback name if it collides.
- Continuous: nightly fork CI against live ORE to catch program changes, plus a circuit-breaker drill.

## GRAFTS_FROM_OTHER_CANDIDATES
- STASH: the mine-or-buy engine (an ORE-native max_production_cost ceiling plus a morning 'buy the rest at market' leg), the effective-price scoreboard, and Gift-a-Miner reborn as Gift a Rig (SKR swapped to SOL, escrowed against the SGT, arriving as a pre-funded rig, 30-day refund, launched from ACTION_PROCESS_TEXT). Also its principle that streaks are backed by value-moving transactions, never memos.
- Pulse: the synchronized shared reveal (the morning Muster per room, with a spoiler-free share grid); the no-wallet P-256 key pattern for arming within signed caps; and the open-source SGT-verifier and secp256r1-introspection crates as public goods, crediting ORE's claim_seeker.
- Whistle (Seeker Swarm): public regional rooms that need no friends, and a community-scale metric, 'Heads Down share of ORE miners per round', for the ORE milestones.
- Night Shift crews: auditable commitments. Every deploy provably matches a phone-signed heartbeat and plan, which is the Call-PDA idea made trustless. Also the room feed auto-generated from real on-chain outcomes.
- Foreman Tycoon: the on-chain least-privilege executor (Guard), with tiles and amounts computed by the program so crankers have no discretion. Its failing-transaction demo beat returns as 'StaleHeartbeat' in place of 'StrategyViolation'.
- Gateman: blast-radius-first framing in THREAT_MODEL.md, and the one-tap Revoke/Freeze tile usable from the lock screen.
- Alert: a hardware key capped by the program, acting as a device oracle that gates only the user's own money (the part Ethelsec endorsed), without Alert's fiat and legal exposure.
- MELT: local-currency haul display and 'what held value' honesty. Never claim ORE doesn't melt.
- Punch and Airtime: Live Update conventions that count up during a user-started session, and the pattern of streaming signed off-chain messages that the chain enforces (heartbeats acting as permission vouchers).

## OPEN_QUESTIONS
- Which rubric governs final judging: the published 4x25, or the Align form's AI 20 / SKR 20 / UX 15 / UI 15 / Innovation 15 / Ecosystem 15? And how much does the AI score decide which submissions humans actually open?
- Would ORE count a heartbeat-gated third-party executor with deliberately small budgets as the 'strongest qualifying integration'? Which milestone metrics (miners per round, rigs, SOL deployed, ORE bought) would ORE accept?
- Does ORE's Discretionary fixed-fee strategy accept a PDA executor and enforce max_production_cost and min_motherlode the same way DiscretionaryBps does? Is the production-cost value ORE uses readable on-chain, so the program can apply its tighter gate? If it isn't, rely on the ORE-native condition alone.
- Can checkpoint be called permissionlessly for automations, and will ORE change the deploy account list (the entropy tail) or its layouts during judging? Would ORE give notice?
- Does Seeker's current build (MR9) expose Android 16 QPR1+ Live Update promotion and StrongBox? Is Google Play services (FCM, Nearby) present on every unit?
- Does Seeker Activity Tracking penalize wallets that an executor references as writable non-signers about twice per round? Should a secondary Seed Vault account be the default rig authority, for both privacy and a clean primary wallet?
- Can the production Seed Vault Wallet sign the multi-instruction clock-in (ORE automate + Heads Down + Jupiter) as tx v1 today, or is v0 with a lookup table needed?
- Does Solana Mobile see 'success = the Seeker set down' as aligned with its engagement KPIs, given that the app creates two value-bearing Seed Vault sessions a day and reactivates drawer devices?
- Would Solana Mobile award the SKR prize to a project that also takes the ORE match (MONOLITH's SKR prize went outside the top 10)?
- Legal: do SKR-bonded Stack tables whose forfeits pay finishers count as wagering in Nigeria, the US or other key markets? Keep bonds small, and consider offering bury-only tables.
- Is 'Heads Down' free as a dApp Store name and trademark? Is anyone in Clock In shipping a phone-gated ORE rig in a private repo?
- Should SMS-certified non-Seeker devices (no SGT) get guest rigs without bonds or room seats?
- Will Mert accept public per-rig activity times, as with any ORE miner, or should the private secondary-account rig be the default?
- Harness note: the heygen and keeperhub MCP servers need authorization, via claude.ai connector settings or `claude mcp` / `/mcp` in an interactive session. They were not needed for this evaluation.
