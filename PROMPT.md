# Setting this up for someone, as an agent

This file is for an AI coding agent (Claude Code or similar) doing the setup on behalf of a human
who owns a Sony Android TV — not for the human to read command-by-command. `README.md` is the
full reference; this is the runbook. Work from the human's confirmations at each checkpoint below,
not just command exit codes — a few steps need a real remote in their hand or a glance at the TV.

## 0. Ask first

Before touching anything, get from the human:
- Their TV's IP address (**Settings → Network → Status** on the TV), or offer to help them find it
  (if they know their router's subnet, `nmap -sn <subnet>` or a port scan on 5555 narrows it down).
- Which 4 apps they want docked, and whether they want the Red/Green/Yellow/Blue remote buttons
  mapped to specific apps (ask which, if so).
- Their city, for the weather widget (or skip it — it defaults off until coordinates are set).
- Optional extras: live cricket (India/IPL; ESPN's public feed, no key needed),
  kids mode (which apps a child may use, a 4-digit parent PIN, the child's name), the arcade look
  (off by default).
- Confirm this is **their own TV** and they're fine with disabling ~20 Sony/Google system packages
  (all reversible — see `restore.sh`) and installing a replacement home screen app.

## 1. One-time device prep (human does this with the remote)

Tell them to, on the TV:
1. **Settings → System → About → Build** — tap it ~7 times to unlock Developer options.
2. **Settings → System → Developer options → Network debugging** — turn on.

You cannot do this step for them; it requires the physical remote. Wait for confirmation before continuing.

## 2. Environment

```sh
git clone <repo-url> && cd sony-bravia-launcher
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
export TV_IP=<their TV's IP>          # every ./tv / ./build-and-install.sh / ./restore.sh call needs this
```

Also needed: JDK 17 (`brew install openjdk@17` on macOS) and an Android SDK (platform 34,
build-tools 34) with its path in `launcher/local.properties` as `sdk.dir=/path/to/android-sdk`.
If they don't have one, `sdkmanager --install "platforms;android-34" "build-tools;34.0.0"` after
installing Android cmdline-tools works, or point at an existing Android Studio SDK install.

Sanity check the connection before going further:

```sh
./tv shell 'echo ok'
```

First run triggers an **"Allow USB debugging?"** prompt on the TV screen — the human must accept
it there (point this out to them; it only happens once per key).

## 3. Debloat

```sh
./debloat.sh
```

Reversible any time with `./restore.sh`. Walk them through `debloat-list.txt` if they want to
opt any specific package back in before running it (comment out or remove its line).

## 4. Configure the home screen

Personal values go in **`config.json`** (git-ignored; start from `config.example.json`, same keys
as `config.xml`, omit anything unchanged) and are pushed with `./config.sh push` — no rebuild,
and nothing personal (location, PIN) ends up in the repo. `config.xml` only holds safe defaults.

Keys, per their answers from step 0:
- `dock_apps`: 4 package names. Find them with `./tv shell 'pm list packages | grep -i <name>'`
  (e.g. `netflix`, `amazonvideo`, `hotstar`, `youtube`).
- `key_red` / `key_green` / `key_yellow` / `key_blue`: package names, or leave empty.
- `weather_lat` / `weather_lon`: their city's coordinates (search "`<city>` latitude longitude"),
  or leave at `0.0`/`0.0` to keep the weather row hidden.
- `kids_apps`, `kids_pin` (exactly 4 digits; empty hides kids mode), `kids_name`.
- `cricket_enabled`, `cricket_india_women`, `screensaver_overlay`, `arcade_touches`.
- Everything else (`hidden_apps`, `wallpaper_rotate_hours`, etc.) — defaults are fine; see
  README.md § "Change the home screen" for what each one does.

## 5. Build and install

```sh
./build-and-install.sh
```

This builds the APK, installs it, makes it the default home screen, and turns on the colour-key
accessibility service. **Do not press Home right after this** — it hides the one-time ADB
authorization prompt if one is still pending.

## 6. Verify with the human, not with injected keys

`input keyevent` from ADB **cannot** test D-pad/colour-key behavior — injected keys skip the
accessibility-service filtering the launcher depends on. Ask the human to check, on the real remote:
- The dock and grid look right, focus moves with the D-pad.
- Colour buttons open the apps they expect (if configured).
- Long-press OK on a tile enters reorder mode (gold highlight); D-pad swaps it; OK or Back drops it.
- Pressing Home twice quickly while on the home screen relaunches the last app opened.
- The **Apps** button (if their remote has one) opens the grid.

If a soundbar is connected over HDMI-ARC, confirm it wakes when media starts playing after a
period of silence (see README.md § "Soundbar wake-up" — needs network debugging to stay on).

If kids mode is configured: Kids pill → only the kids apps; on the real remote the Settings,
Input and Assistant buttons do nothing, other apps/Settings bounce back home; Parent pill + PIN
leaves. (Injected keys can't test the blocked buttons, same as above.) Profiles *inside*
Netflix/Prime/Hotstar are the apps' own business — point them to each app's kids profile and PIN.

What you *can* check from ADB: screenshots (`./tv shot out.png`), `logcat -s TvHomeKeys TvHomeSaver
TvHomeConfig`, and the launcher's state via `run-as com.udesh.tvhome cat shared_prefs/<name>.xml`.
To preview the cricket card without a live match, write a sample `card` JSON into
`shared_prefs/cricket.xml` with run-as.

## 7. If something's wrong

- Wrong app in the dock/grid, wrong colour mapping: edit `config.json`, `./config.sh push`.
- `./tv` connection refused after a TV restart: the TV may have a new IP (e.g. Wi-Fi instead of
  Ethernet) — scan the subnet for port 5555 before blaming network debugging.
- Colour-key service dead after `am force-stop com.udesh.tvhome`: it only rebinds when
  `enabled_accessibility_services` is set to `''` and back (`./config.sh push` does this).
- Photo screensaver black: Google's Ambient Mode problem, not ours — `pm trim-caches 999G`,
  force-stop `com.google.android.apps.tv.dreamx` and `com.google.android.apps.mediashell`, else
  re-link the album in the Google Home app.
- Sony picture/sound settings are signature-locked (no `settings`/content provider access); the
  only way is driving the menu with `input keyevent` while the human has it open, screenshot
  after every step, and never pressing Left/Right on an item you haven't read.
- Want the stock Sony/Google launcher back: `./restore.sh` (keeps the custom launcher installed,
  just stops using it) or `./restore.sh --uninstall` (removes it too).
- Anything else: README.md has a dedicated section per feature with the mechanism and known
  limitations (what was tried and didn't work on Android 12, etc.) — read the relevant one before
  improvising a fix, since several things here work around Android/Sony restrictions in
  non-obvious ways.
