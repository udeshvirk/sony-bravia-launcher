# Working on this repo (notes for coding agents)

Sony BRAVIA (Android TV 12) debloat + a custom home screen app, `com.udesh.tvhome`.
`README.md` = full reference per feature; `PROMPT.md` = setup runbook for a new TV.
Local checkout: `~/Projects/TV/sony-tv-launcher` (moved from `~/tv-launcher` on 2026-10-23);
sibling LG projects and workspace notes are in `~/Projects/TV/` (see `../CLAUDE.md`).

## Never commit
- `config.json` — the owner's settings pushed to the TV (location, kids PIN, kids name).
- `launcher/local.properties` — Android SDK path.
- `shots/` — TV screenshots (can contain family photos).
Both files are in `.gitignore`; before every commit, `git diff --cached` must not contain the PIN
or coordinates. `config.xml` / `config.example.json` keep neutral defaults
(weather 0,0, empty PIN) on purpose — the repo is public.

## Build, install, configure
```sh
export TV_IP=<tv ip>              # find it: scan the LAN for port 5555 if unknown
./build-and-install.sh            # gradle assembleDebug + install + set home + enable a11y service
./config.sh push                  # push config.json (keys = config.xml names) and restart the app
```
JDK 17 at `/opt/homebrew/opt/openjdk@17`. `./tv` is a pure-Python ADB client (macOS blocks `adb`).
Read settings through `Config.str/bool/integer/array(ctx, R.x.y)`, never `getResources()`
directly, so `config.json` can override them.

## Layout of the app (launcher/app/src/main/java/com/udesh/tvhome)
- `HomeActivity` — the whole home screen, built in code (no XML layouts). Zones TOP/DOCK/GRID/MENU.
- `AppRepo` — dock/grid lists (kids mode filters to `kids_apps`).
- `ColorKeyService` — accessibility service: colour keys, Apps button (READ_LOGS watcher),
  soundbar wake (`SoundbarWaker`, TV's own adbd, one fixed command), screensaver score overlay
  (`ScreensaverOverlay`), kids-mode lock (sends non-allowed foreground apps Home, swallows
  Settings/Input/Assistant keys).
- `Cricket` + `CricketCard` — India/IPL score from ESPN's public scoreboard (no key; soft failures).
- `Kids` + `PinPad` — kids mode state, allow-list, PIN with lockout.
- `Weather`, `Config`, `Fonts`; optional `ChomperView`/`MazeBorderView` (arcade look, off).

## Testing gotchas
- `input keyevent` reaches activities but **skips the accessibility key filter** — colour keys,
  Settings (`KEYCODE_BUTTON_2`), Input, Assistant must be tested by the human on the real remote.
- After `am force-stop com.udesh.tvhome` the a11y service only comes back when
  `enabled_accessibility_services` is set to `''` and back.
- SharedPreferences are cached per process: to inject test data (e.g. a sample cricket `card`),
  force-stop first, write via `run-as`, then rebind the service.
- `setupwraith` and `launcherx` must stay disabled for user 0 (setupwraith's RecoveryActivity
  takes over HOME). Enabling either briefly for a test: re-run
  `cmd package set-home-activity com.udesh.tvhome/.HomeActivity` and tell the human not to press Home.
- Don't press Home right after an install (hides a pending "Allow USB debugging?" prompt).
- Network debugging stays on (the soundbar wake needs it) — don't suggest turning it off.
- The owner rejected: a Settings-button quick panel, a custom sleep timer, Continue-watching rows,
  and the arcade look as default. Prefer features that cost no RAM/CPU when idle.
