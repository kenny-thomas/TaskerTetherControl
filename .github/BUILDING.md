# TaskerTetherControl APK builds

This repository contains the original Java Tasker/Locale plug-in. Its Gradle configuration was modernized without rewriting the tethering code.

- `master` is the source branch and has **no automated APK build** on push.
- Advance `apk-build` to the desired `master` commit to request exactly one build, or choose **Actions → Build Android APK → Run workflow**.
- CI uses JDK 17, Gradle 8.11.1, Android Gradle Plugin 8.10.1, and Android SDK 35.
- Successful builds are uploaded as 30-day GitHub Actions artifacts and published in a GitHub prerelease with a **direct .apk download** and SHA-256 checksum. Publishing consumes the existing artifact without a second Gradle build.

## Stable signing and in-place updates

**CI now refuses to build/publish if the permanent signing secrets are missing or
the certificate does not match** `.github/signing-certificate.sha256`.
There is no fallback to a temporary runner debug certificate. A mismatched signing
certificate fails the build before publishing an APK.

This repository is **public**. NEVER commit a keystore, passwords, a signing
bundle, or GitHub Actions secrets to the repository or a release.

The persistent application certificate fingerprint (SHA-256) is:

```text
01d3dc30858c351b650d4a0be379cf708f52f3a3efdb87f6e3cc3fbe3deea367
```

Download and securely back up your private signing bundle from your ChatGPT
project attachment. It contains the **single permanent app signing key** and
`signing-secrets.env`. The key must be retained for *all* future updates.
Using GitHub's repository **Settings → Secrets and variables → Actions →
New repository secret**, create these four secrets from the .env file:

- `APK_KEYSTORE_BASE64`
- `APK_KEYSTORE_PASSWORD`
- `APK_KEY_ALIAS`
- `APK_KEY_PASSWORD`

You can instead run this command locally if GitHub CLI is logged in, from the
directory containing `signing-secrets.env`:

```sh
gh secret set --repo kenny-thomas/TaskerTetherControl -f signing-secrets.env
```

GitHub connections used to maintain this repository cannot write Actions
secrets, so the repository owner must provision these once. Do not put the
private bundle into Git, a public gist, or GitHub Actions build logs.

**One-time transition:** builds #2 and #3 were signed on disposable Actions
runners. Their private keys are unavailable, so no newly signed APK can update
those installations in place. Remove the old Magisk module (if installed),
reboot, uninstall the previous APK, and then install the new permanent-key
signed APK. Afterwards, future builds signed with this same certificate and
higher version codes can install directly as updates. Consider backing up
Tasker configurations if uninstalling would lose any settings.

The app ID remains `com.matejdro.taskertethercontrol`, and CI `versionCode`
is `7000 + GITHUB_RUN_NUMBER`. Do not change workflow identities/version
counters without ensuring the next code exceeds the currently installed APK.

## Android runtime warning

The original code dates from 2017 and relies on hidden connectivity APIs, privileged permissions, and legacy /system remount behavior. A successful Android build does **not** establish functional tethering on Android 15 or dynamic-partition Pixel devices. Device testing and modern platform changes are separate work.

## Systemless privileged app installation

The in-app **Install using Magisk** button packages the installed APK and
`privapp-permissions` XML into a module ZIP, confirms the user wants to
install it, and invokes `su -c magisk --install-module` from a root command.
The module adds a privileged-app overlay at the next reboot; the old
`mount -o rw,remount /system` procedure is completely removed.

This option requires a functional Magisk installation. KernelSU/APatch
are not supported by this button. If installation fails, copy the installer
output, inspect the Magisk module list, and do not try manually remounting
`/system`. Removing or disabling the module in Magisk reverts the overlay.

A modern Pixel 9 does not permit the 2017 style writable-system APK
installation. This fix only handles the installation workflow, not the
private tethering APIs used by `TaskerReceiver`. Android 15 runtime
functionality still needs physical-device validation.

## Android 15 tethering receiver (experimental)

`TaskerReceiver` uses `android.net.TetheringManager` on Android 11+ rather
than the removed `android.net.IConnectivityManager.startTethering` signature.
On Android 15 the API is present at runtime but was not exposed by the
public API 35 SDK. The implementation uses guarded reflection and a
callback proxy, and may be affected by hidden API enforcement or OEM API
differences. If reflection is blocked, Tasker gets an explicit failure and
a reason; it no longer reports success before asynchronous tethering completes.

The Tasker/Locale broadcast uses `goAsync()`, an approximately 5.5 second
timeout, and returns a proper result code and `%errmsg` error variable.
Stopping uses the compatible `TetheringManager.stopTethering(int)` call
with acknowledgement that this older API has no completion callback.

Test **both** Enable and Disable on the actual Pixel 9 / Android 15 with
the updated Magisk module active. APK compilation cannot validate device
permissions, hidden APIs, or carrier entitlement.

## Tasker State plugin conditions (Android 11+)

In Tasker: **Profiles → State → Plugin → Tethering States**, choose
**Hotspot enabled**, **Hotspot disabled**, **One or more devices connected**,
or **No devices connected**. Press Back to save the condition.

These conditions use the Locale `EDIT_CONDITION` / `QUERY_CONDITION` API,
and the app broadcasts `ACTION_REQUEST_QUERY` on hotspot or client changes.
Android Wi-Fi `SoftApCallback` gives both enabled state and connected-client
lists without polling. A persistent foreground notification keeps the
listener alive; it can be stopped from that notification. Monitoring resumes
after reboot if previously enabled.

**Important permissions:** API 30+ on-device `WifiManager.registerSoftApCallback`
is a restricted system API requiring `NETWORK_SETTINGS`; the supplied Magisk
permission XML has been extended to request it. After upgrading the APK,
reinstall/update the Magisk module via the plugin and reboot, otherwise
Tasker conditions will remain UNKNOWN. If hidden-API access is rejected on
the device, the listener logs the error and shows a monitor-unavailable
notification rather than reporting an incorrect false state.

The NO CLIENTS condition is true when the hotspot has no clients,
including when it is disabled. Combine it with the HOTSPOT ENABLED
condition if you only want an enabled hotspot with zero clients.

**Testing:** Try hotspot off; hotspot on with zero devices; one
connected device; disconnect the last device; restart Android;
turn monitoring off from its notification. Confirm that the profile
enters/exits each state. Wi-Fi bridged mode requires per-instance
client aggregation, which is implemented but not hardware verified.

Note that the pre-existing Android 15 tethering ACTION implementation
uses reflection and still requires live-device verification.

## Hotspot state reliability fix and diagnostics (v1.4.4)

The initial monitoring code incorrectly treated a failed privileged
`registerSoftApCallback` call as a total monitoring failure. Now the
service registers for `android.net.wifi.WIFI_AP_STATE_CHANGED` independently
of the privileged callback; its `wifi_state` values identify hotspot
enabled/disabled. `WifiManager.getWifiApState` is also attempted for an
initial immediate snapshot; if hidden-API restrictions block it, results
remain UNKNOWN until an actual broadcast arrives.

**Connected clients** still require the SoftAP system callback and the
`NETWORK_SETTINGS` permission. If the callback cannot be registered,
client states are UNKNOWN while hotspot on/off profiles may still work.
No ambiguous data is converted to false.

The app now offers a launcher activity named **Tethering Diagnostics**.
Open it to view current hotspot state, connected clients, whether the
privileged permission is granted, last callback error, and state source.
Use **Start / retry monitoring**, then **Refresh status**. Tapping the
ongoing monitor notification also opens diagnostics; the screen has a
**Copy diagnostics** button to share the status text.

If monitoring remains unavailable, report the copied diagnostics; a
GitHub CI build cannot verify the device-specific SoftAP permissions,
hidden API behavior, or Magisk overlay state.

## Force reinstall Magisk module (v1.4.6)

The plugin action editor previously hid its **Install using Magisk**
button once Android reported `TETHER_PRIVILEGED` granted. That prevented
reinstallation following a module removal/upgrade. The button is now
always visible when Modify System Settings is granted, and a dedicated
**Force Reinstall Magisk Module** button is always accessible from the
**Tethering Diagnostics** launcher activity, regardless of current
privileged permissions.

Both controls ask for confirmation, call the same root Magisk module
installer, and package the currently installed APK plus privileged
permission XML. The installer does not remount `/system`; reboot to apply
a successful install/replace. If a module is pending removal, check its
state in Magisk before attempting to replace it.

Updating the APK alone does **not** update the APK embedded in Magisk's
systemless module. Use the dedicated reinstall button after each APK
update that changes requested privileged permissions.

## Android 15 app crash resilience (v1.4.7)

The monitor's `startForeground()` used to throw unhandled exceptions,
potentially creating a crash loop whenever Tasker requested a state or
the boot receiver restarted monitoring. It now catches foreground
service errors, disables automatic monitoring until user retry, and
records the reason for diagnosis. Hotspot callback processing, Tasker
requery broadcasts and notification updates also catch runtime failures.

A new `TetherApplication` saves the last uncaught Java exception to
private on-device preferences for diagnosis, then allows the ordinary
Android crash handler to run. Nothing is uploaded automatically.
Open **Tethering Diagnostics** and use **Copy last crash report** to
share the captured failure, or clear it from the same screen. This
will not capture native crashes, Android system kills, or crashes of
other application processes.

If repeated app exits persist, capture `adb logcat -b crash -d`
immediately afterward. A successful compile alone cannot establish
device-specific crash recovery.

## Tasker Profile state recheck delivery (v1.4.11)

Android tethering events may be correctly detected in **Tethering Diagnostics**
but never activate Tasker if the host never receives
`com.twofortyfouram.locale.intent.action.REQUEST_QUERY`.
When Tasker (package `net.dinglisch.android.taskerm`, or the older direct
purchase package `net.dinglisch.android.tasker`) is installed, send the
request with `Intent.setPackage()` to make it package-targeted. When
neither package is installed, preserve the original implicit broadcast
for other Locale-compatible hosts.

The plugin still responds to Tasker's explicit ordered
`ACTION_QUERY_CONDITION` broadcasts with Locale result codes 16, 17 or 18.
The configuration activity continues to save the condition-mode bundle.

**Diagnostics** now persists the number/timestamp of REQUEST_QUERY sends,
the routing package, number/timestamp of actual Tasker QUERY_CONDITION
receives, last mode and result code. **Sent broadcasts are not delivery
acknowledgements**: compare both counters while changing the hotspot.
If host queries stay at zero, verify Tasker itself is enabled and that
the profile uses **State > Plugin**, not Event. If host queries increase
but result code disagrees with the current hotspot state, inspect
the last query mode/bundle and report the diagnostics.

Reinstalling Magisk does not affect Tasker condition recheck delivery.
