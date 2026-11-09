# Project: Gyro Android Player Core Features & Audiovisual Upgrade

## Architecture
Gyro is a modern Jetpack Compose Android video player powered by a native Rust/mpv engine (`libgyro_engine.so`).
The codebase is structured into modular layers:
- `:core:model`: Pure Kotlin data models (`UserSettings`, `PlayerGestureConfig`, `MpvConfig`, `ChapterInfo`, `MediaInfo`, `PlaybackProgress`).
- `:core:data`: Repositories and persistence (`SettingsRepository`, `MpvConfigRepository`, `PlaybackHistoryRepository`, `CustomFolderManager`).
- `:core:designsystem`: Design tokens, Compose themes, icons, and centralized localized string resources (`values/strings.xml`, `values-en/strings.xml`).
- `:core:navigation`: Type-safe Navigation 3 route definitions (`Screen`).
- `:core:engine`: Native JNI bridge (`GyroNative`), decoupling bridge (`MpvNativeBridge`), and lifecycle player engine (`GyroPlayerEngine`).
- `:core:testing`: Test fakes, rules, and mock bridges (`FakeMpvNativeBridge`, `MainDispatcherRule`, `TestUri`).
- `:feature:settings`: Preference screens (`SettingsScreen`, `MpvConfigEditorScreen`, `GestureSettingsScreen`, `FolderManagementScreen`).
- `:feature:player`: Video playback surface (`GyroSurfaceView`), gesture recognition (`PlayerGestureNode`, `PlayerGestureDetector`), controls overlay (`PlayerControlsOverlay`, `PlayerScrubBar`, `PlayerTrackSelectionSheet`, `PlayerHudOverlay`).
- `:app`: Application entry point, `MainActivity`, `PlaybackService` foreground media service with `MediaSessionCompat`.

Native Engine Build:
- `crates/gyro-engine` is compiled by `:core:engine:cargoNdkBuild` (cargo-ndk) into `core/engine/build/rustJniLibs/<abi>/libgyro_engine.so`, which is registered as the module's generated jniLibs source.
- No `.so` is tracked in git: `core/engine/src/main/jniLibs/` is ignored, so the Rust source is the only truth and a stale binary cannot ship.
- ABIs come from `gyro.nativeAbis` in `gradle.properties`; that single value drives both the cargo-ndk targets and the `abiFilters` in `:app`.
- Prerequisites: Rust (the android targets in `rust-toolchain.toml` are installed by rustup on demand), `cargo-ndk`, and an Android NDK. The task skips itself when the crate, `Cargo.lock`, and the toolchain file are unchanged.

Data Flow:
- User Preferences & Configurations: `SettingsRepository` & `MpvConfigRepository` (persisted in internal files & prefs) -> `UserSettings` / `MpvConfig` -> injected into `GyroPlayerEngine` and observed by Feature ViewModels.
- Playback & Audio Pipeline: domain controllers subscribe to `MpvNativeBridge.events` and own their mpv properties (`engine.audio` → `alimiter`/`dynaudnorm`/downmix, `engine.video` → color & deband, `engine.shaders` → `glsl-shaders`, `engine.trackManager` → tracks & chapters, `engine.tuning` → A/V delay & subtitle scale/position); `GyroPlayerEngine` keeps session lifecycle and core playback state only.
- Audio Overdrive Flow: Gestures split travel at 100%: 0~100% adjusts Android `AudioManager.STREAM_MUSIC`; 101~200% adjusts internal mpv software gain with dynamic `alimiter` anti-clipping filter and amber HUD. Auto-reset to 100% on track change/exit.

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Built-in GLSL Shaders | Asset packaging & extraction of AMD CAS and Anime4K Lite shaders | M2 | R1 |
| 2 | Custom Shader SAF Import | SAF file picker integration and internal cache for local `.glsl` files | M2, M4 | R1 |
| 3 | Shader Hot-swapping | Runtime hot-swapping via `change-list glsl-shaders set/clr` | M2 | R1 |
| 4 | Shaders Selection Tab | Bottom sheet tab in `PlayerTrackSelectionSheet` for shader switching | M4 | R1 |
| 5 | MpvConfigRepository | File-backed repository persisting custom `mpv.conf` text | M1 | R2 |
| 6 | MpvConfigEditorScreen | Monospace editor in Settings for `mpv.conf` with guidance and presets | M3 | R2 |
| 7 | mpv.conf Injection | Injection of custom options into `GyroPlayerEngine` during init | M2 | R2 |
| 8 | GUI Override Precedence | Guarantee GUI explicit settings override `mpv.conf` pre-init & runtime | M2 | R2 |
| 9 | Chapter List Parsing | Observe `chapter-list` and parse into `ChapterInfo` models in engine | M2 | R3 |
| 10 | Glowing Chapter Markers | Render micro-glow tick marks at chapter fractions on `PlayerScrubBar` | M4 | R3 |
| 11 | Chapter Selection Drawer | List chapters in bottom sheet with formatted time and click-to-seek | M4 | R3 |
| 12 | 2-Stage Volume Gesture | 0-100% system volume, 101-200% software gain strictly isolated | M2, M4 | R4 |
| 13 | Overdrive Amber HUD | Top capsule HUD switches to warm amber with overdrive indicator | M4 | R4 |
| 14 | Software Gain Safety Reset | Auto-reset software gain to 100% (0dB) on video switch or player exit | M2, M4 | R4 |
| 15 | Audio Limiter Protection | Dynamic `alimiter` filter in `af` to eliminate clipping at 200% volume | M2 | R4 |
| 16 | Deband Filter Toggle | One-click deband filter toggle (`deband=yes`) | M2, M4 | R4 |
| 17 | Color Adjustments | Sliders for brightness, contrast, and saturation (-100 to 100) | M2, M4 | R4 |
| 18 | Night Mode Dialogue | Dynamic Audio Normalizer filter (`dynaudnorm`) for clear dialogue | M2, M4 | R4 |
| 19 | Stereo Downmixing | Downmix multi-channel audio to 2.0 stereo via `pan` audio filter | M2, M4 | R4 |
| 20 | Background Playback Service | Foreground `PlaybackService` with `mediaPlayback` type | M5 | R5 |
| 21 | MediaSession & Notification | `MediaSessionCompat` and `MediaStyle` notification with lock screen card | M5 | R5 |
| 22 | Headset Controls | Wire and bluetooth headset media button handling | M5 | R5 |
| 23 | Surface Detachment | Headless background playback with `EGL_NO_SURFACE` | M5 | R5 |
| 24 | Strings Centralization | All UI strings centralized in `:core:designsystem` | M1, M4 | R6 |
| 25 | Full Bilingual i18n | Complete paired `values-en/strings.xml` eliminating hardcoded strings | M1, M4 | R6 |
| 26 | Pure Floating UI Style | Floating controls with 16dp interaction margins, no solid card blocks | M3, M4 | R6 |
| 27 | Model & Data Gesture Config | Move `PlayerGestureConfig` to `:core:model` & persist in `SettingsRepository` | M1 | R7 |
| 28 | Gestures Settings Screen | MX Player style graphical switches for gestures and sensitivities | M3 | R7 |
| 29 | Player Gesture Dynamic Binding | Wire custom gesture config in `PlayerScreen` & restore speed on release | M4 | R7 |
| 30 | JVM Unit Test Infrastructure | Decoupled `MpvNativeBridge` and `FakeMpvNativeBridge` in `:core:testing` | M2 | AC |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Core Foundation: Models, Data, Settings & i18n Strings | `:core:model`, `:core:data`, `:core:navigation`, `:core:designsystem` (strings.xml & values-en) | none | DONE |
| M2 | Engine Core & Audiovisual Infrastructure | `:core:engine`, `:core:testing` (`MpvNativeBridge`, shaders, mpv.conf injection & overrides, chapters, volume overdrive & limiter, deband, colors, audio filters) | M1 | DONE |
| M3 | Settings & Configuration UI | `:feature:settings` (`MpvConfigEditorScreen`, `GestureSettingsScreen`, Navigation 3 wiring) | M1, M2 | DONE |
| M4 | Player UI, Gestures & Audiovisual Controls | `:feature:player` (`PlayerTrackSelectionSheet` shaders/chapters/effects tabs, `PlayerScrubBar` chapter ticks, overdrive amber HUD, dynamic gestures, 16dp floating styling) | M1, M2, M3 | DONE |
| M5 | System Integration: Background Playback & MediaSession | `:app`, `:feature:player` (Manifest permissions, `PlaybackService`, `MediaSessionCompat`, notification card, headset controls, headless surface detachment, MainActivity navigation) | M2, M3, M4 | DONE |
| M6 | Final Milestone: 100% E2E Test Pass & Adversarial Hardening | Phase 1: Pass 100% of E2E test suite (Tiers 1-4). Phase 2: Adversarial coverage hardening (Tier 5). | M1, M2, M3, M4, M5, TEST_READY | PLANNED |

## Interface Contracts

### `:core:model` ↔ `:core:data` & `:core:engine`
- `MpvConfig`: `data class MpvConfig(val rawText: String, val options: Map<String, String>)` with `parse(text: String): Map<String, String>` companion method.
- `ChapterInfo`: `data class ChapterInfo(val index: Int, val title: String, val timeMs: Long)`.
- `PlayerGestureConfig`: Migrated from `:feature:player` to `dev.gyro.core.model.PlayerGestureConfig`.
- `UserSettings`: Embeds `val gestureConfig: PlayerGestureConfig = PlayerGestureConfig()`.

### `:core:data` ↔ `:core:engine` & `:feature:settings`
- `MpvConfigRepository`:
  - `val configText: StateFlow<String>`
  - `suspend fun saveConfig(content: String)`
  - `suspend fun resetToDefault()`
  - `fun getParsedOptions(): Map<String, String>`
  - `fun getConfigFile(): File`
- `SettingsRepository`:
  - `val settings: StateFlow<UserSettings>`
  - Granular and bulk update methods for `PlayerGestureConfig` and `UserSettings`.

### `:core:engine` ↔ `:feature:player` & `:app`
- `MpvNativeBridge`: Interface abstracting all `GyroNative` calls (`command`, `setOptionString`, `getProperty*`, `setProperty*`, `observeProperty`) plus the typed native event stream `events: SharedFlow<MpvEvent>`.
- `GyroPlayerEngine` (facade: session lifecycle + core playback only):
  - Core commands: `ensureInitialized(userOptions, settings)`, `loadUri(uri, startPositionMs, displayTitle)`, `load(item: MediaItem)`, `load(url, ...)`, `play()`, `pause()`, `togglePlayPause()`, `seekTo(ms)`, `seekRelative(ms)`, `release()`.
  - Core state: `playbackState`, `isPlaying`, `timePosMs`, `durationMs`, `bufferedPosMs`, `isFileLoaded`, `currentUri`, `mediaTitle`, `hwdecCurrent`, `speed`, `fps`, `droppedFrames`.
  - State: `val state: StateFlow<PlaybackSnapshot>` — the one and only playback state container, owned by `PlaybackStore` (`:core:engine.state`). Every controller writes through `store.update { }` and exposes read-only projections, so there is no second source of truth. UI subscribes once. `timePosMs`/`bufferedPosMs` stay on separate high-frequency flows (scrub bar only).
  - `SessionPhase` (`Idle / Resolving / AwaitingSurface / Submitting / Loaded / Failed`) publishes the engine's own load progress in the state; `error` carries the failure cause.
  - Surface: `attachSurface(surface)`, `detachSurface(surface?)`, `redraw()`, `isSurfaceAttached`.
  - Domain namespaces: `audio`, `video`, `geometry`, `trackManager`, `shaders`, `tuning`.
- `AudioEffectsController` (`engine.audio`): `nightMode`, `stereoDownmix`, `softwareVolume`; `setSoftwareVolume`, `resetSoftwareGain`, `setNightMode`, `setStereoDownmix`.
- `VideoEffectsController` (`engine.video`): `deband`, `brightness`, `contrast`, `saturation`; setters + `resetColors`.
- `VideoGeometryCoordinator` (`engine.geometry`): `videoDimensions`, `videoAspect`, `videoFrameRate`, `displayRefreshRate`, `resizeMode`, `geometryReady`, `firstFrameRendered`; `updateDisplayRefreshRate`, `setResizeMode`, `setZoomAndPan`, `resetZoomAndPan`.
- `TrackAndChapterManager` (`engine.trackManager`): `tracks`, `chapters`, `currentChapter`; `selectTrack`, `addSubtitle`, `refreshTracks`, `seekToChapter` via `engine.seekToChapter`.
- `ShaderController` (`engine.shaders`): `shaderState`; `setShader`, `clearShader`, `importAndSetCustomShader`, `getCustomShaders`.
- `SyncTuningController` (`engine.tuning`): `subtitleDelaySec`, `audioDelaySec`, `subtitleScale`, `subtitlePositionPercent`; corresponding setters, clamping owned by the controller.
- `MpvOptionConfigurator` (`:core:engine.config`): applies user `mpv.conf` entries, then GUI settings, then defaults, all before `mpv_init()`; protected keys (`vo`, `wid`, `idle`, `keep-open`, `gpu-context`) are dropped and the engine lifecycle invariants are re-applied.
- `MediaResourceResolver` (`:core:engine.media`): `suspend fun resolve(item: MediaItem): ResolvedMediaSource`. Opens content descriptors off the main thread and hands ownership over in `ResolvedMediaSource.takeTargetUrl()`; a request that is cancelled or replaced before `loadfile` is closed instead of leaking the fd. Resolution failure surfaces as `PlaybackState.ERROR`.
- Event stream: `MpvEventPipeline` (owned by `GyroNative`, surfaced as `MpvNativeBridge.events`) publishes `MpvEvent`; every domain controller subscribes through `observeEvents(scope)`. No replay, publish applies backpressure on the native event thread.
- `PlaybackTimelineController` is intentionally not a public namespace: it is the engine's own time/seek implementation.

### `:core:data` ↔ `:feature:settings`
- `MpvConfigRepository`:
  - `suspend fun loadConfig(): String`
  - `suspend fun saveConfig(content: String)`
  - `suspend fun resetToDefault(): String`
  - `suspend fun getParsedOptions(): Map<String, String>`


### `:core:navigation` ↔ `:feature:settings` & `:app`
- `Screen.MpvConfigEditor : Screen`
- `Screen.GestureSettings : Screen`

## Code Layout
- Existing conventions across Android Gradle modular structure:
  - Model definitions: `core/model/src/main/kotlin/dev/gyro/core/model/`
  - Repositories: `core/data/src/main/kotlin/dev/gyro/core/data/repository/`
  - Engine & Bridges: `core/engine/src/main/kotlin/dev/gyro/engine/`
  - Test Fakes & Mocks: `core/testing/src/main/kotlin/dev/gyro/core/testing/`
  - Strings & Design System: `core/designsystem/src/main/res/values/strings.xml`, `values-en/strings.xml`
  - Navigation Routes: `core/navigation/src/main/kotlin/dev/gyro/core/navigation/`
  - Settings UI: `feature/settings/src/main/kotlin/dev/gyro/feature/settings/`
  - Player UI & Gestures: `feature/player/src/main/kotlin/dev/gyro/feature/player/`
  - Service & App Entry: `app/src/main/kotlin/dev/gyro/player/`

## Known Issues / TODO

### High-frame-rate sources judder

Status: resolved. A 93fps clip kept juddering until two things were corrected; both were measured on
device (PLK110, Adreno 840, Android 17).

Root causes:
- The platform reports 120Hz - `Display.mode.refreshRate` and `Display.refreshRate` agree - while
  SurfaceFlinger composites this window at 90. mpv was given 120 and scheduled every frame for a grid
  the panel never produces.
- `eglSwapBuffers` does not wait for vsync on Android; it only blocks when the buffer queue is full,
  so frames reached the compositor at an arbitrary phase and were sampled by whichever refresh came
  next. mpv-android gets the equivalent pacing from vo_gpu's own swapchain.

The fix (commit 5da826b):
- `GyroSurfaceView.measureDisplayRate` samples Choreographer frame callbacks and reports the median
  interval to `GyroPlayerEngine.onDisplayRefreshRateMeasured`, which sets mpv's `display-fps`
  property at runtime. The median matters: one lost frame would otherwise skew the rate, which is
  the very failure being fixed.
- The render loop passes `MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME` and forwards mpv's `target_time` to
  `eglPresentationTimeANDROID` before each swap. The symbol is resolved lazily, so devices without
  `EGL_ANDROID_presentation_time` keep the previous behaviour.
- `framedrop=vo`, mpv's recommended mode, instead of `decoder+vo`.

Measured after the fix: video-output drops 0 across a 14s run of the 93fps clip (was ~28/s), and the
presented rate aligned with the measured 90Hz rather than the reported 120.

Notes for later:
- `dumpsys SurfaceFlinger`'s `vsyncRate` and the platform refresh-rate APIs are both unreliable on
  this device; measure with Choreographer instead.
- If judder returns, log `frame-drop-count` while playing: a growing count means mpv is dropping, a
  static count with visible judder means the compositor is discarding frames we still submit.

### gpu-next, Dolby Vision and HDR output

Status: HDR10/HLG lighting is wired end to end; gpu-next (and therefore Dolby Vision) is not
reachable with the current dependency.

- `dev.jdtech.mpv:libmpv:1.0.0` bundles **mpv v0.41.0**, whose render API only exposes
  `libmpv_gpu` (`video/out/gpu/libmpv_gpu.c`). `MPV_RENDER_PARAM_BACKEND` does not exist in
  `render.h` (the enum ends at `SW_POINTER = 20`), and unknown render params are skipped silently,
  so asking for `vo-backend=gpu-next` returns success while the session keeps running `vo_gpu`.
  Upstream support is still an open draft (mpv-player/mpv#16818), absent from v0.42.0 as well.
  `GPU_NEXT_RENDER_API_SUPPORTED` (`:core:model`) encodes this; the UI offers the switch only when
  it is true, and `MpvOptionConfigurator` refuses to apply libplacebo-only options when it is not.
- HDR path: `hdr-capable` (a fake option intercepted in `jni.rs`) tells the EGL layer to pick a
  10-bit config before the first surface exists; `hdr-output` still drives the per-file decision.
  `EGL_GL_COLORSPACE_BT2020_PQ` is requested in `SurfaceSlot::create_window_surface` for every
  creation path (attach, format-change rebuild, recovery).
- The window surface carries its colourspace, so `GyroSurfaceView.surfaceChanged` asks for a
  rebuild (`RenderCommand::RebuildSurface`) when the buffer format flips between 8-bit and 10-bit.
- `displayHdrCapable` (`VideoGeometryCoordinator.setDisplayHdrCapable`, fed by `Display.isHdr`)
  gates PQ output itself, not just the window colour mode: on an SDR panel mpv must tone-map
  instead of being told `target-trc=pq`.
- Switching the renderer or the HDR mode at runtime cannot re-pick the EGL config; it is chosen
  once per render thread, so a mode change that needs 10 extra bits takes effect on next start.
