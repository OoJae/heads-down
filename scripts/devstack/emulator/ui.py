#!/usr/bin/env python3
"""Read and tap an Android screen through adb (uiautomator dump). Used by emulator-smoke.sh.

  ui.py texts                 every text on screen, top to bottom
  ui.py tap <text> [exact]    tap the first node whose text contains (or equals) <text>,
                              scrolling down to look for it
  ui.py find <text>           print the first matching text, exit 1 if there is none
  ui.py has <text> [exact]    exit 0 if it is on screen right now (no scrolling), else 1
  ui.py swipe up|down

ADB (default "adb") and ANDROID_SERIAL choose the device.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB", "adb")


def adb(*args):
    return subprocess.run([ADB, *args], capture_output=True, text=True).stdout


def nodes():
    for _ in range(4):
        adb("shell", "uiautomator", "dump", "/sdcard/hd-ui.xml")
        xml = adb("exec-out", "cat", "/sdcard/hd-ui.xml")
        if xml.lstrip().startswith("<?xml"):
            break
        time.sleep(1)
    else:
        sys.exit("could not read the screen (uiautomator dump)")
    out = []
    for n in ET.fromstring(xml).iter("node"):
        text = n.get("text") or n.get("content-desc") or ""
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if text and m:
            x1, y1, x2, y2 = map(int, m.groups())
            out.append((text, (x1 + x2) // 2, (y1 + y2) // 2))
    return out


def matches(text, exact):
    return [n for n in nodes() if (n[0] == text if exact else text.lower() in n[0].lower())]


def screen():
    """(width, height) in pixels: the override if one is set, else the physical size."""
    sizes = re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    return tuple(map(int, sizes[-1])) if sizes else (1080, 2400)


def swipe(direction):
    # Relative to the screen: a 720x1640 phone has no pixel at the y an emulator's 2400 has.
    w, h = screen()
    low, high = str(h * 78 // 100), str(h * 33 // 100)
    a, b = (low, high) if direction == "up" else (high, low)
    adb("shell", "input", "swipe", str(w // 2), a, str(w // 2), b, "250")
    time.sleep(0.8)


def main():
    cmd = sys.argv[1]
    if cmd == "texts":
        for text, _, y in nodes():
            print(f"{y:5d}  {text}")
    elif cmd == "swipe":
        swipe(sys.argv[2])
    elif cmd == "has":
        sys.exit(0 if matches(sys.argv[2], len(sys.argv) > 3 and sys.argv[3] == "exact") else 1)
    elif cmd in ("tap", "find"):
        text, exact = sys.argv[2], len(sys.argv) > 3 and sys.argv[3] == "exact"
        for _ in range(6):
            hit = matches(text, exact)
            if hit:
                if cmd == "tap":
                    adb("shell", "input", "tap", str(hit[0][1]), str(hit[0][2]))
                    time.sleep(0.3)
                print(hit[0][0])
                return
            swipe("up")
        sys.exit(f"'{text}' is not on screen")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
