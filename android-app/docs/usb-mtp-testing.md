# Testing the USB / MTP Kindle sync

The sync has three layers, and they need very different things to validate. Be honest
about which layer a given test actually covers.

| Layer | What it is | How to test | Needs |
|---|---|---|---|
| 1. Reconcile logic | which book gets pushed / skipped / deleted, `.sdr` cleanup | **JVM unit tests** | nothing |
| 2. MTP host stack | our `android.mtp.MtpDevice` calls against a real MTP responder | **phone-as-host + a software MTP responder** | a phone + an MTP target + OTG cable |
| 3. Kindle behavior | VID detection, subfolder indexing, post-Wi-Fi retention | **a real Kindle** | a 2024-era (MTP) Kindle + OTG cable |

## Layer 1 — unit tests (no hardware)

```
./gradlew :app:testDebugUnitTest
```

`ReconcileTest` runs `reconcile()` against `FakeKindleStore`, an in-memory MTP responder
modeled on real device semantics (object handles, parent links, folders-as-associations,
overwrite-on-push). This is where the sync *decisions* are pinned down.

## Layer 2 — real host stack, no Kindle required

You can't simulate this on an emulator: a stock AVD has no USB-host feature, disables USB
in the guest kernel, and returns an empty `getDeviceList()`. So use a phone as the USB
**host** and put a software MTP **responder** on the other end of an OTG cable.

In **debug builds**, "Sync to Kindle" falls back to *any* attached MTP/PTP device when no
Kindle (vendor `0x1949`) is present — so a non-Kindle responder works as a test target. The
status line shows `⚠ Test target (not a Kindle): …` so you can't mistake it for the real
thing. Release builds stay Kindle-only.

Two responder options:

- **A second Android phone** in "File transfer / MTP" USB mode — zero new hardware. Plug it
  into the host phone via OTG, run a debug sync, then browse its `documents/Excalibur/`
  folder to confirm the files (and that deletes remove them + the `.sdr` sidecar).
- **[uMTP-Responder](https://github.com/viveris/uMTP-Responder)** on a USB-gadget board
  (Pi Zero 2 W is listed as tested). Its `src/mtp.c` implements exactly the operations we
  call — `SEND_OBJECT_INFO`, `SEND_OBJECT`, `DELETE_OBJECT`. Rough setup:
  1. Raspberry Pi OS → add `dtoverlay=dwc2,dr_mode=peripheral` to `config.txt`
  2. `make` uMTP-Responder, configure `/etc/umtprd/umtprd.conf`, run `umtprd_ffs.sh`
  3. Connect the Pi to the host phone via OTG and run a debug sync

The one undocumented seam in either option: confirming the *Android* host enumerates the
gadget over OTG (most write-ups host from a PC). Budget time for that.

## Layer 3 — the real Kindle

Only a physical 2024-era (MTP) Kindle settles: that a stock Android phone opens an MTP
session to it at all, that `documents/Excalibur/` is indexed, and whether Amazon's reported
removal of USB-sideloaded content after a Wi-Fi reconnect would undo a sync.

## Auto-launch / auto-sync

`MainActivity` registers `android.hardware.usb.action.USB_DEVICE_ATTACHED` with
`res/xml/kindle_usb_filter.xml`, currently matching Amazon/Lab126 vendor id `0x1949`
(decimal `6473`) and no product id because product ids vary by Kindle model. Android owns
the chooser/default-app prompt for this attach event.

The attach event only launches Excalibur. It does not sync unless the user has enabled
Settings -> "Auto-sync when connected". When disabled, the app records "Kindle connected.
Auto-sync is off." and leaves the manual "Sync to Kindle" button as the active path.

To smoke-test the intent branch without hardware:

```
adb shell am start -n com.joshuamandel.excalibur/.MainActivity \
  -a android.hardware.usb.action.USB_DEVICE_ATTACHED
```

That does not grant USB permission or create an attached device; it only proves the Activity
routes the attach action into the guarded auto-sync path.
