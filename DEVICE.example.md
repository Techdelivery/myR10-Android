# R10 device identity — TEMPLATE

Copy this file to `DEVICE.local.md` and fill in your unit's values:

    cp DEVICE.example.md DEVICE.local.md

`DEVICE.local.md` is gitignored (`*.local.md`) and holds the real serial / BT MAC.
**Never commit the real values** — keep them in the local file only.

## Device
- Model:      Approach R10
- Serial:     <SERIAL>
- Firmware:   <FW>
- BT MAC:     <MAC>   (static random — stable across sessions)
- Paired to:  <PHONE>

## Debugging commands

Scan-dump — single the R10 out of a raw scan by address (the paired unit
advertises no name and no service UUID, so the MAC is the only reliable handle):

    adb shell am start -n com.techdelivery.r10/.ScanDumpActivity --es target <MAC>
    adb logcat -s R10SCAN:V

An **unfiltered** scan is silently refused while the screen is off — wake it first:

    adb shell input keyevent KEYCODE_WAKEUP
    adb shell svc power stayon true

Re-enable the Garmin apps before handing the phone back (they were disabled to
free the R10's ACL link; `force-stop` does not release it):

    adb shell pm enable com.garmin.android.apps.connectmobile
    adb shell pm enable com.garmin.android.apps.golf

## Notes
- GATT data service / characteristic UUIDs live in DESIGN.md §4 (not sensitive).
- The paired R10 advertises a 62-byte stub with no name and no service UUID; the
  bonded-direct connect path is the only discovery route for a paired unit.
