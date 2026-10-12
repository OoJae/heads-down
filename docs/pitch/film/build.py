#!/usr/bin/env python3
"""Builds the HyperFrames composition from template.html, timed to a voice recording.

Usage: build.py <voice recording> [--no-transcribe]

1. Transcribes the recording with whisper.cpp (word times) and finds, in it, the start of each of
   the script's nine paragraphs and of some forty phrases inside them (the cues).
2. Lays the phone's screen shots on the timeline against those cues.
3. Writes ../hf/index.html, ../hf/assets/vo.m4a, timings.json, and the caption file.
"""
import json
import os
import re
import shutil
import subprocess
import sys

F = os.path.dirname(os.path.abspath(__file__))
HF = os.path.join(os.path.dirname(F), "hf")
MODEL = os.path.expanduser("~/.cache/hyperframes/whisper/models/ggml-small.en.bin")
audio = sys.argv[1]
TAIL = 3.4


def run(*a):
    return subprocess.run(a, check=True, capture_output=True, text=True)


def norm(w):
    return re.sub(r"[^a-z0-9]", "", w.lower())


# ---- the script: the captions' spelling, and what the voice was given to read
SCRIPT = [p.strip() for p in open(os.path.join(F, "script.txt"), encoding="utf-8").read().strip().split("\n\n")]
READ = [p.strip() for p in open(os.path.join(F, "voice-over-for-clipchamp.txt"), encoding="utf-8").read().strip().split("\n\n")]
assert len(SCRIPT) == 9 and len(READ) == 9

# ---- transcribe
wav = os.path.join(F, "vo.wav")
if "--no-transcribe" not in sys.argv:
    run("ffmpeg", "-v", "error", "-y", "-i", audio, "-ar", "16000", "-ac", "1", wav)
    run("whisper-cli", "-m", MODEL, "-f", wav, "-ml", "1", "-sow", "-oj", "-of", os.path.join(F, "vo"), "-np")
total = float(run("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", wav).stdout)
tr = json.load(open(os.path.join(F, "vo.json"), encoding="utf-8"))["transcription"]
W = [(norm(t["text"]), t["offsets"]["from"] / 1000.0, t["offsets"]["to"] / 1000.0, t["text"].strip()) for t in tr if norm(t["text"])]
open(os.path.join(F, "vo-heard.txt"), "w", encoding="utf-8").write(" ".join(w[3] for w in W) + "\n")
sd = subprocess.run(["ffmpeg", "-hide_banner", "-i", wav, "-af", "silencedetect=noise=-34dB:d=0.3", "-f", "null", "-"], capture_output=True, text=True).stderr
pauses = list(zip([float(x) for x in re.findall(r"silence_start: ([\d.]+)", sd)], [float(x) for x in re.findall(r"silence_end: ([\d.]+)", sd)]))
print("recording %.1f s · %d words heard · %d pauses" % (total, len(W), len(pauses)))


def find(keys, lo, hi, expect=None):
    """Index in W[lo:hi] where one of the word sequences starts; nearest to `expect` if given."""
    hits = []
    for key in keys:
        k = [norm(x) for x in key.split()]
        for j in range(lo, max(lo, hi - len(k) + 1)):
            if all(W[j + n][0] == k[n] or (len(k[n]) > 4 and W[j + n][0].startswith(k[n][:5])) for n in range(len(k))):
                hits.append(j)
    if not hits:
        return None
    return min(hits, key=lambda j: abs(j - expect)) if expect is not None else min(hits)


def snap(t, reach=0.6):
    near = [e for (a, e) in pauses if abs(e - t) <= reach]
    return min(near, key=lambda e: abs(e - t)) if near else t


# ---- paragraph starts
OPEN = [["every night"], ["heads down is"], ["what is new"], ["here is a real"], ["s k r is", "skr is", "skr is the"], ["and you come"],
        ["heads down is plain"], ["all of it has"], ["heads down put", "heads down"]]
counts = [len(p.split()) for p in READ]
idx = [0]
for i in range(1, 9):
    expect = idx[-1] + counts[i - 1]
    j = find(OPEN[i], max(0, expect - 25), min(len(W), expect + 25), expect)
    if j is None:
        j = min(len(W) - 1, expect)
        print("paragraph %d: its opening was not heard; placed by the word count" % (i + 1))
    idx.append(j)
idx.append(len(W))
S = [None] + [0.0 if i == 0 else round(snap(W[idx[i]][1]) - 0.15, 3) for i in range(9)]
END = round(min(total, W[-1][2]) + TAIL, 3)

# ---- cues: phrase -> time, searched inside its paragraph; the fallback is the phrase's place in the script
CUES = {
    "c1_hd": (1, ["heads down gives", "heads down"], "Heads Down gives"),
    "c2_powered": (2, ["powered by", "powered"], "powered by"), "c2_plug": (2, ["plug in"], "Plug in"), "c2_alarm": (2, ["set your alarm"], "set your alarm"),
    "c2_clock": (2, ["clock in"], "clock in"), "c2_lay": (2, ["lay the phone", "lay the"], "lay the phone"),
    "c2_digs": (2, ["while it stays", "while it"], "While it stays"), "c2_inside": (2, ["inside limits", "inside"], "inside limits"),
    "c2_pick": (2, ["pick it up", "pick it"], "Pick it up"),
    "c3_once": (3, ["once a minute", "once a"], "Once a minute"), "c3_sol": (3, ["solana checks", "solana"], "Solana checks"),
    "c3_same": (3, ["in the same", "the same transaction", "same"], "in the same"), "c3_no": (3, ["no server"], "No server"),
    "c3_not": (3, ["not even"], "Not even"), "c3_lift": (3, ["lift the phone", "lift the"], "Lift the phone"),
    "c3_signs": (3, ["it signs a break", "signs a break", "signs"], "it signs a break"), "c3_stop": (3, ["the digging stops", "digging stops", "digging"], "The digging stops"),
    "c4_main": (4, ["solana"], "Solana mainnet"), "c4_date": (4, ["the 12th", "the twelfth", "12th", "twelfth"], "the twelfth"),
    "c4_redmi": (4, ["on a redmi", "redmi", "on a"], "on a Redmi"),
    "c4_41": (4, ["forty one", "fortyone", "41 minutes", "41", "forty"], "Forty-one"), "c4_37": (4, ["thirty seven", "thirtyseven", "37 rounds", "37", "thirty"], "Thirty-seven"),
    "c4_5": (4, ["five digs", "5 digs", "five"], "Five digs"), "c4_one": (4, ["one approval"], "One approval"),
    "c4_haul": (4, ["then the haul", "the haul", "haul"], "Then the haul"), "c4_price": (4, ["the price paid", "price paid", "price"], "the price paid"),
    "c4_beside": (4, ["beside the market", "beside"], "beside the market"),
    "c5_locked": (5, ["were locked", "locked"], "were locked"), "c5_done": (5, ["it completed", "completed"], "It completed"),
    "c5_back": (5, ["came back", "came"], "came back"), "c5_lost": (5, ["a lost bond", "lost bond"], "A lost bond"),
    "c5_rule": (5, ["by the program", "by the"], "By the program"),
    "c6_every": (6, ["every completed", "every"], "Every completed"), "c6_streak": (6, ["a streak", "streak"], "a streak"),
    "c6_two": (6, ["two nights", "2 nights"], "two nights"), "c6_nights": (6, ["the nights screen", "nights screen"], "The Nights screen"),
    "c6_share": (6, ["a share card", "share card", "share"], "a share card"),
    "c7_mining": (7, ["mining is one", "mining is", "mining"], "Mining is one"), "c7_not": (7, ["not income", "income"], "not income"),
    "c7_rig": (7, ["the rig digs only", "the rig digs", "rig digs"], "The rig digs only"), "c7_cost": (7, ["cost figure", "cost"], "cost figure"),
    "c7_ceiling": (7, ["price ceiling", "ceiling"], "price ceiling"), "c7_that": (7, ["that shifts digs", "that shift", "that shifts"], "That shift's digs"),
    "c7_market": (7, ["against a market", "market price", "against"], "against a market"), "c7_first": (7, ["first twenty", "first 20", "first"], "first twenty"),
    "c7_buy": (7, ["and the clock out", "and the clockout", "the clock out", "clockout can", "clock out can"], "And the clock-out"),
    "c8_ten": (8, ["ten shifts", "10 shifts"], "ten shifts"), "c8_35": (8, ["thirty five digs", "thirtyfive digs", "35 digs", "thirty five", "35"], "thirty-five digs"),
    "c8_every": (8, ["every transaction"], "every transaction"),
    "c9_hd": (9, ["heads down"], "Heads Down"), "c9_put": (9, ["put it down", "put it"], "Put it down"),
    "c9_digs": (9, ["it digs", "digs"], "It digs"), "c9_pow": (9, ["powered by", "powered"], "Powered by"),
}
C, missed = {}, []
for name, (p, keys, phrase) in CUES.items():
    lo, hi = idx[p - 1], idx[p]
    after = lo + (2 if name == "c1_hd" else 0)  # in paragraph 1, "Heads Down" is its second sentence
    words = SCRIPT[p - 1].split()
    pos = next((n for n in range(len(words)) if " ".join(words[n:n + len(phrase.split())]).lower().startswith(phrase.lower()[:8])), 0)
    frac = pos / max(1, len(words))
    expect = lo + frac * (hi - lo)
    j = find(keys, after, hi, expect)
    if j is None:
        missed.append(name)
        t = S[p] + frac * ((S[p + 1] if p < 9 else END - TAIL) - S[p])
    else:
        t = W[j][1]
    C[name] = round(t, 3)
if missed:
    print("placed by position, not heard:", ", ".join(missed))
# keep every scene's cues in order, and no closer than a third of a second
ORDER = [n for n in CUES]
for p in range(1, 10):
    names = [n for n in ORDER if CUES[n][0] == p]
    floor = S[p] + 0.5
    for n in names:
        C[n] = round(max(C[n], floor), 3)
        floor = C[n] + 0.35

# ---- the phone's screen: which shot, from when, for how long
A = os.path.join(HF, "assets")
LEN = {f[:-4]: float(run("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", os.path.join(A, f)).stdout) - 0.12
       for f in os.listdir(A) if f.endswith(".mp4") and not f.startswith("vo")}
screens = []


def show(asset, start, until, offset=0.0):
    """A shot from `start` to `until`; a video that is shorter than its slot is followed by its last frame."""
    start, until = round(start, 3), round(until, 3)
    if until - start < 0.2:
        return
    if asset.endswith(".png"):
        screens.append(("img", asset, start, round(until - start, 3)))
        return
    d = min(LEN[asset] - offset, until - start)
    screens.append(("video", asset + ".mp4", start, round(d, 3), offset))
    if until - (start + d) > 0.05:
        screens.append(("img", asset + "-last.png", round(start + d, 3), round(until - start - d, 3)))


s = S
# 2 · home, the wallet's sheet for the clock-in, armed; then (after lying down) cooling, and cold
ck = C["c2_clock"] - 0.3
show("home", s[2] - 0.2, ck)
show("signsheet", ck, ck + 1.9)
show("armed", ck + 1.9, C["c2_digs"] + 0.6)
show("cold", C["c2_pick"] - 0.6, S[3] + 0.6, 1.8)  # its first seconds repeat the frame before the pickup
# 3 · the break: the home screen of the lifted shift
show("breaksheet", C["c3_lift"] - 0.6, S[4] + 0.75)
# 4 · the morning of shift 10
t0 = S[4] + 0.75
show("notsealed", t0, C["c4_41"] + 1.0)
a = C["c4_one"] - 0.25
show("sheet", C["c4_41"] + 1.0, a)
show("wallet", a, a + 1.6)
h = max(a + 2.6, C["c4_haul"] - 0.2)
show("confirmed", a + 1.6, h)
n4 = max(h + 2.0, C["c4_beside"] - 0.3)
show("haul", h, n4)
show("numbers", n4, S[5] + 0.3)
flag4, flag4d = a, 1.5
# 5 · the bond
show("bond-chosen.png", S[5] + 0.3, C["c5_locked"] - 0.4)
show("bond-wallet-a.png", C["c5_locked"] - 0.4, C["c5_back"] - 0.6)
show("bond-confirmed.png", C["c5_back"] - 0.6, S[6] + 0.3)
# 6 · the streak, Nights
show("streak", S[6] + 0.3, C["c6_nights"] - 0.7)
n0 = C["c6_nights"] - 0.7
show("nights", n0, n0 + 4.2)
show("nightslist", n0 + 4.2, S[7] + 0.3)
# 7 · the gate line, the price row, the purchase
show("home-gate-redmi.png", S[7] + 0.3, C["c7_that"] - 0.4)
show("bond-haul-b.png", C["c7_that"] - 0.4, C["c7_buy"] - 0.6)
b0 = C["c7_buy"] - 0.6
show("buy", b0, b0 + 3.4)
show("buywallet", b0 + 3.4, b0 + 5.6)
show("buyok", b0 + 5.6, S[8] + 0.2)
screens.sort(key=lambda x: x[2])
html = []
for n, item in enumerate(screens):
    kind, src, st, du = item[:4]
    if kind == "video":
        off = ' data-media-start="%.3f"' % item[4] if item[4] else ""
        html.append('          <video id="scr%d" class="clip" src="assets/%s" data-start="%.3f" data-duration="%.3f"%s data-track-index="1" muted playsinline></video>' % (n, src, st, du, off))
    else:
        html.append('          <img id="scr%d" class="clip" src="assets/%s" alt="" data-start="%.3f" data-duration="%.3f" data-track-index="1" />' % (n, src, st, du))

# ---- generated rows
board = "".join('<i id="bc%d"><u></u></i>' % n for n in range(25))
ticks = "".join('<i class="%s"><u></u></i>' % ("dig" if n < 5 else "") for n in range(37))
cal = "".join('<i class="today"><u></u></i>' if n == 29 else "<i></i>" for n in range(30))
grid = "kbkkb bbykk kbkbb kbokb bkkby".replace(" ", "")
share = "".join('<i class="%s"></i>' % ("" if c == "k" else c) for c in grid)
LEDGER = [
    ("01:09:17", "3Ngnxsv2…JJgf", "clock-in · the night of 12 October"),
    ("01:10:50", "2sx1jTmR…i2TQ", "dig · round 436,943"),
    ("04:01:17", "4VtssJM3…HKFo", "heartbeat recorded on-chain"),
    ("04:08:36", "3TBjJwzz…ADmW", "break · the charger lost power"),
    ("14:22:35", "61GTXAMr…zfWsT", "clock-in · shift 9"),
    ("14:23:28", "2eiHdtH8…wAxD", "dig · round 437,693"),
    ("14:30:28", "33aAQgcb…LogeB", "break · the phone was unlocked"),
    ("15:03:51", "4QSDYqcZ…JzvuR", "clock-in · 10 SKR locked"),
    ("15:05:16", "3naKDhs4…QpBC", "dig · round 437,729"),
    ("15:06:10", "4MfN3mcU…TDk5", "dig · round 437,730"),
    ("15:07:15", "2yv8Cs6P…urna", "dig · round 437,731"),
    ("15:44:21", "3NDz7zPA…rKJZ", "heartbeat recorded · dark round 37"),
    ("15:52:59", "BaiMVwRK…7Lvdt", "clock-out · completed · 10 SKR back"),
    ("15:58:25", "JJsQ4SXr…2Aom6", "0.001 SOL to ORE, through Jupiter"),
]
ledger = "".join("<p><b>%s</b><u>%s</u><span>%s</span></p>" % r for r in LEDGER)

T = {"s": S, "c": C, "end": END, "x": {"flag2": round(ck + 0.1, 3), "flag2d": 1.7, "flag4": round(flag4, 3), "flag4d": flag4d, "ledgerScroll": len(LEDGER) * 60 - 560}}
D = [None] + [round((S[i + 1] if i < 9 else END) - S[i], 3) for i in range(1, 10)]
D[3] = round(D[3] + 0.9, 3)  # "The digging stops." stays a beat into the next scene
out = open(os.path.join(F, "template.html"), encoding="utf-8").read()
rep = {"DUR": "%.3f" % END, "SCREENS": "\n".join(html), "BOARD": board, "TICKS": ticks, "CAL": cal, "SHARE": share, "LEDGER": ledger,
       "AUDIO": "vo.m4a", "TJSON": json.dumps(T)}
for i in range(1, 10):
    rep["S%d" % i] = "%.3f" % S[i]
    rep["D%d" % i] = "%.3f" % D[i]
for k, v in rep.items():
    out = out.replace("{{%s}}" % k, v)
assert "{{" not in out, re.findall(r"\{\{[^}]+\}\}", out)[:5]
open(os.path.join(HF, "index.html"), "w", encoding="utf-8").write(out)
run("ffmpeg", "-v", "error", "-y", "-i", audio, "-af", "highpass=f=70,loudnorm=I=-16:TP=-1.5:LRA=11,apad=pad_dur=%.2f" % (TAIL + 0.5),
    "-t", "%.3f" % END, "-c:a", "aac", "-b:a", "192k", os.path.join(A, "vo.m4a"))
json.dump(T, open(os.path.join(F, "timings.json"), "w"), indent=1)

# ---- captions: the script's own spelling, each sentence timed inside its paragraph
def ts(t):
    h, r = divmod(max(0.0, t), 3600); m, sec = divmod(r, 60)
    return ("%02d:%02d:%06.3f" % (h, m, sec)).replace(".", ",")


cues = []
for i, p in enumerate(SCRIPT):
    a0 = W[idx[i]][1]
    b0 = (W[idx[i + 1]][1] - 0.3) if i < 8 else W[-1][2] + 0.3
    sents = [x.strip() for x in re.split(r"(?<=[.:;?!])\s+", p) if x.strip()]
    long = []
    for x in sents:
        while len(x) > 92 and ", " in x:
            cuts = [m.start() for m in re.finditer(", ", x)]
            c = min(cuts, key=lambda k: abs(k - len(x) / 2))
            long.append(x[: c + 1]); x = x[c + 2:]
        long.append(x)
    merged = []
    for x in long:
        if merged and len(merged[-1]) < 26:
            merged[-1] += " " + x
        else:
            merged.append(x)
    chars = sum(len(x) for x in merged)
    t = a0
    for x in merged:
        d = (b0 - a0) * len(x) / chars
        cues.append((t, t + d - 0.06, x)); t += d
open(os.path.join(F, "heads-down-film.srt"), "w", encoding="utf-8").write(
    "".join("%d\n%s --> %s\n%s\n\n" % (n, ts(x), ts(y), z) for n, (x, y, z) in enumerate(cues, 1)))
open(os.path.join(F, "heads-down-film-transcript.txt"), "w", encoding="utf-8").write("\n\n".join(SCRIPT) + "\n")
print("scenes:", " ".join("%d@%.1f" % (i, S[i]) for i in range(1, 10)), "end %.1f" % END)
print("%d screen shots · %d captions" % (len(screens), len(cues)))
