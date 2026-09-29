#!/usr/bin/env python3
"""Build and execute forecaster.ipynb: a read-through of validation, backtest and forecaster.

The notebook calls the same modules as the scripts (no duplicated logic), so the numbers in it
match RESULTS.md. Figures are referenced from figures/ rather than embedded, to keep it small.
    .venv/bin/python build_notebook.py            # uses data/ (run fetch.py first)
    .venv/bin/python build_notebook.py --sample   # committed sample only
"""
import os
import sys

import nbformat
from nbclient import NotebookClient

HERE = os.path.dirname(os.path.abspath(__file__))


def build(sample: bool) -> nbformat.NotebookNode:
    S = "True" if sample else "False"
    md = nbformat.v4.new_markdown_cell
    code = nbformat.v4.new_code_cell
    cells = [
        md("# Heads Down: Foreman Cost Forecaster, data and backtest\n\n"
           "Mine-or-buy for a small nightly ORE rig, replayed over every ORE round since the "
           "Aug 12, 2026 fee change. Reproduce with `python3 fetch.py && .venv/bin/python backtest.py "
           "&& .venv/bin/python forecast.py`. Written results: [RESULTS.md](RESULTS.md), model card: "
           "[MODEL_CARD.md](MODEL_CARD.md)."),
        code("import json, warnings\nimport numpy as np, pandas as pd\n"
             "pd.set_option('display.width', 160); pd.set_option('display.max_columns', 20)\n"
             f"SAMPLE = {S}\n"
             "import orelib as L, backtest as BT, forecast as F, validate as V"),
        md("## 1. Is the round reconstruction right?\n"
           "Fee identities on every round, Motherlode pot vs every payout, replayed EMA vs "
           "api.ore.com and vs the live Board account."),
        code("val = V.run(sample=SAMPLE)\n"
             "for k in ('coverage', 'fee_identities', 'motherlode', 'ema_vs_api', 'api_cost_below_price', 'chain_check', 'tiles'):\n"
             "    print(k, json.dumps(val.get(k), indent=1, default=str)[:800])"),
        md("## 2. Crowding follows the Motherlode pot\n"
           "`production_cost_ema` counts 1.2 ORE per round and ignores the pot, but miners pile in "
           "when the pot is large. Adjusting the EMA for the pot's expected value (1 + pot/500 ORE per "
           "round) flattens the cost curve."),
        code("pd.DataFrame(val['crowding_vs_pot']['by_pot']).T.round(3)"),
        md("## 3. Mine-or-buy at small budgets\n![](figures/eff_price_vs_budget.png)"),
        code("window = L.ShiftWindow(23, 7, 1)\n"
             "prep = BT.prepare(SAMPLE, window)\n"
             "r, n_med = prep['rounds'], prep['rounds_per_night']\n"
             "costs = L.Costs()\n"
             "print(f\"{r['night'].nunique()} nights, median {n_med} rounds/night\")\n"
             "tables = {}\n"
             "for B in (0.02, 0.04, 0.05):\n"
             "    A = B * 1e9 / n_med\n"
             "    tables[B] = BT.summarize(BT.evaluate(r, A, costs, BT.decisions_for(r, A, costs)), r, costs, n_boot=500)\n"
             "tables[0.04][['eff_sol_per_ore', 'vs_always_buy_pct', 'ci95_lo_pct', 'ci95_hi_pct', 'mined_round_share']].round(4)"),
        md("## 4. Variance of a small rig\n![](figures/ore_per_night.png)"),
        code("rows = []\n"
             "for B in (0.02, 0.04):\n"
             "    A = B * 1e9 / n_med\n"
             "    for pol in L.TILE_POLICIES:\n"
             "        d = BT.night_distribution(r, A, costs, pol, n_sims=1000)\n"
             "        d.pop('samples'); d['budget'] = B; rows.append(d)\n"
             "pd.DataFrame(rows)[['budget', 'policy', 'mean_ore', 'p5', 'median', 'p95', 'p_zero']]"),
        md("## 5. Forecaster: does predicting the EMA beat reading it?\n![](figures/forecast_walkforward.png)"),
        code("rounds = L.load_rounds(sample=SAMPLE); prices = L.load_prices(sample=SAMPLE)\n"
             "since = str(rounds['time'].min().date()) if SAMPLE else BT.REGIME_START\n"
             "h = F.add_targets(F.hourly_table(rounds, prices, since), (1,))\n"
             "pred = F.walk_forward(h, 1, since, min_train_days=1 if SAMPLE else 7)\n"
             "pd.DataFrame(F.score(pred)).T.round(4) if len(pred) else 'not enough data'"),
        md("See [RESULTS.md](RESULTS.md) for the recommendation."),
    ]
    nb = nbformat.v4.new_notebook(cells=cells)
    nb.metadata["kernelspec"] = {"name": "python3", "display_name": "Python 3", "language": "python"}
    return nb


def main() -> int:
    sample = "--sample" in sys.argv
    nb = build(sample)
    NotebookClient(nb, timeout=1800, kernel_name="python3", resources={"metadata": {"path": HERE}}).execute()
    path = os.path.join(HERE, "forecaster.ipynb")
    nbformat.write(nb, path)
    print(f"wrote {path} ({os.path.getsize(path) / 1024:.1f} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
