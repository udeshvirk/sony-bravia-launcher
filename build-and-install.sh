#!/bin/sh
# Build the home screen APK, install it on the TV and make it the default home.
# Requires TV_IP to be set (see README).
set -e
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
( cd launcher && ./gradlew -q assembleDebug )
APK=launcher/app/build/outputs/apk/debug/app-debug.apk
./tv push "$APK" /data/local/tmp/tvhome.apk          # base64 chunks + md5 check
./tv shell "pm install -r -t /data/local/tmp/tvhome.apk && rm /data/local/tmp/tvhome.apk"
# Wi-Fi name on the Wi-Fi pill needs location permission (SSID only; weather never uses it)
./tv shell "pm grant com.udesh.tvhome android.permission.ACCESS_FINE_LOCATION" || true
# Default home. Google's setup app has a higher-priority HOME screen, so it must stay disabled.
./tv shell "pm disable-user --user 0 com.google.android.apps.tv.launcherx"
./tv shell "pm disable-user --user 0 com.sony.dtv.launcherxcustomizer"
./tv shell "pm disable-user --user 0 com.google.android.tungsten.setupwraith"
./tv shell "cmd package set-home-activity com.udesh.tvhome/.HomeActivity"
# Apps button: lets the colour-button service see the system "all apps" request (see README)
./tv shell "pm grant com.udesh.tvhome android.permission.READ_LOGS" || true
# Colour-button service
./tv shell "settings put secure enabled_accessibility_services com.udesh.tvhome/.ColorKeyService"
./tv shell "settings put secure accessibility_enabled 1"
# (no Home press here: it would hide the one-time "Allow USB debugging?" prompt)
echo "Installed."
