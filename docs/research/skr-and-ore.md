########## SKR-AND-ORE ##########
SUMMARY: I checked the official hackathon site (the text is in its JS bundle), the Align API, Solana Mobile docs and blog, the ORE source code, api.ore.com and live mainnet RPC. Six findings matter most for picking a project.

1) The ORE prize rules are written on the official Clock In site. ORE gives ONE matched prize to the strongest qualifying ORE integration among the Top 10, equal to that project's placement prize (1st place = $30k USDC + $30k ORE match). The integration must be live and user-facing, and ORE must be the "primary product featured" whenever the app supports competing products. The match is paid in stages against milestones agreed after the hackathon, and stops if the integration is removed. ORE decides "at its sole discretion".

2) SKR prize: $10k paid in SKR for the best integration. The FAQ says verbatim "(SKR Staking Integrations do not qualify)". The official blog suggests in-app purchases, rewards and access.

3) ORE today (v3 program, with "v4" changes shipped Jul–Aug 2026) is a 5x5 board game. There is roughly one round every 60–80s (78s measured). Each round mints 1 ORE, either split among miners on the winning tile or paid to one of them, depending on which tile wins. It also adds 0.2 ORE to a Motherlode jackpot with 1/500 odds per round; the jackpot was 312 ORE (~$27k) on-chain today. Since Aug 12, 2026 there is no parimutuel payout: winning tiles return 99% of deployed SOL and losing tiles 89.1%, and protocol revenue funds buy-and-bury.

4) ORE has no native mobile app. Its mobile story is a PWA added Aug 20, 2026. Regolith (ORE's developer) built a Dioxus + Mobile Wallet Adapter (MWA) Android prototype in Jul 2025 and advertised "Proof of mobile: mine ORE on Seeker – coming soon". It shipped then removed a claim_seeker instruction that checked Seeker Genesis Tokens (SGTs). A polished native Seeker ORE client fills a gap ORE itself has left open.

5) Rounds are ~78s, so asking for a wallet signature every round would ruin the UX. The ORE program's Automation account solves this: the user signs once to deposit SOL, then either ORE's permissionless executor (~7,000 lamports/round) or the app's own executor deploys each round. The app's own executor can charge a fee in basis points, capped at 1%. This allows non-custodial autominers, "crews" and shared strategies without writing a new custody program.

6) SKR+ORE together already exists in a basic form: refinORE (Jan 2026) lets users automine ORE with SKR via a swap. Solana Mobile's own SKR megathread shows that pay-with-SKR, earn-SKR leaderboards and SKR lootboxes are crowded. The SKR whitepaper's "Ecosystem Curators" idea (token-weighted reviews) is still unbuilt.

Both prizes in one project is possible (no rule forbids it; theoretical maximum is $70k), but not guaranteed: in Monolith the SKR prize went to a team outside the Top 10 (Seek).

Market data (Sep 29, 2026):
- ORE: ~$85–87, circulating supply ~498.5k of a 3M cap (the ore.com docs and the ore-mint program both say 3M; older articles say 5M), staking APY ~14.3%, production cost ~$104/ORE (above price), ~150–165 miners per round.
- SKR: ~$0.0185, supply ~10.62B.
- Clock In: 325 teams from 64 countries registered. Submissions close 2026-10-09 06:59 UTC; results Nov 10.

FINDINGS:
- [verified] The ORE prize rules are published on the official Clock In site. ORE is offering ONE matched prize to the strongest qualifying ORE integration among the Clock In Top 10, equal to that project's placement prize (1st $30k, 2nd $25k, 3rd $20k, 4th $15k, 5th $10k, 6th–10th $5k). Only one project in the whole Top 10 gets it, and if no Top 10 project qualifies, none is awarded. | EVIDENCE: Text from the solanamobile.radiant.nexus JS bundle: 'ORE is offering ONE additional matched prize to the strongest qualifying ORE integration among the Clock In Top 10.' 'A maximum of ONE matched ORE prize will be awarded across the entire Top 10.' SolanaFloor tweet (Sep 21, 2026): '@ORE is offering an additional $30K prize to builders in @solanamobile's CLOCK IN hackathon who meaningfully integrate ORE.' | SRC: https://solanamobile.radiant.nexus/, https://x.com/SolanaFloor/status/2102150173954584947
- [verified] ORE's qualifying conditions (verbatim from the site):
- 'A working, user-facing ORE integration must be live in the submitted product.'
- 'If the product supports competing or similar products, ORE must be the primary product featured and promoted during the prize period.'
- 'The ORE integration must remain live and actively supported to remain eligible for any outstanding prize payments.'
- ORE and the team agree milestone deliverables after the hackathon (development, launch, adoption, usage); the prize is paid in stages against them.
- Progress updates with usage metrics are required.
- ORE must be 'meaningfully included in relevant product launches, demos, product content and social media.'
- ORE reserves the right to decide qualification 'at its sole discretion'. | EVIDENCE: Arrays tU and tB in the official site bundle. | SRC: https://solanamobile.radiant.nexus/
- [verified] SKR prize: '$10,000 in $SKR for the best SKR integration'. The FAQ says verbatim '(SKR Staking Integrations do not qualify)'. The official blog suggests 'Use SKR for in-app purchases, rewards, access, or invent your own use case'. It is optional and separate from the USDC pool, and the main competition is judged as a single category. | EVIDENCE: Site FAQ entry 'What is SKR?' and the Clock In blog post. Some earlier press quoted 'in-app purchases, staking, rewards, access'; staking has since been dropped from the official copy. | SRC: https://solanamobile.radiant.nexus/, https://solanamobile.com/blog/clock-in-the-solana-mobile-hackathon
- [verified] Other official rules and timeline:
- Judging uses four equal criteria at 25% each: Stickiness & PMF, UX, Innovation/X-factor, Presentation & Demo.
- The project must have been started within 3 months of the hackathon launch.
- Only teams without VC or angel funding are eligible for USDC prizes.
- The demo should be about three minutes and the app must run on a real device.
- Results are announced November 10; winners must publish on the dApp Store within 30 calendar days.
- All winners get a technical review, including code verification.
- KYC runs through Sumsub; governing law is the BVI.
- The Align API shows submissions close 2026-10-09T06:59Z, judging runs Oct 10–Nov 9 and prize distribution is Nov 11.
- Registration stats: 325 teams, 358 participants, 64 countries, 850 users. | EVIDENCE: Site bundle arrays ty, tS, tw and the FAQ. GET https://align-api.radiant.nexus/hackathons/H5jQCFppZBd6wq2XLpazBjJMeLAn1HJSmrT9PpTLM1kb/stats returned {teams:325, participants:358, countries:64, users:850}. | SRC: https://solanamobile.radiant.nexus/, https://align-api.radiant.nexus/hackathons/running
- [likely] Anomaly: the Align platform config for Clock In carries a different set of scoring weights: AI 20, SKR Integration 20, UX 15, UI 15, Innovation 15, Ecosystem Impact 15. The main track has useGlobalScoring:true. The public site says four equal 25% criteria. The same weights appear in test hackathon entries, so this is probably a platform default. It could still feed Align's automated 'ai-screen' step. | EVIDENCE: The /hackathons/running response includes globalScoringCriteria with those weights. The site bundle has an 'ai-screen' endpoint. Radiants-DAO/alignai-mobile-fixtures (created Sep 15, 2026) shows 'AlignAI' statically checks MWA wiring: setup-connect, account-network-session, signing-completion, rejection-recovery, platform-configuration. | SRC: https://align-api.radiant.nexus/hackathons/running, https://github.com/Radiants-DAO/alignai-mobile-fixtures
- [verified] SKR basics:
- Mint SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3, a classic SPL Token with 6 decimals; mint authority FMNn5sorEBbEoGQGrh7y3xSbYGt116F12FpL2VTsohiw; no freeze authority.
- Launched and airdropped Jan 21, 2026.
- Supply is 10B at launch plus inflation; on-chain supply today is 10,622,283,490.
- Inflation starts at 10%, decays 25% per year, and settles at 2% terminal; it is linear and paid every 48h epoch.
- Allocation: 30% airdrop, 25% Growth & Partnerships, 15% Solana Mobile, 10% Solana Labs, 10% community treasury, 10% liquidity. Solana Mobile and Solana Labs vest over a 1-year cliff plus 3-year linear schedule.
- Staking program SKRskrmtL83pcL4YqLWt6iPefDqwXQWHSw9S9vz94BZ; Stake Config 4HQy82s9CHTv1GsYKnANHMiHfhcqesYkK6sB3RDSYyqw; Stake Vault 8isViKbwhuhFhsv2t8vaFL74pKCqaFPQXo1KkeQwZbB8; Solana Mobile Guardian Pool DPJ58trLsF9yPrBa2pk6UaRkvqW8hWUYjawe788WBuqr.
- About 4.93B SKR is staked at ~16.4% yield; unstaking is immediate with a 48h withdrawal cooldown. | EVIDENCE: docs.solanamobile.com SKR page (modified Jul 21, 2026); mainnet RPC getAccountInfo and getTokenSupply; solanamobile.com/skr; SolanaFloor tokenomics article. | SRC: https://docs.solanamobile.com/solana-mobile-stack/skr, https://solanamobile.com/skr
- [verified] SKR market and distribution:
- Price ~$0.0185 today (Jupiter), with ~$709k liquidity on its tracked pools. Launch close was $0.021, all-time high $0.0429 (Jan 22), and the low $0.0064 (Aug 3, 2026).
- The $10k SKR prize is therefore about 540k SKR.
- Season 1: ~90% claimed, ~70% of circulating SKR staked, 90k+ holders (Apr 2026).
- Seeker Summer (Jul 7–Aug 30, 2026): four rounds of quests in partner dApps. Rounds 1–3 allocated 25M/27M/29M SKR.
- SKR is listed on Upbit. | EVIDENCE: lite-api.jup.ag/price/v3 response; Phemex price history; Solana Mobile blog posts on the Season 1 wrap and Seeker Summer; Solana Compass. | SRC: https://lite-api.jup.ag/price/v3?ids=SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3, https://solanamobile.com/blog/skr-season-1-claims-wrap-monolith-crowns-winners-and-seeker-goes-shopping
- [verified] Existing SKR integrations, from Solana Mobile's own megathread (Jul 14, 2026), show what is already crowded:
- Spending: SP3ND (eBay/Amazon), Deks (4,000+ shops), Zabana (free delivery when paying in SKR), NOMADZ (hotels), Dinario/Corso (gift cards, earn SKR).
- Games and wagering: Clash of Perps, Bakeland lootboxes (+50% leaderboard boost when paying SKR), Gable Guardians, Faenora, raffle.sol.
- Earn-SKR campaigns: BananaZone, Foresee (500k SKR), Million Stars, Roaster, Murmo post-to-earn (200k SKR), Slimecoin tournaments, Seeker Wheel/Envelope spins, Seeker Legends, GEODNET TokenRun real-world quests.
- Other: Jupiter Portfolio shows staked SKR; refinORE Autominer mines ORE with SKR.
- Season 2 featured Colony (staked-SKR gameplay) and Faenora (SKR purchases). | EVIDENCE: Full thread text pulled via api.fxtwitter.com/2/thread/2077091413767204976; the Clock In blog links this thread as inspiration. | SRC: https://x.com/solanamobile/status/2077091413767204976, https://solanamobile.com/blog/sms-goes-global-and-season-2-wrapup
- [verified] Past SKR bonus winner and what Solana Mobile says it wants:
- The Monolith SKR Track winner was 'Seek', AI-powered scavenger hunts where the Seeker is used to explore the real world and compete with friends. It was separate from the 10 grand-prize winners.
- Cashflow, a grand-prize winner, used 'SKR staking integration' for gas-free transactions; that pattern is now excluded.
- The Builder Grants post asks for SKR used for 'incentives, payments, exclusive experiences, or entirely new participation mechanics'. It discourages teams that only 'want SKR to distribute across an existing user base'. | EVIDENCE: Monolith winners blog (Jul 14, 2026); Builder Grants blog post. | SRC: https://solanamobile.com/blog/solana-mobile-monolith-hackathon-winners-announced, https://solanamobile.com/blog/solana-mobile-builder-grants-bring-your-best-seeker-and-skr-ideas
- [verified] The SKR whitepaper describes utility that is still unbuilt:
- Token-weighted curation of dApp Store apps.
- 'Ecosystem Curators' who review apps publicly; apps with enough poor reviews move to a 'spam' section. Curators are not rewarded at launch.
- A developer SKR bond (100 SKR, timelocked 3 months) for dApp submissions.
- Future SKR discounts or rebates, and fee capture at the dApp evaluation layer.
An SKR-weighted curation or reputation layer is 'on-thesis' for Solana Mobile without being a staking integration. | EVIDENCE: SKR whitepaper PDF (pdftotext), 'Curation', 'Guardians' and 'Ecosystem Curators' sections. | SRC: https://pub-227d63fec15a494fb95f11fb42cf6bf4.r2.dev/whitepaper.pdf, https://solanamobile.com/whitepaper
- [verified] Seeker identity primitives useful for SKR 'access' features and anti-sybil checks:
- Seeker Genesis Token (SGT): a Token-2022 NFT minted once per device, with metadata/group address GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te and mint authority GT2zuHVaZQYZSyQMgJPLzvkmyztfyXg2NJunqFp4p3A4. The docs recommend Sign-in-with-Solana (SIWS) plus an SGT check, recording the SGT mint so each device can claim only once.
- .skr domains: every Seeker owner gets one, resolved both ways via AllDomains.
- Seeker Connect SDK: direct connection to the device wallet, built on MWA (docs dated Sep 11, 2026; web available, React Native and Kotlin planned).
- Community package seeker-sdk (npm) wraps SGT checks, .skr lookups, SKR balances and staking data. | EVIDENCE: docs.solanamobile.com pages; the retired ORE claim_seeker code checks the same SGT authorities; the seeker-sdk README. | SRC: https://docs.solanamobile.com/solana-mobile-stack/seeker-genesis-token, https://docs.solanamobile.com/recipes/general/detecting-seeker-users
- [verified] ORE addresses:
- Mining program oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv (the IDL version reads 4.0.0; the Rust crate ore-api is at 3.8.25, published Sep 8, 2026).
- Mint oreoU2P8bN6jkk3jbaiVxYnG1dCXcYxwhwyK9jSybcp: classic SPL, 11 decimals.
- Board BrcSxdp1nXFzou1YyDnQJcPNBNHgoypZmTsyKBSLLXzi, Treasury 45db2FSR4mcXdSVVZbKbwojU6uYDpMyhpEi7cC8nHaWG, Config 9c9X7aDRAF41faiDs94ELjT19UrGnn72wBW9hPsS4Awy.
- ore-mint mintzxW6Kckmeyh1h6Zfdj9QcYgCzhPSGiC8ChZ6fCx with MAX_SUPPLY = 3,000,000 ORE.
- Stake program stakecNP3FpiExZPCgZfqRgumVzi6dNqnfrjwXyTgeH; LST program storeD7bEkywTTMrje19WRoyrkEhbhrvyjVnLxWih6a; stORE mint storenSbvkfzircixnaosc5CbzNZVrHJ6S3EKrS1yqR.
- Entropy RNG 3jSkUuYBoJzQPMEzTvkDFXCZUBksPamrVhrnHR9igu2X; grams gram5fpWcCWgE65u3DGKncvnxKqFPdbeRZqQg9joE8L.
Older sources quote a stale v2 program ID (mineRHF5...) and a 5M cap. | EVIDENCE: regolith-labs/ore api/src/consts.rs and lib.rs; ore-mint, ore-stake, ore-lst, entropy and grams consts; mainnet RPC. The ore.com Learn text says: 'ORE has a maximum supply of 3,000,000 tokens, enforced by the protocol's immutable mint program.' | SRC: https://github.com/regolith-labs/ore, https://github.com/regolith-labs/ore-mint
- [verified] How ORE mining works now (from source code and ore.com Learn):
- Board: 5x5 grid; miners deploy SOL on any tiles each round. The winning tile is rng % 25, with randomness from slot hashes plus the Entropy commit-reveal protocol.
- Split vs solo: each round has 15 'split' and 10 'solo' tiles (solo tiles marked with a star). Split tiles share 1 ORE pro-rata among everyone on the winning tile; on solo tiles one miner wins the whole 1 ORE with odds weighted by their SOL.
- Motherlode: +0.2 ORE per round to a jackpot with 1/500 odds, split pro-rata on the winning tile.
- SOL returns: since Aug 12, 2026 there is no parimutuel payout. Winning-tile SOL comes back at 99% (1% admin fee); losing tiles come back at 89.1% (an extra 10% protocol fee).
- Claims: a 10% 'refining fee' on claimed ORE goes to miners who haven't claimed; partial claims have been allowed since Jul 12.
- Timing: round_slots=240 plus intermission_slots=48. Solana slot time dropped from 400ms to 300ms, so rounds are ~1 min plus intermission; measured ~78s per round. | EVIDENCE: program/src/reset.rs, checkpoint.rs, api/src/state/round.rs (did_hit_motherlode: rng.reverse_bits() % 500 == 0; calculate_fees; distribution_mask selects 10 solo bits). Config account read on-chain: admin 100 bps, protocol 1000 bps, intermission 48, round 240. ore.com changelog: 'Solana's recent reduction in slot times from 400ms to 300ms' (Sep 1, 2026). | SRC: https://github.com/regolith-labs/ore/blob/master/program/src/reset.rs, https://github.com/regolith-labs/ore/blob/master/api/src/state/round.rs
- [verified] ORE changelog for 2026 (dates from the ore.com app):
- Jun 15: stake-program exploit (a missing account check in deposit allowed inflated stake shares; no user deposits lost). Migrated to new stake and LST programs and a new stORE mint.
- Jul 12: partial claims.
- Jul 14: Motherlode odds changed from 1:625 to 1:500 ('expected motherlode value a clean 100 ORE').
- Jul 29: split/solo tile assignment.
- Aug 3: conditional deployments (min/max Motherlode).
- Aug 12: parimutuel SOL payouts removed.
- Aug 17: DiscretionaryBps strategy lets third-party autominers charge fees in basis points; CSV export.
- Aug 20: PWA manifest and service worker; mobile menu.
- Sep 1: round time adjustment.
- Sep 9: ORE Reserve launched at 1% of revenue. Sep 10: raised to 2%. Sep 14: raised to 5% and GLDx added. GitHub commit Sep 25: 'bump reserve to 10%'.
- Sep 25: Learn section added. | EVIDENCE: Changelog strings in the ore.com WASM bundle; regolith-labs/ore commit history. | SRC: https://ore.com, https://github.com/regolith-labs/ore/commits/master
- [verified] Live ORE metrics (Sep 29, 2026, api.ore.com/stats/history):
- Price $85.36 (Jupiter: $87.14); production cost $104.62/ORE, so mining costs more than the market price and miners are mainly chasing the Motherlode.
- Staking APY 14.27%; ~273.7k ORE staked plus ~41.4k in stORE.
- Circulating supply 498,545; holders 32,856; tracked liquidity ~$775k.
- 24h ORE trading volume ~$0.8M; protocol revenue ~904 SOL/24h; lifetime revenue ~382,880 SOL.
- Mining: 153–162 average miners per round and ~9–11.5 SOL deployed per round.
- On-chain Motherlode was 312.2 ORE (~$27k) right now; recent hits were 179.4 ORE (Sep 28) and 87 ORE (Sep 27).
The active mining base is small (~150–165 miners per round), which leaves obvious room for mobile user acquisition. | EVIDENCE: GET api.ore.com/stats/history, /stats/revenue-24h, /stats/total-revenue, /events/motherlode, /events/reset; Treasury account read via mainnet RPC. | SRC: https://api.ore.com/stats/history, https://api.ore.com/events/motherlode?page=0
- [verified] The Automation account is the key technical unlock for a phone app: one signature, then hands-free mining. An automation stores:
- amount per tile and a SOL balance;
- an executor, a fee, a strategy (Random, Preferred, Discretionary, DiscretionaryBps) and a tile mask;
- auto-reload of SOL winnings;
- conditions: max production cost, min/max Motherlode, number of split and solo tiles.
ORE's own Lite/Pro autominer uses the permissionless EXECUTOR_ADDRESS with a fee of 7,000 lamports per round (5,000 plus a 2,000 Jito tip).
Third-party executors can use Discretionary (fixed fee) or DiscretionaryBps (≤100 bps, charged once per round). The executor picks tiles and amount, capped at automation.amount, but the SOL balance can only be deployed or returned to the authority. The result is non-custodial automation with a built-in business model. | EVIDENCE: api/src/state/automation.rs; program/src/deploy.rs, which asserts executor == signer or EXECUTOR_ADDRESS; docs/DISCRETIONARY_BPS.md; ore-starter-app use_automate_transaction.rs (AUTOMATION_FEE = 5000 + JITO_TIP_AMOUNT 2000; strategy Random or Preferred). | SRC: https://github.com/regolith-labs/ore/blob/master/docs/DISCRETIONARY_BPS.md, https://github.com/regolith-labs/ore/blob/master/api/src/state/automation.rs
- [verified] ORE developer tooling:
- regolith-labs/ore-starter-app (Aug 10, 2026): a stripped-down fork of the production ore.com client (Dioxus/Rust WASM plus Tailwind) with transaction builders for deploy, automate, claim, top-up and reset, and account subscriptions for board, round, miner and treasury.
- REST API at https://api.ore.com: /stats/history, /stats/revenue-24h, /stats/top-miners-unrefined, /stats/top-stakers, /events/motherlode, /events/reset, /events/bury, /events/buyback, /users/{addr} (usernames, profile photos), /round/{id}/miners, /slothash, /market.
- Entropy API at entropy-api.onrender.com.
- Rust crates: ore-api, ore-types, steel, entropy-api. An IDL is at api/idl.json.
- No official TypeScript SDK on npm. Evore (Kriptikz) offers a managed-miner program with a JS SDK.
- regolith-labs/solana-mobile (Jul 2025): a Dioxus Android example with a Rust-to-Kotlin bridge for MWA authorize/signTransaction/signMessage. | EVIDENCE: Starter app README and src/gateway/ore.rs endpoint list; live GETs to api.ore.com succeeded without auth; crates.io; npm search. | SRC: https://github.com/regolith-labs/ore-starter-app, https://api.ore.com/stats/history
- [likely] ORE has repeatedly signalled a Seeker/mobile ambition but never shipped a native app:
- The ore-app landing page (2025) had a card 'Proof of mobile — Use a Solana Seeker mobile phone to mine ORE anywhere and everywhere you go — Coming soon'.
- On Sep 26, 2025 the program added claim_seeker: SGT verification with an 'is_seeker' flag on miner and stake accounts. It was removed in the Nov 6, 2025 entropy merge; only the SEEKER PDA seed remains.
- ore.com today is a web app with Privy social login and, since Aug 20, 2026, a PWA.
The hackathon prize effectively asks for the native Seeker ORE experience ORE hasn't built. | EVIDENCE: regolith-labs/ore-app src/pages/landing.rs; ore commit 'seeker' (037aa5e480) and the claim_seeker.rs history; ore.com index.html (Privy) and changelog. The inference is about intent. | SRC: https://github.com/regolith-labs/ore-app, https://github.com/regolith-labs/ore/commits/master/program/src/claim_seeker.rs
- [verified] Third-party ORE ecosystem:
- refinORE Autominer (web; supports AI-agent tools OpenClaw and Clawdbot) added 'auto mining with SKR tokens' on Jan 22, 2026. This is the existing SKR+ORE precedent.
- MineMore: a Privy-subaccount ORE mining app with a separate recovery CLI.
- Evore: a managed-miner program by Kriptikz.
- Radiants-DAO/lodestar-cli: the hackathon organizer's own zero-fee ORE terminal autominer with EV heatmaps and a local keypair.
- Farmer and sniper bots; Jupiter platform-list entry 'oresupply'; Tributary plans to allow the ORE program as a recurring-payment forward target.
- StonkFun ORE-paired launches.
- Privacy Cash 'shield' pools for private ORE/stORE transfers.
- ORE Reserve: Meteora limit-order liquidity in SOL/USDC/GLDx(/ZEC); hourly withdrawals, 90% buried and 10% to stakers.
- Meteora/Orca ORE pools, including SPCX-ORE.
- grams address GHRBYPA4cujFwfyhNNm6NLTh4egdTrcz7xkBbEwM4xX automatically buries any ORE sent to it.
- MetaDAO partnership for Reserve governance. | EVIDENCE: fxtwitter thread for JussCubs tweet 2014468024154358000; GitHub READMEs; ore.com bundle strings (PrivacyCash, reserve text); Genfinity article (Sep 28, 2026). | SRC: https://x.com/JussCubs/status/2014468024154358000, https://automine.refinore.com/
- [inferred] Winning both the SKR prize and the ORE match with one project is allowed by the published rules; nothing forbids it. The ORE match requires a Top 10 finish. The SKR prize is 'separate from the main USDC prize pool' and doesn't affect main-prize eligibility. Best case: $30k USDC + $30k ORE match + $10k in SKR = $70k, plus Seeker devices. In Monolith, however, the SKR bonus went to a team outside the Top 10 (Seek), which suggests Solana Mobile may use it to reward an extra team. | EVIDENCE: Site prize text and FAQ; the Monolith blog lists 10 grand-prize winners plus a separate 'SKR Track Winner'. | SRC: https://solanamobile.radiant.nexus/, https://solanamobile.com/blog/solana-mobile-monolith-hackathon-winners-announced
- [inferred] Constraints that shape a credible ORE+SKR design:
(a) ORE deploys are denominated in SOL, so SKR can only fund mining through a swap (Jupiter) or pay for app-level services.
(b) A plain 'mine with SKR' swap is already shipped (refinORE), so it isn't novel.
(c) The ORE rule allows other tokens as long as ORE is primary relative to competing products. SKR is not a competing mining or store-of-value product, so featuring SKR as a funding and social currency is compatible.
(d) The judges include two EthelSec security researchers, and Solana Mobile runs an SKR bug bounty of up to $75k. Custody design will be scrutinised; using ORE's native Automation accounts avoids writing a pooled-custody program. | EVIDENCE: Deploy instruction takes lamports; refinORE precedent; ORE rule text; Solana Mobile bug bounty blog (Aug 17, 2026) naming EthelSec as its first security grant recipient. | SRC: https://github.com/regolith-labs/ore/blob/master/program/src/deploy.rs, https://solanamobile.com/blog/introducing-the-solana-mobile-vulnerability-disclosure-policy-bug-bounty-program-and-security-grants
- [likely] The Solana dApp Store Publisher Policy has no explicit ban on gambling, lotteries or games of chance. It requires compliance with applicable law and documentation for regulated financial services. Wagering apps such as raffle.sol and Slimecoin cash games already run on Seeker, so an ORE mining app with Motherlode-jackpot mechanics looks publishable. Presenting it as a 'lottery' in some regions still carries legal risk. | EVIDENCE: The policy text contains no gambling section; raffle.sol and Slimecoin appear in the Solana Mobile megathread and blog. | SRC: https://legal.solanamobile.com/publisher-policy-web, https://x.com/solanamobile/status/2077091413767204976

IMPLICATIONS_FOR_WINNING:
- An ORE-core app that places 1st pays $30k USDC plus a $30k ORE match, and adding the $10k SKR prize makes $70k. Since the ORE match only goes to the single strongest ORE integration in the Top 10, being the obvious best ORE app is a big edge. It also looks like a gap: ORE has no native mobile app, and no public GitHub competitor showed up in searches (many hackathon repos stay private until submission).
- Treat ORE as the product's engine rather than a feature. The rules require ORE to be primary, the integration to be live on mainnet and user-facing, and the team to keep supporting it through milestone payments. Plan a real mainnet launch, and put ORE branding in the demo, pitch deck and social posts.
- Solve the ~78-second round cadence with ORE's Automation account. One Seed Vault signature funds a session, and then ORE's permissionless executor (~7,000 lamports/round) or your own DiscretionaryBps executor (fee ≤1%) deploys every round. This is the UX unlock that scores on the 25% UX criterion, and it is non-custodial, which matters to the EthelSec security judges.
- Make the app sticky with phone-native hooks around the Motherlode jackpot (currently 312 ORE, ~$27k, 1/500 odds per round, about one hit a day on average): threshold push notifications ('Motherlode passed 300 ORE'), a home-screen widget, haptic reveals of the winning tile, a daily 'what you mined overnight' digest, and refining-yield growth. This targets the 25% Stickiness criterion ('daily engagement').
- Give SKR a role beyond pay-with-SKR and earn-SKR leaderboards. Options: SKR as a one-tap funding rail (atomic Jupiter SKR→SOL swap into the automation deposit); SKR tips between .skr names when someone wins a solo tile or hits the Motherlode; SKR-weighted curation of shared mining strategies, which matches the whitepaper's Ecosystem Curators idea; SKR-sponsored crew bounties. Never involve SKR staking, and avoid wording that sounds like staking (use 'bond', 'escrow' or 'spend').
- Use Seeker identity for 'Proof of Mobile', reviving ORE's abandoned claim_seeker idea at the app layer: SIWS plus an SGT check, recording the SGT mint so each device counts once, a Seeker-only leaderboard or crew seat, and .skr names on the live board. This is Seeker-exclusive differentiation that pleases both Solana Mobile and ORE.
- Tie the app to ORE's tokenomics story. Route part of the app's fees (or SKR tips) to buy ORE and send it to the grams address (GHRBYPA4...), which buries it automatically, so every SKR spent reduces ORE supply. Show the ORE Reserve and buyback stats (buried per 7 days) in the app. This fits Hardhat Chad's 'hard money' narrative.
- Be honest about the economics. Production cost (~$104) is above the ORE price (~$85), so present mining as 'accumulate hard money with jackpot upside' and show fees and expected losses clearly. Security researchers and Toly will respect that more than yield hype.
- Security matters in the technical review: no private-key export, no local hot keypairs, and correct MWA wiring (authorize, use the authorized account, handle rejection). AlignAI appears to lint exactly these points (setup-connect, account-network-session, signing-completion, rejection-recovery, platform-configuration). Explain in the README why the executor cannot steal funds.
- Cheap hedge: the Align backend config lists 'AI 20%' and 'SKR Integration 20%'. A small, genuinely useful AI feature (a strategy explainer, or an 'autopilot' that sets Motherlode thresholds and split/solo mix) plus SKR costs little and covers the case where an automated pre-screen uses those weights.
- Timing: submissions close 2026-10-09 06:59 UTC (Oct 8, 23:59 PT). Mainnet ORE and SKR are both live, and api.ore.com plus RPC websockets are open, so a working mainnet demo is feasible within the remaining ~9 days.

IDEA_SEEDS:
- "Motherlode": a native Seeker ORE miner (Kotlin or React Native). One Seed Vault signature starts a session: 'Mine 0.2 SOL over 50 rounds, 10 split + 3 solo tiles, only when the Motherlode is above 150 ORE'. It then runs hands-free through ORE Automation, with a live 5x5 board, haptic tile reveal, jackpot push alerts and a home-screen widget. SKR 'Fuel' tops up the automation via an atomic Jupiter SKR→SOL swap, and tips go to .skr winners.
- "ORE Crews": social co-mining without a pooled-custody program. Each SGT-verified crew member keeps their own Automation account and appoints the crew captain's backend as a DiscretionaryBps executor (≤1% fee). The captain sets tile strategy, the crew shares a feed of wins and Motherlode hits, and crews compete in weekly SKR-sponsored bounties. The non-custodial design is a strong security story for the EthelSec judges.
- "Strategy Market": users publish ORE autominer strategies (tile masks, split/solo mix, Motherlode and production-cost thresholds). Followers mine with one tap via a DiscretionaryBps executor, and strategy authors earn a bps revenue share. Seeker owners rank strategies by SKR-weighted curation (upvotes cost SKR, with a share burned or given to authors), which implements the SKR whitepaper's 'Ecosystem Curator' idea.
- "Mine While You Sleep": a daily-habit app that drips a small SKR, USDC or SOL budget into ORE mining every night. A morning card shows ORE mined, refining yield earned, whether you were on the winning tile, and your Motherlode near-misses, plus a streak. This is a hard-money DCA habit on Seeker, measured by daily active users (DAU).
- "Gift a Miner": send SKR to a friend's .skr name and it arrives as a pre-funded ORE mining session (an automation created for them on first open, gated by SGT). It is a viral onboarding loop for ORE on Seeker: SKR is the gifting currency, ORE is the product.
- "Bury Button": every SKR in-app purchase (cosmetic board skins, crew banners, extra notification slots) routes a fixed share to buy ORE and send it to the grams address, which buries it automatically. A live 'you've buried X ORE' counter ties SKR spending to ORE scarcity, a narrative the ORE team is likely to amplify.
- "Proof of Mobile" revived: a Seeker-only side leaderboard ranked by ORE mined per verified SGT device, with seasonal SKR-sponsored rewards for top Seeker miners. It delivers the 'mine ORE on your Seeker' promise ORE advertised but never shipped (the claim_seeker instruction was removed Nov 2025).
- Motherlode 'watch party': a live social view of each 60–80s round, with .skr names placed on tiles, reactions, and SKR micro-tips thrown at whoever wins a solo tile. Rounds run every ~78s around the clock, so the feed is always live.

THINGS_TO_AVOID:
- Any SKR staking or Guardian-delegation integration: the FAQ says verbatim '(SKR Staking Integrations do not qualify)'. Also avoid staking-gated perks like Monolith's Cashflow (gasless via staked SKR).
- Generic SKR uses that are already crowded according to Solana Mobile's own megathread: pay with SKR at checkout, earn SKR on a weekly leaderboard, SKR lootboxes or in-game items, spin-to-win SKR, raffle or wager in SKR.
- A plain 'mine ORE with SKR' swap: refinORE shipped this in Jan 2026, so it isn't novel on its own.
- A web or PWA wrapper of ore.com: ORE already ships a PWA (Aug 20, 2026), and the rules say PWA wrappers 'will score poorly and are unlikely to win'.
- Asking the user to sign every ~78-second round through MWA or Seed Vault: use ORE Automation (permissionless or your own executor) instead.
- Custodial patterns the security judges will flag: exporting private keys (MineMore needed a Privy key-export recovery tool), local hot keypairs like lodestar's plaintext id.json, or a new unaudited pooled-custody program when native Automation accounts already do the job.
- Making ORE one token among many: the ORE prize requires ORE to be 'the primary product featured and promoted' if the app supports competing or similar products.
- Presenting ORE mining as profitable yield: production cost (~$104) is currently above price (~$85). Overclaiming expected value hurts credibility and adds regulatory risk.
- Relying on stale ORE facts: the v2 program ID mineRHF5..., the 5M supply cap, parimutuel SOL payouts (removed Aug 12, 2026), and 1:625 Motherlode odds (now 1:500). Use program oreV3EG1..., the stake program stakecNP..., and the stORE mint storenSb... (the old STkEAu2/LStwN2/sTorERY... addresses were affected by the Jun 15, 2026 exploit).
- Counting on the SKR bonus as a certainty for a Top-10 project: in Monolith it went to a team outside the Top 10. Treat it as upside, not the plan.
- Starting from an older codebase: projects must have started within 3 months of launch, and funded (VC/angel) teams can't receive USDC prizes.

OPEN_QUESTIONS:
- What exact milestones and payout schedule would ORE require for the matched prize (for example usage, number of miners or SOL deployed)? It is negotiated after the hackathon; a short DM to @ORE or Hardhat Chad could clarify what 'strongest qualifying' means.
- Would Solana Mobile award the SKR prize to a project that also places Top 10 and wins the ORE match, or do they prefer spreading prizes across teams, as the Monolith precedent suggests?
- Is Align's globalScoringCriteria (AI 20, SKR 20, UX 15, UI 15, Innovation 15, Ecosystem Impact 15) used anywhere, such as the AlignAI 'ai-screen' step, or is it only a platform default that the public 4×25% rubric overrides?
- How reliably does ORE's permissionless executor crank small automations (7,000-lamport fee) every round? Does a mobile app need its own executor for guaranteed execution and custom strategies?
- Does ORE treat SKR-denominated side features (tips, bounties) as fine, or would an SKR-heavy app risk failing the 'ORE must be primary' test? This probably depends on framing.
- What is 'ZINC', the mobile mining app that went live on the Seeker dApp Store in August 2026? Is it ORE-related or a competitor?
- Is there an official TypeScript client for ORE v4? None was found on npm; teams may need to generate one from api/idl.json with Codama, port the Rust sdk.rs, or use Evore's JS SDK. Does the IDL fully match the deployed program (it omits some admin instructions)?
- Does the Solana dApp Store's review (Guardian checks and an LLM content review, per the whitepaper) flag jackpot or lottery framing? The publisher policy has no explicit gambling ban, but reviewer practice is unknown.
