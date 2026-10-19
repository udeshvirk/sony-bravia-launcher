#!/usr/bin/env python3
"""Tiny ADB client for the TV, built on adb-shell (works around macOS Local Network blocking adb).
Usage:
  tv.py shell '<cmd>'            run one shell command
  tv.py key KEYCODE [...]        send key events
  tv.py shot out.png             screenshot to local file
  tv.py push local remote        push file (base64 chunks, md5-verified)
  tv.py pull remote local        pull a file (base64)
"""
import sys, os, base64, hashlib
from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.keygen import keygen
from adb_shell.auth.sign_pythonrsa import PythonRSASigner

HOST = os.environ.get("TV_IP")
if not HOST:
    sys.exit("Set TV_IP to your TV's address first, e.g. TV_IP=192.168.1.50 ./tv shell ...")
KEY = os.path.expanduser("~/.android/adbkey")

def connect():
    if not os.path.exists(KEY):
        os.makedirs(os.path.dirname(KEY), exist_ok=True)
        keygen(KEY)
    signer = PythonRSASigner(open(KEY + ".pub").read(), open(KEY).read())
    d = AdbDeviceTcp(HOST, 5555, default_transport_timeout_s=20.0)
    d.connect(rsa_keys=[signer], auth_timeout_s=120)
    return d

def sh(d, cmd, timeout=120):
    return d.shell(cmd, read_timeout_s=timeout, transport_timeout_s=timeout, decode=True)

def main():
    a = sys.argv[1:]
    d = connect()
    op = a[0]
    if op == "shell":
        out = sh(d, a[1], timeout=int(os.environ.get("TV_TIMEOUT", "120")))
        sys.stdout.write(out)
    elif op == "key":
        for k in a[1:]:
            sh(d, f"input keyevent {k}")
    elif op == "shot":
        sh(d, "screencap -p /data/local/tmp/s.png")
        b64 = sh(d, "base64 /data/local/tmp/s.png", timeout=60)
        open(a[1], "wb").write(base64.b64decode("".join(b64.split())))
        print("saved", a[1])
    elif op == "pull":
        b64 = sh(d, f"base64 '{a[1]}'", timeout=120)
        open(a[2], "wb").write(base64.b64decode("".join(b64.split())))
        print("pulled", a[2])
    elif op == "push":
        data = open(a[1], "rb").read()
        remote = a[2]; tmp = remote + ".b64"
        sh(d, f"rm -f '{tmp}' '{remote}'")
        enc = base64.b64encode(data).decode()
        CH = 48000
        for i in range(0, len(enc), CH):
            sh(d, f"echo -n '{enc[i:i+CH]}' >> '{tmp}'")
        sh(d, f"base64 -d '{tmp}' > '{remote}' && rm -f '{tmp}'")
        rmd5 = sh(d, f"md5sum '{remote}'").split()[0]
        lmd5 = hashlib.md5(data).hexdigest()
        print("md5", "OK" if rmd5 == lmd5 else f"MISMATCH {rmd5} != {lmd5}")
        if rmd5 != lmd5: sys.exit(1)
    d.close()

if __name__ == "__main__":
    main()
