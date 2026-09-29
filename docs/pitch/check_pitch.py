#!/usr/bin/env python3
"""Lint docs/pitch/*.md: banned wording, reviewer-directed text, repo paths, counts and lengths.

Usage: python3 docs/pitch/check_pitch.py [hash_out_file]

Hard failures (exit code 1): a word from the banned list in docs/ECONOMICS.md section 4 (the hard
subset), text addressed to the people or tools scoring the submission, a repo path that does not
exist, a skrIntegration draft over 950 characters, a deck outside 15 to 17 slides or missing a part,
a Q&A without 20 questions, an X post over 280 characters or without its DRAFT label, or a VO over
460 words. Softer wording is printed as "soft" for a human to judge. Commit hashes referenced in
backticks are written to <hash_out_file>, to verify with `git cat-file --batch-check`.
"""
import glob
import os
import re
import sys

root = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
hash_out = sys.argv[1] if len(sys.argv) > 1 else None
pitch = os.path.join(root, "docs", "pitch")
files = sorted(glob.glob(os.path.join(pitch, "*.md")))
fail = False

HARD = [r"\bearn\w*", r"\byield\w*", r"\bstak(e|ed|es|ing)\b", r"passive income",
        r"proof of focus", r"focus mining"]
SOFT = [r"\blotter\w*", r"\bjackpot\w*", r"\bgambl\w*", r"\bbets?\b", r"\bprofit\w*",
        r"\bguarantee\w*", r"risk-free", r"free ORE", r"\binvest\w*", r"\brewards?\b",
        r"\bAPY\b", r"\bAPR\b", r"\breturns\b"]
REVIEWER = [r"judges? should", r"dear judge", r"\breviewers?\b", r"ai screener",
            r"ignore (all )?previous", r"score this", r"note to (the )?judges",
            r"attention,? judges", r"as an ai"]

TOP = ("android/", "crank/", "crates/", "dashboard/", "docs/", "ml/", "programs/",
       "registrar/", "services/", "spikes/")
path_re = re.compile(r"(?<![\w/.-])((?:%s)[A-Za-z0-9_./*-]*|buildplan\.md|README\.md)"
                     % "|".join(re.escape(t) for t in TOP))
hash_re = re.compile(r"`([0-9a-f]{7})`")

missing, hashes = set(), set()
for f in files:
    name = os.path.basename(f)
    text = open(f, encoding="utf-8").read()
    for pat in HARD:
        for m in re.finditer(pat, text, re.I):
            line = text.count("\n", 0, m.start()) + 1
            print(f"HARD  {name}:{line}: '{m.group(0)}'")
            fail = True
    for pat in SOFT:
        for m in re.finditer(pat, text, re.I):
            line = text.count("\n", 0, m.start()) + 1
            print(f"soft  {name}:{line}: '{m.group(0)}'")
    for pat in REVIEWER:
        for m in re.finditer(pat, text, re.I):
            line = text.count("\n", 0, m.start()) + 1
            print(f"REVIEWER-DIRECTED  {name}:{line}: '{m.group(0)}'")
            fail = True
    for m in path_re.finditer(text):
        p = m.group(1).rstrip(".,:;)")
        if p.endswith("/"):
            p = p[:-1]
        full = os.path.join(root, p)
        if "*" in p:
            ok = bool(glob.glob(full))
        else:
            ok = os.path.exists(full)
        if not ok:
            missing.add((name, p))
    hashes.update(hash_re.findall(text))

for name, p in sorted(missing):
    print(f"MISSING PATH  {name}: {p}")
    fail = True

print(f"commit hashes referenced: {len(hashes)}")
if hash_out:
    with open(hash_out, "w") as h:
        h.write("\n".join(sorted(hashes)) + "\n")
    print(f"  written to {hash_out}; verify with: git cat-file --batch-check < {hash_out}")

# skrIntegration drafts <= 950 characters
sub = open(os.path.join(pitch, "SUBMISSION.md"), encoding="utf-8").read()
for tag in ("skr-A", "skr-B"):
    m = re.search(r"<!-- %s:start -->\n(.*?)<!-- %s:end -->" % (tag, tag), sub, re.S)
    s = " ".join(re.sub(r"^> ?", "", l) for l in m.group(1).splitlines() if l.startswith(">"))
    s = re.sub(r"\s+", " ", s).strip()
    ok = len(s) <= 950
    print(f"skrIntegration {tag}: {len(s)} chars {'OK' if ok else 'TOO LONG'}")
    fail |= not ok

# Deck: 15-17 slides, each with the required parts
deck = open(os.path.join(pitch, "DECK.md"), encoding="utf-8").read()
slides = re.split(r"\n## Slide \d+:", deck)[1:]
print(f"deck slides: {len(slides)}")
fail |= not (15 <= len(slides) <= 17)
for i, s in enumerate(slides, 1):
    for part in ("**Message:**", "**On slide", "**Visual:**", "**Speaker notes:**", "**Serves:**",
                 "**Sources:**"):
        if part not in s:
            print(f"slide {i} missing {part}")
            fail = True

# Judge Q&A: 20 questions
qa = open(os.path.join(pitch, "JUDGE_QA.md"), encoding="utf-8").read()
nq = len(re.findall(r"^### \d+\. ", qa, re.M))
print(f"judge questions: {nq}")
fail |= nq != 20

# X posts: 10 drafts, <= 280 weighted chars ([link] counts as 23)
bip = open(os.path.join(pitch, "BUILD_IN_PUBLIC.md"), encoding="utf-8").read()
posts = re.split(r"\n### \d+\. ", bip)[1:]
print(f"x posts: {len(posts)}")
fail |= len(posts) != 10
for i, p in enumerate(posts, 1):
    q = " ".join(re.sub(r"^> ?", "", l) for l in p.splitlines() if l.startswith("> "))
    q = re.sub(r"\s+", " ", q).strip()
    n = len(q.replace("[link]", "x" * 23))
    ok = n <= 280
    print(f"  post {i}: {n} chars {'OK' if ok else 'OVER 280'}; DRAFT label: {'DRAFT' in p.splitlines()[0]}")
    fail |= not ok or "DRAFT" not in p.splitlines()[0]

# Demo VO length
demo = open(os.path.join(pitch, "DEMO_SCRIPT.md"), encoding="utf-8").read()
vo = demo.split("## 7. VO transcript", 1)[1]
vo_text = " ".join(re.sub(r"^> ?", "", l) for l in vo.splitlines() if l.startswith(">"))
words = len(vo_text.split())
print(f"VO words: {words} (~{words / 160 * 60:.0f} s at 160 wpm)")
fail |= words > 460

print("RESULT:", "FAIL" if fail else "PASS")
sys.exit(1 if fail else 0)
