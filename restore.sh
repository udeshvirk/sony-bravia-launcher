#!/bin/sh
# Undo everything this project changed on the TV:
#  - re-enables every debloated package, the stock Google TV home, Sony's home customiser and Google's setup app
#  - makes Google TV the default home again
#  - turns off the colour-button accessibility service (original state: accessibility off, no services)
#  - restores the screensaver settings to the values saved in the backup
# Usage: ./restore.sh              keep the custom launcher installed (but not default)
#        ./restore.sh --uninstall  also uninstall it
cd "$(dirname "$0")"
B=$(ls -d backup/*/ | head -1)
for p in $(grep -vE '^\s*(#|$)' debloat-list.txt) com.sony.dtv.launcherxcustomizer com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith; do
  printf '%-45s ' "$p"; ./tv shell "pm enable --user 0 $p 2>&1" | tr -d '\n'; echo
done
./tv shell "cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity"

# accessibility: back to the original (off, nothing enabled)
./tv shell "settings delete secure enabled_accessibility_services"
./tv shell "settings put secure accessibility_enabled 0"

# screensaver settings from the backup (unchanged by this project, restored for safety)
for k in screensaver_components screensaver_enabled screensaver_activate_on_sleep screensaver_activate_on_dock screensaver_default_component sleep_timeout attentive_timeout; do
  v=$(grep "^$k=" "$B/settings-secure.txt" | cut -d= -f2-)
  [ -n "$v" ] && [ "$v" != null ] && ./tv shell "settings put secure $k '$v'"
done
v=$(grep "^screen_off_timeout=" "$B/settings-system.txt" | cut -d= -f2-)
[ -n "$v" ] && ./tv shell "settings put system screen_off_timeout $v"

[ "$1" = "--uninstall" ] && ./tv shell "pm uninstall com.udesh.tvhome"
./tv shell "input keyevent KEYCODE_HOME"
echo "Restored. Original package lists and settings are in $B"
