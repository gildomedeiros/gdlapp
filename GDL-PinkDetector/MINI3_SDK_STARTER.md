# Mini 3 Pro SDK starter

Started 2026-09-14 for the user-confirmed Mini 3 Pro + RC-N1 + Android device.

**Current project location:** `C:/Users/gildo/gdlapp/cam3`. Open `C:/Users/gildo/gdlapp/cam3/SampleCode-V5/android-sdk-v5-as` in Android Studio. The project was copied there after Windows blocked moving the original folder; all 2,904 included source/settings/Git files and the existing APK were hash-verified. Build/Gradle/Kotlin/IDE caches were excluded. The original remains intact. Paths and commands below reference that original checkout; use the new location for ongoing development. In the new Android Studio project, select Java 21 as the Gradle JDK and sync before building.

## Source and project

Official source: https://github.com/dji-sdk/Mobile-SDK-Android-V5

Local independent checkout: `mini3-sdk-starter/`, upstream revision `a48aa4e7811d824c27abfa973f5655579bfb8a77`, SDK **5.18.0**. Its own Git metadata and MIT sample license are retained. The parent repository ignores this checkout; backing up the parent alone does not back up local starter edits.

`MINI3_SDK_LOCAL.patch` preserves the current local changes (local key loading and ignoring Kotlin build caches). To recreate them, clone the official repository, check out the revision above, and apply this patch from that checkout with `git apply ../MINI3_SDK_LOCAL.patch`. Recreate `local.properties` with the local SDK path and your key separately; it is not included in the patch.

Open `mini3-sdk-starter/SampleCode-V5/android-sdk-v5-as` in Android Studio. The `sample` application includes DJI's `uxsdk` module. Keep the sample's package `com.dji.sampleV5.aircraft` for this first milestone. This is a separate application from GDL PinkDetector.

The checked sample targets Android 35, requires Android 24 or later, and packages arm64-v8a native libraries. Use a 64-bit Android device. Gradle is 8.12, Android Gradle plugin 8.7.0, Kotlin 2.1.0. On this computer use `C:/Program Files/Java/jdk-21` as the Gradle JDK; Android Studio's bundled Java 25 fails this sample with `Unsupported class file major version 69`.

## Create the DJI App Key

Completed for the user-supplied DJI app `cam3` on 2026-09-14. Its package matches the sample; the supplied key is configured in ignored local settings. Keep the steps below for recreating the setup.

1. Sign in or register at https://developer.dji.com/user/apps/ and complete DJI developer registration.
2. Create a Mobile SDK application for Android. Use an app name such as `GDL Mini 3 Pro Sample`.
3. Enter the exact package name `com.dji.sampleV5.aircraft`.
4. Obtain the App Key from the application's details. A key registered for a different package will not match this sample.
5. Open `mini3-sdk-starter/SampleCode-V5/android-sdk-v5-as/local.properties`. Paste the key after `AIRCRAFT_API_KEY=` and save. This file is ignored by Git. The local sample build reads it in preference to DJI's tracked placeholder.
6. Build again after adding or changing the key; it is embedded in the APK manifest at build time. Map provider keys are separate and have not been configured.

Official instructions: https://developer.dji.com/doc/mobile-sdk-tutorial/en/quick-start/run-sample.html

From this parent project directory in PowerShell:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
$env:GRADLE_USER_HOME = 'C:/Users/gildo/.gradle'
& ./mini3-sdk-starter/SampleCode-V5/android-sdk-v5-as/gradlew.bat -p mini3-sdk-starter/SampleCode-V5/android-sdk-v5-as :sample:assembleDebug --console=plain
```

The expected APK output directory is `mini3-sdk-starter/SampleCode-V5/android-sdk-v5-sample/build/outputs/apk/debug/`. The sample retains DJI's supplied development signing configuration; it is not a production signing setup.

## First hardware milestone

After configuring the key and building, install the debug sample on the Android device. Give it internet access for SDK registration and grant the sample's required permissions. Close DJI Fly before connecting the RC-N1 USB data cable and select the sample for the USB accessory prompt.

Start with a stationary, grounded aircraft. Confirm the sample reports registration success and a product connection. Open DJI's Default Layout or Camera Stream testing page and verify live video; check reported model, battery and available telemetry. Record aircraft/controller firmware, Android model/version, APK hash and observed results. Do not infer camera or aircraft operation from compilation alone.

Pink detection, automatic target selection and gimbal recovery are later work. Availability of Mini 3 Pro Spotlight/ActiveTrack APIs is not established by general aircraft support or by seeing those features in DJI Fly. Verify each needed API's product support before implementation.

## Validation status

Both the first full `:sample:assembleDebug` (7m 13s) and the final incremental build with local-key configuration (7s) passed using Java 21. Upstream resource, deprecated API and native-library packaging warnings remain; they did not prevent assembly. Local diffs passed whitespace checks and the saved patch passed reverse-apply checking.

Current built file: `mini3-sdk-starter/SampleCode-V5/android-sdk-v5-sample/build/outputs/apk/debug/sample-debug.apk`. Gradle output metadata reports `com.dji.sampleV5.aircraft`, version 1.0 / code 1, debug, minimum SDK 24. SHA-256: `FE66258FEB48A7AB9DF6E21538A007F8826BFA45EC7266BDFD7F91B0CA370645`.

Rebuild with the user-supplied `cam3` key passed in 11s (6 tasks executed, 53 up-to-date). Verified the configured key matches the packaged XML manifest's DJI metadata and is present in the APK's binary manifest without printing it. This replaces the earlier placeholder-key APK. No installation, registration, aircraft connection or camera stream has been verified. Map keys are also unconfigured. Compilation and key packaging are the completed milestones; working hardware operation remains unverified.
