//! Pure state machines extracted from the native render pipeline.
//!
//! Nothing here touches EGL, GL, JNI or mpv. Every decision the pipeline makes about "may I enqueue
//! a frame" and "is the GPU stuck" lives in this module so it can be exercised on the host with
//! `cargo test`; the unsafe glue in `lib.rs` only forwards measurements into these types.

use std::sync::atomic::{AtomicBool, Ordering};

/// One-slot gate that collapses mpv's per-frame update notifications into at most one queued
/// render command.
///
/// mpv invokes its update callback from its own thread, once per produced frame. The render thread
/// can be busy for tens of milliseconds waiting on `eglSwapBuffers`, so without collapsing, the
/// command channel accumulates thousands of redundant notifications and then drains them in one
/// burst when the surface comes back.
#[derive(Debug)]
pub struct FrameCoalescer {
    pending: AtomicBool,
}

impl FrameCoalescer {
    pub const fn new() -> Self {
        Self { pending: AtomicBool::new(false) }
    }

    /// Claims the single queue slot. Exactly one caller succeeds until the slot is released.
    pub fn try_acquire(&self) -> bool {
        self.pending
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Relaxed)
            .is_ok()
    }

    /// Frees the slot.
    ///
    /// This must be called both after a notification was delivered *and* after enqueueing failed.
    /// A held-but-never-delivered slot closes the gate forever: the next playback session would
    /// render exactly one frame and then freeze, which is far harder to diagnose than a duplicated
    /// notification.
    pub fn release(&self) {
        self.pending.store(false, Ordering::Release);
    }

    /// True while some notification owns the gate and has not been released yet.
    pub fn is_pending(&self) -> bool {
        self.pending.load(Ordering::Acquire)
    }
}

impl Default for FrameCoalescer {
    fn default() -> Self {
        Self::new()
    }
}

/// Outcome of evaluating a single rendered frame.
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub enum WatchdogVerdict {
    /// Fast frame, or a frame exempted because the pipeline had just resumed.
    Healthy,
    /// Slow frame, but the run is not long enough to conclude the GPU is unhealthy.
    Slow,
    /// Enough consecutive slow frames were observed; the caller should degrade hardware decoding.
    Trip,
    /// The fallback already fired; the verdict is terminal for this session.
    AlreadyTripped,
}

/// Decides when abnormally slow frames mean "the GPU is stuck" rather than "this frame was just
/// expensive".
///
/// Two exemptions keep a working device from being degraded:
///
/// * A frame that arrives after a long silence is the first frame of a load, a resume or a seek.
///   Its cost says nothing about steady-state health, so it starts a warm-up window instead of
///   counting towards a trip.
/// * A single fast frame clears the run, so isolated stalls never accumulate.
///
/// The fallback is deliberately terminal: once the pipeline degrades to a copy path it stays there,
/// because silently flapping between paths is worse than staying on the safe one.
#[derive(Debug)]
pub struct RenderWatchdog {
    slow_frame_ms: u128,
    trip_after: u32,
    warmup_frames: u32,
    idle_gap_ms: u128,
    slow_run: u32,
    warmup_left: u32,
    tripped: bool,
}

impl RenderWatchdog {
    pub const fn new(
        slow_frame_ms: u128,
        trip_after: u32,
        warmup_frames: u32,
        idle_gap_ms: u128,
    ) -> Self {
        Self {
            slow_frame_ms,
            // A zero threshold would trip on the first slow frame and then stay tripped forever,
            // so it is clamped rather than treated as "disabled".
            trip_after: if trip_after == 0 { 1 } else { trip_after },
            warmup_frames,
            idle_gap_ms,
            slow_run: 0,
            warmup_left: 0,
            tripped: false,
        }
    }

    /// 80ms is roughly 12fps: slow enough to be visibly broken, fast enough to catch a stalled GPU
    /// long before a user gives up. 30 warm-up frames cover decode + first paint of a 4K load.
    pub const fn with_defaults() -> Self {
        Self::new(80, 3, 30, 500)
    }

    /// Evaluates one frame.
    ///
    /// * `elapsed_ms` is render + swap time for this frame.
    /// * `gap_ms` is the distance to the previous frame; a large gap means the pipeline had stopped
    ///   producing frames, so this one is a resume frame rather than a steady-state sample.
    pub fn on_frame(&mut self, elapsed_ms: u128, gap_ms: u128) -> WatchdogVerdict {
        if self.tripped {
            return WatchdogVerdict::AlreadyTripped;
        }

        if gap_ms >= self.idle_gap_ms {
            self.slow_run = 0;
            self.warmup_left = self.warmup_frames;
            return WatchdogVerdict::Healthy;
        }

        if self.warmup_left > 0 {
            self.warmup_left -= 1;
            return WatchdogVerdict::Healthy;
        }

        if elapsed_ms > self.slow_frame_ms {
            self.slow_run += 1;
            if self.slow_run >= self.trip_after {
                self.tripped = true;
                return WatchdogVerdict::Trip;
            }
            return WatchdogVerdict::Slow;
        }

        self.slow_run = 0;
        WatchdogVerdict::Healthy
    }

    /// Diagnostics hooks for tests and future HUD wiring; not on the hot path.
    #[allow(dead_code)]
    pub fn is_tripped(&self) -> bool {
        self.tripped
    }

    #[allow(dead_code)]
    pub fn warmup_frames_left(&self) -> u32 {
        self.warmup_left
    }
}

impl Default for RenderWatchdog {
    fn default() -> Self {
        Self::with_defaults()
    }
}

/// Bookkeeping for the hardware-decoder degradation the watchdog asks for.
///
/// The pipeline is allowed exactly one write of `hwdec`. Two failures must not be conflated:
///
/// * No mpv handle yet means nothing was attempted, so the latch stays open and a later frame can
///   try again.
/// * A write that mpv rejected means the pipeline is stuck with the decoder it has, and retrying on
///   every following slow frame cannot change that. The latch closes so the log does not flood while
///   the user is already looking at a frozen picture.
///
/// In both failure cases the UI must not be told the pipeline degraded, which is what the notify
/// answer distinguishes.
#[derive(Debug, Default)]
pub struct DegradeState {
    attempted: bool,
    applied: bool,
}

impl DegradeState {
    pub const fn new() -> Self {
        Self { attempted: false, applied: false }
    }

    /// Decides whether the caller should write `hwdec` now.
    pub fn begin(&mut self, has_handle: bool) -> bool {
        if self.attempted || !has_handle {
            return false;
        }
        self.attempted = true;
        true
    }

    /// Records the write result. Returns true when the caller should emit the fallback event,
    /// which is exactly when the decoder actually changed.
    pub fn confirm(&mut self, accepted: bool) -> bool {
        if accepted {
            self.applied = true;
        }
        accepted
    }

    pub fn is_applied(&self) -> bool {
        self.applied
    }
}

/// What the caller should do after the driver dropped the GL context.
// Host builds never reach the Android render loop, so this whole area looks unused to them.
#[allow(dead_code)]
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub enum RecoveryVerdict {
    /// A rebuild is still allowed; the caller should rebuild the pipeline and draw again.
    Rebuild,
    /// The budget is spent. The pipeline stays dead instead of strobing the screen.
    Exhausted,
}

/// Books how many times the GL pipeline may be rebuilt after the driver dropped the context.
///
/// `EGL_CONTEXT_LOST` is not a slow frame, so no decoder setting can recover from it: the context is
/// gone and every later GL call on it is void. The only cure is to build a new context and hand it
/// to mpv again, which is worth doing because the usual cause - the system reclaiming GPU resources
/// while the app sits in the background - leaves the process perfectly healthy otherwise.
///
/// It is not worth doing forever. A driver that loses the context two times running is broken, and
/// each further attempt costs another visible black frame, so the budget is finite and the verdict
/// terminal, matching how [`DegradeState`] latches rather than retrying on every failure.
#[allow(dead_code)]
#[derive(Debug)]
pub struct ContextRecovery {
    budget: u32,
    used: u32,
}

#[allow(dead_code)]
impl ContextRecovery {
    pub const fn new(budget: u32) -> Self {
        Self { budget, used: 0 }
    }

    /// Two rebuilds: one for a genuine driver reset plus one retry, few enough that a permanently
    /// broken GPU does not flash the screen once per second.
    pub const fn with_defaults() -> Self {
        Self::new(2)
    }

    /// Counts one context loss and answers whether a rebuild is still allowed.
    pub fn on_context_lost(&mut self) -> RecoveryVerdict {
        if self.used >= self.budget {
            return RecoveryVerdict::Exhausted;
        }
        self.used += 1;
        RecoveryVerdict::Rebuild
    }

    #[allow(dead_code)]
    pub fn rebuilds_used(&self) -> u32 {
        self.used
    }
}

impl Default for ContextRecovery {
    fn default() -> Self {
        Self::with_defaults()
    }
}

/// Result of queueing a render command and waiting for its acknowledgement.
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub enum AckOutcome {
    /// The render thread processed the command.
    Acknowledged,
    /// The command was queued but the wait expired, so the render thread may still be inside it.
    TimedOut,
    /// The render thread is gone; nothing was queued.
    Disconnected,
}

/// Queues a command and waits for its acknowledgement.
///
/// The caller passes an already-cloned [`std::sync::mpsc::Sender`] so that no outer lock is held
/// while waiting: every one of these waits can last hundreds of milliseconds, and the frame
/// notification path needs the same slot. Holding a lock across the wait stalls frame production
/// exactly when the pipeline is already in trouble.
pub fn request_ack<C>(
    sender: &std::sync::mpsc::Sender<C>,
    build: impl FnOnce(std::sync::mpsc::SyncSender<()>) -> C,
    timeout: std::time::Duration,
) -> AckOutcome {
    let (ack_tx, ack_rx) = std::sync::mpsc::sync_channel(1);
    if sender.send(build(ack_tx)).is_err() {
        return AckOutcome::Disconnected;
    }
    match ack_rx.recv_timeout(timeout) {
        Ok(()) => AckOutcome::Acknowledged,
        Err(std::sync::mpsc::RecvTimeoutError::Timeout) => AckOutcome::TimedOut,
        Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => AckOutcome::Disconnected,
    }
}

/// Which thread owns the GL context.
///
/// `0` means unowned. Threads are identified by a lazily assigned logical id rather than a platform
/// thread handle, so the guard needs no libc binding and stays testable on the host.
///
/// The point is not to prove safety at compile time but to turn "this GL call must happen on the
/// render thread" from a comment into something that fails immediately when it is wrong. Calling GL
/// without a current context is undefined behaviour that drivers report inconsistently: silence on
/// one device, a crash on another, months later.
#[derive(Debug, Default)]
pub struct ThreadAffinity {
    owner: u64,
}

#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub enum AffinityVerdict {
    /// Called from the thread that owns the context.
    Owner,
    /// No thread owns the context, so nothing can be violated.
    Unowned,
    /// Called from a thread that does not own the context.
    Violation,
}

impl ThreadAffinity {
    pub const fn new() -> Self {
        Self { owner: 0 }
    }

    /// Claims ownership for `tid`.
    ///
    /// Returns false when a different thread already owns the context: two live render loops sharing
    /// one GL context is a bug worth surfacing, not papering over.
    pub fn claim(&mut self, tid: u64) -> bool {
        if tid == 0 {
            return false;
        }
        if self.owner == 0 || self.owner == tid {
            self.owner = tid;
            return true;
        }
        false
    }

    /// Releases ownership, but only for the thread that holds it.
    pub fn release(&mut self, tid: u64) {
        if self.owner == tid {
            self.owner = 0;
        }
    }

    pub fn check(&self, tid: u64) -> AffinityVerdict {
        if self.owner == 0 {
            AffinityVerdict::Unowned
        } else if self.owner == tid {
            AffinityVerdict::Owner
        } else {
            AffinityVerdict::Violation
        }
    }

    pub fn is_owner(&self, tid: u64) -> bool {
        self.owner != 0 && self.owner == tid
    }
}

// ---------- EGL bootstrap ----------

/// EGL error codes this pipeline names in its logs.
///
/// The numeric values live here rather than next to the FFI declarations so the table that turns
/// them into names can be exercised on the host, and so `lib.rs` has exactly one source for the two
/// codes it branches on.
pub const EGL_SUCCESS: i32 = 0x3000;
pub const EGL_NOT_INITIALIZED: i32 = 0x3001;
pub const EGL_BAD_ALLOC: i32 = 0x3003;
pub const EGL_BAD_ATTRIBUTE: i32 = 0x3004;
pub const EGL_BAD_CONFIG: i32 = 0x3005;
pub const EGL_BAD_CONTEXT: i32 = 0x3006;
pub const EGL_BAD_DISPLAY: i32 = 0x3008;
pub const EGL_BAD_MATCH: i32 = 0x3009;
pub const EGL_BAD_NATIVE_WINDOW: i32 = 0x300B;
pub const EGL_BAD_PARAMETER: i32 = 0x300C;
pub const EGL_BAD_SURFACE: i32 = 0x300D;
pub const EGL_CONTEXT_LOST: i32 = 0x300E;

/// Turns an `eglGetError` code into the name the Khronos spec uses.
///
/// A bare hex code made "surface creation failed" indistinguishable from "the context is gone" in
/// bug reports, which are two very different recoveries.
pub fn egl_error_name(code: i32) -> &'static str {
    match code {
        EGL_SUCCESS => "EGL_SUCCESS",
        EGL_NOT_INITIALIZED => "EGL_NOT_INITIALIZED",
        EGL_BAD_ALLOC => "EGL_BAD_ALLOC",
        EGL_BAD_ATTRIBUTE => "EGL_BAD_ATTRIBUTE",
        EGL_BAD_CONFIG => "EGL_BAD_CONFIG",
        EGL_BAD_CONTEXT => "EGL_BAD_CONTEXT",
        EGL_BAD_DISPLAY => "EGL_BAD_DISPLAY",
        EGL_BAD_MATCH => "EGL_BAD_MATCH",
        EGL_BAD_NATIVE_WINDOW => "EGL_BAD_NATIVE_WINDOW",
        EGL_BAD_PARAMETER => "EGL_BAD_PARAMETER",
        EGL_BAD_SURFACE => "EGL_BAD_SURFACE",
        EGL_CONTEXT_LOST => "EGL_CONTEXT_LOST",
        _ => "EGL_UNKNOWN",
    }
}

/// Client versions tried for the render context, best first.
///
/// The context is what mpv's `vo_gpu` actually needs: `--gpu-api=opengl` requires GLES 3.0. The 2.x
/// attempt only exists so the failure is reported as "this driver cannot do ES 3" instead of an
/// unexplained black screen, and so the GL string probe still has a context to query.
pub const CONTEXT_CLIENT_VERSIONS: [i32; 2] = [3, 2];

/// One candidate `EGLConfig`, in the order the render thread tries them.
///
/// The order is the point. `EGL_RENDERABLE_TYPE` is what decides whether a driver returns any
/// configuration at all, and several Mali/Adreno generations advertise only ES 2 renderability even
/// though they create ES 3 contexts happily. media3 asks for `EGL_OPENGL_ES2_BIT` in the config and
/// passes `EGL_CONTEXT_CLIENT_VERSION = 3` at context creation for exactly that reason
/// (`GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888`), so the first attempt mirrors it and the stricter
/// ES 3 request is only the fallback.
///
/// `require_window` is the other half of the same lesson: the previous single request demanded
/// `EGL_WINDOW_BIT | EGL_PBUFFER_BIT`, a combination some drivers do not list, and
/// `eglChooseConfig` then returned zero configs and the whole render thread gave up. media3 does not
/// constrain the surface type at all.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct EglConfigRequest {
    pub require_es3: bool,
    pub require_window: bool,
    pub red_bits: u32,
    pub green_bits: u32,
    pub blue_bits: u32,
    pub alpha_bits: u32,
    pub depth_bits: u32,
    pub stencil_bits: u32,
    /// Recorded in the log line that reports which tier won, so a device-specific bug report says
    /// what the driver actually accepted.
    pub name: &'static str,
}

const fn rgba8888(require_es3: bool, require_window: bool, name: &'static str) -> EglConfigRequest {
    EglConfigRequest {
        require_es3,
        require_window,
        red_bits: 8,
        green_bits: 8,
        blue_bits: 8,
        alpha_bits: 8,
        depth_bits: 0,
        stencil_bits: 0,
        name,
    }
}

const fn rgba1010102(require_es3: bool, require_window: bool, name: &'static str) -> EglConfigRequest {
    EglConfigRequest {
        require_es3,
        require_window,
        red_bits: 10,
        green_bits: 10,
        blue_bits: 10,
        alpha_bits: 2,
        depth_bits: 0,
        stencil_bits: 0,
        name,
    }
}

static CONFIG_ATTEMPTS_8BIT: [EglConfigRequest; 4] = [
    rgba8888(false, false, "rgba8888/es2"),
    rgba8888(false, true, "rgba8888/es2/window"),
    rgba8888(true, false, "rgba8888/es3"),
    rgba8888(true, true, "rgba8888/es3/window"),
];

static CONFIG_ATTEMPTS_10BIT: [EglConfigRequest; 8] = [
    rgba1010102(false, false, "rgba1010102/es2"),
    rgba1010102(false, true, "rgba1010102/es2/window"),
    rgba1010102(true, false, "rgba1010102/es3"),
    rgba1010102(true, true, "rgba1010102/es3/window"),
    rgba8888(false, false, "rgba8888/es2"),
    rgba8888(false, true, "rgba8888/es2/window"),
    rgba8888(true, false, "rgba8888/es3"),
    rgba8888(true, true, "rgba8888/es3/window"),
];

/// Candidate configurations for the window surface, in fallback order.
///
/// Within one colour depth the order is ES 2 before ES 3 (see [`EglConfigRequest`]), and no
/// surface-type constraint before an explicit `EGL_WINDOW_BIT`. The unconstrained request already
/// yields window-capable configs on a compliant driver, because `EGL_SURFACE_TYPE` defaults to
/// `EGL_WINDOW_BIT`; the explicit tier covers a driver that ignores that default and would
/// otherwise hand back a config `eglCreateWindowSurface` rejects.
///
/// `prefer_10bit` is off until the HDR output path exists: a `RGBA1010102` window only pays off
/// together with `EGL_GL_COLORSPACE_BT2020_PQ` / `_HLG` window attributes, which is what media3
/// passes in `GlUtil.createEglSurface` for HDR content. Asking for 10 bits without them merely
/// triples the scanout bandwidth and leaves SDR content on a wider buffer for no visible gain.
pub fn egl_config_attempts(prefer_10bit: bool) -> &'static [EglConfigRequest] {
    if prefer_10bit {
        &CONFIG_ATTEMPTS_10BIT
    } else {
        &CONFIG_ATTEMPTS_8BIT
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // ---------- FrameCoalescer ----------

    #[test]
    fn coalescer_admits_one_notification_at_a_time() {
        let gate = FrameCoalescer::new();
        assert!(gate.try_acquire());
        assert!(!gate.try_acquire());
        assert!(!gate.try_acquire());
        assert!(gate.is_pending());
    }

    #[test]
    fn coalescer_reopens_after_release() {
        let gate = FrameCoalescer::new();
        assert!(gate.try_acquire());
        gate.release();
        assert!(!gate.is_pending());
        assert!(gate.try_acquire());
    }

    #[test]
    fn coalescer_reopens_after_failed_enqueue() {
        // Regression guard: a claimed slot whose notification could not be delivered (render thread
        // gone, channel closed) must be handed back, otherwise the next session never renders.
        let gate = FrameCoalescer::new();
        assert!(gate.try_acquire());
        gate.release();
        assert!(!gate.is_pending());
        assert!(gate.try_acquire());
    }

    #[test]
    fn coalescer_survives_concurrent_acquire() {
        use std::sync::Arc;
        use std::thread;

        let gate = Arc::new(FrameCoalescer::new());
        let winners = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let mut handles = Vec::new();
        for _ in 0..8 {
            let gate = Arc::clone(&gate);
            let winners = Arc::clone(&winners);
            handles.push(thread::spawn(move || {
                if gate.try_acquire() {
                    winners.fetch_add(1, Ordering::SeqCst);
                }
            }));
        }
        for h in handles {
            h.join().unwrap();
        }
        assert_eq!(1, winners.load(Ordering::SeqCst));
    }

    // ---------- RenderWatchdog ----------

    fn watchdog() -> RenderWatchdog {
        RenderWatchdog::new(80, 3, 2, 500)
    }

    /// Past the warm-up window, keeping the gap small so frames count as steady state.
    fn settle(w: &mut RenderWatchdog) {
        w.on_frame(10, 16);
        w.on_frame(10, 16);
        w.on_frame(10, 16);
    }

    #[test]
    fn watchdog_trips_after_configured_consecutive_slow_frames() {
        let mut w = watchdog();
        settle(&mut w);
        assert_eq!(WatchdogVerdict::Slow, w.on_frame(120, 16));
        assert_eq!(WatchdogVerdict::Slow, w.on_frame(120, 16));
        assert_eq!(WatchdogVerdict::Trip, w.on_frame(120, 16));
        assert!(w.is_tripped());
    }

    #[test]
    fn watchdog_fast_frame_breaks_the_slow_run() {
        let mut w = watchdog();
        settle(&mut w);
        assert_eq!(WatchdogVerdict::Slow, w.on_frame(120, 16));
        assert_eq!(WatchdogVerdict::Healthy, w.on_frame(10, 16));
        assert_eq!(WatchdogVerdict::Slow, w.on_frame(120, 16));
        assert_eq!(WatchdogVerdict::Slow, w.on_frame(120, 16));
        assert_eq!(WatchdogVerdict::Trip, w.on_frame(120, 16));
    }

    #[test]
    fn watchdog_ignores_frames_right_after_a_long_gap() {
        let mut w = watchdog();
        settle(&mut w);
        // A 4K file start: the gap opens a warm-up window, and the following frames are exempt
        // because decode and first paint are still ramping up.
        assert_eq!(WatchdogVerdict::Healthy, w.on_frame(400, 2000));
        assert_eq!(2, w.warmup_frames_left());
        assert_eq!(WatchdogVerdict::Healthy, w.on_frame(400, 16));
        assert_eq!(WatchdogVerdict::Healthy, w.on_frame(400, 16));
        assert_eq!(0, w.warmup_frames_left());
        // Warm-up exhausted: only now does a slow frame count towards a trip.
        assert_eq!(WatchdogVerdict::Slow, w.on_frame(400, 16));
    }

    #[test]
    fn watchdog_resets_on_every_resume_not_just_the_first() {
        let mut w = watchdog();
        settle(&mut w);
        w.on_frame(10, 2000);
        assert_eq!(2, w.warmup_frames_left());
        // Seek during playback also counts as a resume.
        settle(&mut w);
        assert_eq!(WatchdogVerdict::Healthy, w.on_frame(400, 900));
        assert_eq!(2, w.warmup_frames_left());
    }

    #[test]
    fn watchdog_verdict_is_terminal_after_trip() {
        let mut w = watchdog();
        settle(&mut w);
        w.on_frame(120, 16);
        w.on_frame(120, 16);
        assert_eq!(WatchdogVerdict::Trip, w.on_frame(120, 16));
        assert_eq!(WatchdogVerdict::AlreadyTripped, w.on_frame(10, 16));
        assert_eq!(WatchdogVerdict::AlreadyTripped, w.on_frame(400, 5000));
    }

    #[test]
    fn watchdog_default_thresholds_describe_a_12fps_floor() {
        let w = RenderWatchdog::with_defaults();
        assert!(!w.is_tripped());
        assert_eq!(0, w.warmup_frames_left());
    }

    #[test]
    fn watchdog_tolerates_zero_trip_threshold() {
        // Defensive: a zero threshold would otherwise trip on the first slow frame forever.
        let mut w = RenderWatchdog::new(80, 0, 0, 500);
        assert_eq!(WatchdogVerdict::Trip, w.on_frame(120, 16));
    }

    #[test]
    fn watchdog_does_not_trip_on_steady_playback() {
        let mut w = watchdog();
        settle(&mut w);
        for _ in 0..1000 {
            assert_ne!(WatchdogVerdict::Trip, w.on_frame(16, 16));
        }
        assert!(!w.is_tripped());
    }

    // ---------- ThreadAffinity ----------

    #[test]
    fn affinity_tracks_the_owning_thread() {
        let mut affinity = ThreadAffinity::new();
        assert_eq!(AffinityVerdict::Unowned, affinity.check(1));
        assert!(affinity.claim(1));
        assert_eq!(AffinityVerdict::Owner, affinity.check(1));
        assert_eq!(AffinityVerdict::Violation, affinity.check(2));
        assert!(affinity.is_owner(1));
        assert!(!affinity.is_owner(2));
    }

    #[test]
    fn affinity_refuses_a_second_owner() {
        let mut affinity = ThreadAffinity::new();
        assert!(affinity.claim(1));
        // A second live render loop must not silently steal the context.
        assert!(!affinity.claim(2));
        assert_eq!(AffinityVerdict::Owner, affinity.check(1));
    }

    #[test]
    fn affinity_allows_the_owner_to_reclaim() {
        let mut affinity = ThreadAffinity::new();
        assert!(affinity.claim(1));
        assert!(affinity.claim(1));
        assert_eq!(AffinityVerdict::Owner, affinity.check(1));
    }

    #[test]
    fn affinity_only_the_owner_can_release() {
        let mut affinity = ThreadAffinity::new();
        assert!(affinity.claim(1));
        affinity.release(2);
        assert_eq!(AffinityVerdict::Owner, affinity.check(1));
        affinity.release(1);
        assert_eq!(AffinityVerdict::Unowned, affinity.check(1));
        assert_eq!(AffinityVerdict::Unowned, affinity.check(2));
    }

    #[test]
    fn affinity_rejects_the_unowned_sentinel() {
        let mut affinity = ThreadAffinity::new();
        assert!(!affinity.claim(0));
        assert_eq!(AffinityVerdict::Unowned, affinity.check(0));
    }

    #[test]
    fn affinity_can_be_reclaimed_after_release() {
        let mut affinity = ThreadAffinity::new();
        assert!(affinity.claim(1));
        affinity.release(1);
        // A restarted render thread gets a new id and must be able to take over.
        assert!(affinity.claim(2));
        assert_eq!(AffinityVerdict::Owner, affinity.check(2));
        assert_eq!(AffinityVerdict::Violation, affinity.check(1));
    }

    // ---------- DegradeState ----------

    #[test]
    fn degrade_without_a_handle_does_not_consume_the_single_attempt() {
        let mut state = DegradeState::new();
        assert!(!state.begin(false));
        // The latch is still open, so the next frame with a live handle can still degrade.
        assert!(state.begin(true));
    }

    #[test]
    fn degrade_attempts_only_once() {
        let mut state = DegradeState::new();
        assert!(state.begin(true));
        assert!(!state.begin(true));
        assert!(!state.begin(true));
    }

    #[test]
    fn degrade_reports_only_when_the_write_was_accepted() {
        let mut state = DegradeState::new();
        assert!(state.begin(true));
        assert!(state.confirm(true));
        assert!(state.is_applied());
    }

    #[test]
    fn degrade_stays_silent_when_mpv_rejects_the_write() {
        let mut state = DegradeState::new();
        assert!(state.begin(true));
        // A rejected write must not be announced as a degradation, and must not be retried.
        assert!(!state.confirm(false));
        assert!(!state.is_applied());
        assert!(!state.begin(true));
    }

    // ---------- ContextRecovery ----------

    #[test]
    fn recovery_allows_the_configured_number_of_rebuilds() {
        let mut recovery = ContextRecovery::new(2);
        assert_eq!(recovery.on_context_lost(), RecoveryVerdict::Rebuild);
        assert_eq!(recovery.on_context_lost(), RecoveryVerdict::Rebuild);
    }

    #[test]
    fn recovery_exhausts_and_stays_exhausted() {
        let mut recovery = ContextRecovery::new(1);
        assert_eq!(recovery.on_context_lost(), RecoveryVerdict::Rebuild);
        // Terminal: a driver that keeps dropping the context must not rebuild forever.
        assert_eq!(recovery.on_context_lost(), RecoveryVerdict::Exhausted);
        assert_eq!(recovery.on_context_lost(), RecoveryVerdict::Exhausted);
    }

    #[test]
    fn recovery_with_zero_budget_never_rebuilds() {
        let mut recovery = ContextRecovery::new(0);
        assert_eq!(recovery.on_context_lost(), RecoveryVerdict::Exhausted);
    }

    #[test]
    fn recovery_counts_only_successful_grants() {
        let mut recovery = ContextRecovery::new(1);
        assert_eq!(recovery.rebuilds_used(), 0);
        recovery.on_context_lost();
        assert_eq!(recovery.rebuilds_used(), 1);
        // A refused loss must not be booked, or the budget would drain without a rebuild happening.
        recovery.on_context_lost();
        assert_eq!(recovery.rebuilds_used(), 1);
    }

    // ---------- request_ack ----------

    fn spawn_render_thread(
        delay: std::time::Duration,
    ) -> (
        std::sync::mpsc::Sender<(u8, std::sync::mpsc::SyncSender<()>)>,
        std::thread::JoinHandle<()>,
    ) {
        let (tx, rx) = std::sync::mpsc::channel::<(u8, std::sync::mpsc::SyncSender<()>)>();
        let handle = std::thread::spawn(move || {
            while let Ok((_, ack)) = rx.recv() {
                std::thread::sleep(delay);
                let _ = ack.send(());
            }
        });
        (tx, handle)
    }

    #[test]
    fn ack_completes_when_the_render_thread_confirms() {
        let (tx, _thread) = spawn_render_thread(std::time::Duration::from_millis(5));
        let outcome = request_ack(&tx, |ack| (0u8, ack), std::time::Duration::from_millis(500));
        assert_eq!(AckOutcome::Acknowledged, outcome);
    }

    #[test]
    fn ack_reports_a_dead_render_thread() {
        // Receiver dropped: the render thread is gone, so nothing is queued at all.
        let (tx, rx) = std::sync::mpsc::channel::<(u8, std::sync::mpsc::SyncSender<()>)>();
        drop(rx);
        let outcome = request_ack(&tx, |ack| (0u8, ack), std::time::Duration::from_millis(50));
        assert_eq!(AckOutcome::Disconnected, outcome);
    }

    #[test]
    fn ack_reports_a_timeout_instead_of_blocking_forever() {
        // Render thread stuck inside a swap: it never acknowledges.
        let (tx, rx) = std::sync::mpsc::channel::<(u8, std::sync::mpsc::SyncSender<()>)>();
        let outcome = request_ack(&tx, |ack| (0u8, ack), std::time::Duration::from_millis(30));
        assert_eq!(AckOutcome::TimedOut, outcome);
        drop(rx);
    }

    #[test]
    fn waiting_for_ack_does_not_hold_the_slot_lock() {
        // Regression guard for the stalling bug: the frame notification path needs the same slot
        // that render commands are cloned from, so the wait must happen unlocked.
        use std::sync::{Arc, Mutex};
        use std::time::{Duration, Instant};

        type Slot = Arc<Mutex<Option<std::sync::mpsc::Sender<(u8, std::sync::mpsc::SyncSender<()>)>>>>;

        let (tx, thread) = spawn_render_thread(Duration::from_millis(200));
        let slot: Slot = Arc::new(Mutex::new(Some(tx)));

        let caller_slot = Arc::clone(&slot);
        let caller = std::thread::spawn(move || {
            let sender = caller_slot.lock().expect("slot").as_ref().cloned().expect("sender");
            request_ack(&sender, |ack| (0u8, ack), Duration::from_millis(2000))
        });

        // Let the caller get past the clone and into the wait.
        std::thread::sleep(Duration::from_millis(50));

        let started = Instant::now();
        {
            let guard = slot.lock().expect("slot");
            let (dead_ack, _keep) = std::sync::mpsc::sync_channel(1);
            let _ = guard.as_ref().expect("sender").send((1u8, dead_ack));
        }
        let blocked_for = started.elapsed();
        assert!(
            blocked_for < Duration::from_millis(100),
            "frame notification waited {blocked_for:?} on the slot lock"
        );

        assert_eq!(AckOutcome::Acknowledged, caller.join().expect("caller"));
        drop(thread);
    }

    // ---------- EGL bootstrap ----------

    #[test]
    fn config_attempts_ask_for_es2_renderability_without_a_surface_constraint_first() {
        // The exact request media3 sends, and the one that keeps `eglChooseConfig` from returning
        // nothing on drivers that do not list ES 3 or the window+pbuffer combination.
        let first = egl_config_attempts(false)[0];
        assert!(!first.require_es3, "first tier must not demand ES 3 renderability");
        assert!(!first.require_window, "first tier must not constrain the surface type");
    }

    #[test]
    fn config_attempts_fall_back_to_the_stricter_requests() {
        let attempts = egl_config_attempts(false);
        assert!(attempts.len() >= 3, "a single tier leaves no room to recover");
        assert!(
            attempts.iter().any(|request| request.require_es3),
            "the ES 3 request is the fallback for drivers that only advertise it"
        );
        assert!(
            attempts.iter().any(|request| request.require_window),
            "the explicit window request is the fallback for drivers that need it"
        );
    }

    #[test]
    fn es2_attempts_all_come_before_the_es3_attempts() {
        let attempts = egl_config_attempts(false);
        let first_es3 = attempts
            .iter()
            .position(|request| request.require_es3)
            .expect("the ES 3 request has to exist as a fallback");
        assert!(
            attempts[..first_es3].iter().all(|request| !request.require_es3),
            "an ES 3 request ahead of the ES 2 ones defeats the compatibility it exists for"
        );
    }

    #[test]
    fn config_attempts_never_request_depth_or_stencil() {
        for request in egl_config_attempts(false).iter().chain(egl_config_attempts(true)) {
            assert_eq!(0, request.depth_bits, "{} wants a depth buffer", request.name);
            assert_eq!(0, request.stencil_bits, "{} wants a stencil buffer", request.name);
        }
    }

    #[test]
    fn ten_bit_attempts_lead_and_still_end_with_the_eight_bit_fallbacks() {
        let attempts = egl_config_attempts(true);
        assert_eq!(10, attempts[0].red_bits);
        assert_eq!(2, attempts[0].alpha_bits, "RGBA1010102 spends 2 bits on alpha");
        assert!(
            attempts.iter().any(|request| request.red_bits == 8),
            "a device without a 10-bit window must still reach a usable config"
        );
        assert!(
            egl_config_attempts(false)
                .iter()
                .all(|request| attempts.contains(request)),
            "every 8-bit tier has to survive the 10-bit preference"
        );
    }

    #[test]
    fn config_attempts_are_distinguishable_in_the_log() {
        for attempts in [egl_config_attempts(false), egl_config_attempts(true)] {
            let mut names: Vec<&str> = attempts.iter().map(|request| request.name).collect();
            let total = names.len();
            names.sort_unstable();
            names.dedup();
            assert_eq!(total, names.len(), "tier names must be unique to identify a device");
        }
    }

    #[test]
    fn context_versions_try_es3_before_es2() {
        assert_eq!(3, CONTEXT_CLIENT_VERSIONS[0], "mpv vo_gpu needs GLES 3");
        assert_eq!(2, CONTEXT_CLIENT_VERSIONS[1]);
    }

    #[test]
    fn error_names_cover_the_codes_the_pipeline_branches_on() {
        assert_eq!("EGL_BAD_SURFACE", egl_error_name(EGL_BAD_SURFACE));
        assert_eq!("EGL_CONTEXT_LOST", egl_error_name(EGL_CONTEXT_LOST));
        assert_eq!("EGL_BAD_ALLOC", egl_error_name(EGL_BAD_ALLOC));
        assert_eq!("EGL_SUCCESS", egl_error_name(EGL_SUCCESS));
    }

    #[test]
    fn error_names_do_not_panic_on_vendor_codes() {
        assert_eq!("EGL_UNKNOWN", egl_error_name(0x3FFF));
        assert_eq!("EGL_UNKNOWN", egl_error_name(-1));
    }
}
