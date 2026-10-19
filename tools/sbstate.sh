#!/bin/sh
cd "$(dirname "$0")/.."
./tv shell 'dumpsys hdmi_control 2>&1 | grep -E "mArcEstablished|mSystemAudioActivated|logical_address: 0x05" | sed -E "s/.*(mArcEstablished: [a-z]+|mSystemAudioActivated: [a-z]+|display_name: [^ ]+ [^ ]+ power_status: [0-9]).*/\1/" | sort -u | tr "\n" " "; echo; dumpsys hdmi_control 2>&1 | grep -E "\[(S|R)\]" | tail -4 | cut -c1-110'
