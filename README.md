# OriTweaks

Fixes and enhancements for vivo OriginOS that have nothing to do with Google:
- a snappier app drawer after you leave recents;
- the Origin Island music player for more apps;
- vivo's game super resolution and frame interpolation for any game;
- 50 MP capture in third-party camera apps.

OriTweaks is an [LSPosed](https://github.com/LSPosed/LSPosed)-style module built on the
[libxposed API 102](https://github.com/libxposed/api). All changes are made at runtime and no system partition is modified.

Google integrations (Circle to Search, Gemini gestures, Quick Share, Discover and others) live in the companion module **[OriGoogle](https://github.com/kailashrs/OriGoogle)**.

> [!WARNING]
> These hooks target specific OriginOS classes and are tested on one phone only. Use them at your own risk.

## Compatibility

| | |
| --- | --- |
| Device | vivo X200 Ultra (PD2454), China firmware |
| OS | OriginOS 6 (Android 16) and OriginOS 7 (Android 17, checked on 17.2.10.6 / launcher 17.0.0.381) |
| Framework | Any libxposed API 102 framework; developed on Vector |
| Optional | [Shizuku](https://github.com/RikkaApps/Shizuku), which lets the app restart affected apps without a soft reboot |

The module checks `ro.vivo.os.build.display.id` and does nothing outside OriginOS.

## Features

You can switch each feature on or off in the OriTweaks app. Features marked *app list* also let you choose which apps they apply to.

| Feature | Default | What you get |
| --- | --- | --- |
| **Instant app drawer after recents** | On | Swiping up right after leaving recents opens the app drawer. Without the module, the swipe is ignored for about half a second. |
| **More island music player apps** *(app list)* | On, YouTube Music | The Origin Island music card and the control-center player work for the apps you choose. Those apps' own media notification is hidden, so you don't get two players. |
| **More super resolution games** *(experimental, app list)* | On, no games | Adds the games you choose to vivo's game super resolution, using its generic profile. |
| **More frame interpolation games** *(experimental, app list)* | On, no games | Adds the games you choose to vivo's frame interpolation, using its generic 60 → 120 fps profile. |
| **Full resolution in camera apps** *(experimental)* | On | PhotonCamera and Open Camera can shoot 50 MP on the main, ultrawide and periscope cameras. |

Games must also be added in **Game space** for the two game features to work.

### Full resolution needs a CamX override

The camera hooks only fix how apps ask for full resolution. The camera HAL must also be told to offer it, and that needs the companion KernelSU/Magisk module **[camx-fullsize](https://github.com/kailashrs/camx-fullsize)**. It does three things:
- sets `exposeFullSizeForQCFA=TRUE` in `camxoverridesettings.txt`;
- adds the matching remosaic rules to the sensor-mode selector (`sensormode-selector/PD2454.json`);
- adds the camera apps to `vendor.camera.aux.packagelist`, so the hidden periscope camera shows up.

Without that module, this feature does nothing.

## Installation

1. Root the phone and install a Zygisk implementation and a libxposed API 102 framework. The setup tested with OriGoogle is described in [its README](https://github.com/kailashrs/OriGoogle#tested-setup).
2. Install the APK as root, because vivo blocks `adb install`:
   ```bash
   adb push app-release.apk /data/local/tmp/oritweaks.apk
   adb shell su -c pm install -r /data/local/tmp/oritweaks.apk
   ```
3. Enable **OriTweaks** in your framework's manager with the recommended scope:

   | Scope | Used for |
   | --- | --- |
   | `system` (system_server) | Apply actions (restart apps, soft reboot) |
   | `com.bbk.launcher2` | Instant app drawer |
   | `com.vivo.musicwidgetmix` | Island music app list |
   | `com.android.systemui` | Hiding the duplicate media notification |
   | `com.vivo.gamecube`, `com.vivo.gamewatch` | Game lists |
   | `com.particlesdevs.photoncamera`, `net.sourceforge.opencamera` | Full-resolution camera |

4. Reboot.

Vector does not re-apply `scope.list` when you update the APK. Add any new scope by hand with
`/data/adb/modules/zygisk_vector/cli scope add io.github.kailashrs.oritweaks <package>/0`.

## Using the app

Change switches or app lists, then tap **Apply**. The app restarts only the apps a change affects:
- With Shizuku running and permission granted, it restarts them directly. Under root Shizuku this also restarts persistent processes such as GameWatch.
- Otherwise it asks the module's receiver in `system_server` to do it. This receiver only exists after the first reboot with the module enabled.

## How it works

| Feature | Process | Mechanism |
| --- | --- | --- |
| Instant app drawer after recents | launcher | Leaving recents starts QuickstepPlugin's `RecentsView` exit-to-home guard animator. It only ends once the launcher's scale spring settles, about 0.5–0.75 s after the home screen is visibly back. Until then, Workspace's `checkRecentViewAnimationIfNeeded` (the only `boolean (DrawerContainerView)` method) rejects swipe-ups to the drawer. When that check fails only because the guard is running, the module force-ends the guard the way vivo's home gesture does (`RecentsPlugin.forceEndExitToHomeAnim`). It skips the spring to its end rather than cancelling it, so the home screen is left at full size. |
| More island music player apps | `com.vivo.musicwidgetmix`, SystemUI | VivoMusicWidgetMix serves the island music card (SystemUIPlugin `RemoteMusicManager`). It only follows apps on its lists: built-in lists (`w6.v0` on OriginOS 7, `s4.m0` on 6, which island notifications check), and the cloud whitelist, into which `R.array.framework_support_list` is merged. Chosen apps are added to both. In SystemUI, they are also added to the whitelist that the control-center card plugin (`com.vivo.musicmixcard`) reports through `mixMusicCallbackToQSCenter(100)`. As a result, `MediaCoordinator` hides their own media notification. Apps that are not listed, like Spotify, already use the generic `MediaController` path. |
| More super resolution / frame interpolation games | `com.vivo.gamecube`, `com.vivo.gamewatch` | Both apps read `/system/etc/gamecube/{SuperResolution,FrameInter}ConfigList.xml` via `new FileInputStream(String)`. The module serves a copy from the app's cache with the chosen games appended, using vivo's generic entries (super resolution `state="1"`; frame interpolation `index="10000"`, 60 → 120 fps). It also raises the list version by 1000 plus the settings revision, so GameCube re-imports the list into its database. |
| Full resolution in camera apps | the camera app | Framework-level Camera2 hooks inside the app's own process, so the app's often obfuscated code doesn't matter. Each capture request is made consistent with its targets: <ul><li>full-size `ImageReader` surfaces are classified by size as soon as the app gets them;</li><li>requests that target them run with `SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION` and drop the other targets, while all other requests run in default mode;</li><li>crop regions are rescaled to the maximum-resolution active array;</li><li>full-size bursts are capped at about 800 MB of RAW.</li></ul> |
| Apply actions | system_server | A receiver registered at `ActivityManagerService.systemReady`, protected by a signature permission. It can only restart the module's own scoped packages, or soft-reboot. |

### Source layout

```
app/src/main/kotlin/io/github/kailashrs/oritweaks/
├── OriTweaksModule.kt      entry point, dispatches hooks per process
├── core/                   hook helpers, feature switches, control protocol
├── launcher/               instant app drawer
├── media/                  island music app list and SystemUI notification whitelist
├── games/                  super resolution / frame interpolation lists
├── camera/                 full-resolution Camera2 hooks
├── system/                 system_server control receiver
└── ui/                     settings app, Shizuku restarter
```

## Troubleshooting

OriginOS mutes logcat for its system apps, so each hooked process writes `oritweaks.log`:

| Process | Log |
| --- | --- |
| system_server | `/data/system/oritweaks.log` |
| launcher | `/data/user_de/0/com.bbk.launcher2/cache/oritweaks.log` |
| other apps | `<app data dir>/cache/oritweaks.log`, e.g. `/data/user/0/com.vivo.musicwidgetmix/cache/oritweaks.log` |

Every hook logs whether it installed and which OriginOS classes it matched.

- **A game doesn't get super resolution or frame interpolation.** Add the game in Game space, then tap Apply so GameCube re-imports its list.
- **The island still shows nothing for an app.** The app must publish a `MediaSession`. Tap Apply after changing the list so the music widget service restarts.

## Building

Requires JDK 21. The Android Gradle Plugin's jlink step fails on newer JDKs.

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew :app:assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`. Release builds are signed with your local debug key.

## License

**GPL-3.0** (see [LICENSE](LICENSE)). The module shares its core code with OriGoogle, which is derived from [Coogle](https://github.com/zopulus/coogle).

Not affiliated with vivo. All trademarks belong to their owners.
