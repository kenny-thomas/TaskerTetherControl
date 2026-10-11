# TaskerTetherControl APK builds

This repository contains the original Java Tasker/Locale plug-in. Its Gradle configuration was modernized without rewriting the tethering code.

- `master` is the source branch and has **no automated APK build** on push.
- Advance `apk-build` to the desired `master` commit to request exactly one build, or choose **Actions → Build Android APK → Run workflow**.
- CI uses JDK 17, Gradle 8.11.1, Android Gradle Plugin 8.10.1, and Android SDK 35.
- Successful builds are uploaded as 30-day GitHub Actions artifacts and published in a GitHub prerelease with a **direct .apk download** and SHA-256 checksum. Publishing consumes the existing artifact without a second Gradle build.

## Stable development APK signing

For install-in-place upgrades, configure all four encrypted GitHub Actions repository secrets:
`APK_KEYSTORE_BASE64`, `APK_KEYSTORE_PASSWORD`, `APK_KEY_ALIAS`, and `APK_KEY_PASSWORD`.

Store a backed-up per-app development keystore securely; put only its base64 encoding in the secret. Do not commit keystores or passwords. If secrets are absent, each ephemeral runner's default debug key is used, so updates can require uninstalling an APK built by an earlier run. Production builds should have a separate production key.

The Android application ID stays `com.matejdro.taskertethercontrol`. Version codes increase by run number within this workflow history.

## Android runtime warning

The original code dates from 2017 and relies on hidden connectivity APIs, privileged permissions, and legacy /system remount behavior. A successful Android build does **not** establish functional tethering on Android 15 or dynamic-partition Pixel devices. Device testing and modern platform changes are separate work.
