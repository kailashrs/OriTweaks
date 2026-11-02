# OriTweaks

Fixes and enhancements for vivo X200 Ultra (PD2454, OriginOS 6 and 7 China, Android 16 and 17) that are not about
Google; Google integrations live in [OriGoogle](../origoogle). An LSPosed (libxposed API 102) module
running on the same Vector stack; no partition is modified.

## Features

| Feature | Where | How |
| --- | --- | --- |
| Instant app drawer after recents | launcher | Leaving recents starts QuickstepPlugin's `RecentsView` exit-to-home guard animator, which ends only after the launcher's scale spring settles (~0.5-0.75 s after home is visibly back). Until then Workspace's `checkRecentViewAnimationIfNeeded` (the only `boolean (DrawerContainerView)` method) rejects swipe-ups to the drawer. When that check fails only because the guard is running, the module force-ends it like vivo's home gesture does (`RecentsPlugin.forceEndExitToHomeAnim`), but skipping the spring to its end, not cancelling it, so the home screen is left at full size. Diagnosed on launcher 16.0.0.811, rechecked on 17.0.0.381. |
| More island music player apps | com.vivo.musicwidgetmix, com.android.systemui | The island music card (SystemUIPlugin `RemoteMusicManager`) is served by VivoMusicWidgetMix, which follows only listed apps: built-in lists in `s4.m0` (island notifications check `m0.c()`) and the cloud whitelist, into which `R.array.framework_support_list` is merged. Chosen apps are added to both; unlisted apps like Spotify already use its generic `MediaController` path. In SystemUI, the chosen apps are also added to the whitelist the control-center card plugin (com.vivo.musicmixcard) reports through `mixMusicCallbackToQSCenter(100)`, so `MediaCoordinator` hides their own media notification instead of showing a second player. Default: YouTube Music. Checked on 6.2.7.2. |
| More super resolution / frame interpolation games | com.vivo.gamecube, com.vivo.gamewatch | Both read `/system/etc/gamecube/{SuperResolution,FrameInter}ConfigList.xml` via `new FileInputStream(String)`; the module serves a copy from the app's cache with chosen games appended using vivo's generic entries (SR `state="1"`; FI `index="10000"`, 60 → 120 fps) and the version raised by 1000 + settings revision so GameCube re-imports it into its database. Games must also be in Game space. |

## Debugging

Each hooked process writes `oritweaks.log` in its data dir, e.g.
`/data/user_de/0/com.bbk.launcher2/cache/oritweaks.log`, `/data/system/oritweaks.log`.

## Build

`./gradlew :app:assembleRelease` (JDK 21, compileSdk 37). GPL-3.0 (shares OriGoogle's code).
