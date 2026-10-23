#!/bin/sh
# Change home-screen settings without rebuilding.
#   ./config.sh push   send config.json to the TV and restart the launcher (keys = config.xml names;
#                      anything left out uses the built-in default from config.xml)
#   ./config.sh pull   fetch the TV's current config.json into config.json
#   ./config.sh reset  delete it from the TV (back to the built-in defaults)
# Requires TV_IP (see README). Start from config.example.json.
set -e
cd "$(dirname "$0")"
REMOTE=/sdcard/Android/data/com.udesh.tvhome/files/config.json
SVC=com.udesh.tvhome/.ColorKeyService
restart() {
  # restart the launcher and re-bind the colour-button service so both re-read the settings
  ./tv shell "am force-stop com.udesh.tvhome; settings put secure enabled_accessibility_services ''; \
    settings put secure enabled_accessibility_services $SVC; settings put secure accessibility_enabled 1; \
    am start -n com.udesh.tvhome/.HomeActivity >/dev/null"
}
case "$1" in
  push)
    python3 -c 'import json,sys; json.load(open("config.json"))' || { echo "config.json is not valid JSON"; exit 1; }
    ./tv shell "mkdir -p /sdcard/Android/data/com.udesh.tvhome/files"
    ./tv push config.json "$REMOTE"
    restart; echo "Pushed and restarted." ;;
  pull)  ./tv pull "$REMOTE" config.json && echo "Saved to config.json." ;;
  reset) ./tv shell "rm -f $REMOTE"; restart; echo "Back to built-in defaults." ;;
  *) sed -n '2,8p' "$0"; exit 1 ;;
esac
