#!/usr/bin/env python3
"""Wait for each colour-key launch in logcat, then screenshot the app it opened."""
import subprocess, sys, time, os
os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
names = ["red", "green", "yellow", "blue"]
slots = [int(a) for a in sys.argv[1:]] or [0, 1, 2, 3]
tv = lambda *a: subprocess.run(["./tv", *a], capture_output=True, text=True).stdout
tv("shell", "logcat -c")
for s in slots:
    t0 = time.time(); hit = None
    while time.time() - t0 < 120:
        out = tv("shell", "logcat -d -s TvHomeKeys:I")
        lines = [l for l in out.splitlines() if f"key {s} " in l]
        if lines: hit = lines[-1]; break
        time.sleep(1.5)
    print(names[s], "->", hit or "NOT PRESSED within 120 s", flush=True)
    if hit and "->" in hit:
        time.sleep(7)
        tv("shot", f"shots/50-{names[s]}.png")
        print(tv("shell", "dumpsys activity activities | grep -m1 mResumedActivity").strip(), flush=True)
