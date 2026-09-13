#!/bin/bash

# Headless: there is no display server in this image. shotserver.py serves the screen over adb instead
/sdk/emulator/emulator -avd agenttest -no-window -no-audio -no-boot-anim \
  -gpu swiftshader_indirect -no-snapshot -ports 5554,5555 &

/sdk/platform-tools/adb wait-for-device
until [ "$(/sdk/platform-tools/adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
sleep 3

# The emulator binds its adb port to loopback, so a published -p 5555:5555 forwards to an
# address inside the container that nothing is listening on. Republish it on the container's
# own interface, which is the one docker actually forwards to
socat TCP-LISTEN:5555,bind="$(hostname -i)",fork,reuseaddr TCP:127.0.0.1:5555 &

# Foreground: this is what keeps the container alive
python3 /shotserver.py
