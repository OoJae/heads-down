# Foreman Cost Forecaster: data and backtest

This directory answers one question for Heads Down: for a small nightly ORE budget, should the rig
mine, buy, or gate between the two? It also asks whether a learned forecaster makes a better gate
than the on-chain `production_cost_ema`.

- **[RESULTS.md](RESULTS.md)** has the numbers and the recommendation.
- **[MODEL_CARD.md](MODEL_CARD.md)** covers the forecaster and what ships on the device.
- **[forecaster.ipynb](forecaster.ipynb)** is an executed read-through that calls the same modules.

| File | What it does |
|---|---|
| `fetch.py` | A polite, resumable fetcher that uses only the standard library. It pulls `api.ore.com` history (every round's `ResetEvent`, Motherlode hits, stats snapshots and revenue), the ORE/SOL pool's hourly candles from GeckoTerminal, and a read-only snapshot of the ORE Board and Treasury accounts. |
| `orelib.py` | ORE mechanics for a small miner, taken from the ORE program source: fees, pro-rata and solo payouts, the Motherlode pot, the integer EMA, shift windows, and a price join that cannot look ahead. |
| `validate.py` | Checks the reconstruction against the fee identities, every Motherlode payout, api.ore.com's `production_cost` and the live Board account. |
| `backtest.py` | Spend-matched strategies (always buy, always mine, EMA and EV gates, an hourly hindsight oracle, concentrated deploys), a budget sweep, and a Monte Carlo of ORE per night. |
| `forecast.py` | A walk-forward forecast of the next hours' EMA against price (persistence vs ridge vs gradient boosting), scored as a forecast and as a gate. It also exports the linear model as JSON. |
| `tests/` | Pytest checks: the integer math matches the Rust source, strategies spend equally, the oracle bounds both pure strategies, decisions are causal, budgets are capped, and the fee rules hold on real sample rounds. |

## Reproduce

```bash
cd ml/forecaster
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
python3 fetch.py                       # ~45 min: ~650 pages of /events/reset at 0.8 s spacing
.venv/bin/python validate.py
.venv/bin/python backtest.py           # writes out/backtest_results.json and figures/*.png
.venv/bin/python forecast.py           # writes out/forecast_results.json and out/forecaster_ridge_h1.json
.venv/bin/python -m pytest -q tests
.venv/bin/pip install nbformat nbclient ipykernel && .venv/bin/python build_notebook.py
```

You can run everything offline against the committed sample (`data/sample/`, under 50 KB)
with `--sample`. The sample is enough for the tests and a smoke run, but too short for the
published numbers.
