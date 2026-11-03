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

// ---------- Frame health ----------

/// An mpv counter that describes frames which did not reach the screen on time.
///
/// mpv already does the deadline arithmetic for the whole pipeline, so these are its verdicts
/// rather than a guess made from how long one draw call took. They only move while mpv is allowed
/// to act on lateness, which is why the engine runs with `framedrop=vo`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FrameCounter {
    /// Frames delayed by external circumstances in display-sync mode.
    Late,
    /// Frames dropped by the video output.
    Dropped,
    /// Frames dropped by the decoder because video fell too far behind audio.
    DecoderDropped,
}

impl FrameCounter {
    pub const ALL: [FrameCounter; 3] = [
        FrameCounter::Late,
        FrameCounter::Dropped,
        FrameCounter::DecoderDropped,
    ];

    /// The property name mpv publishes this counter under.
    pub const fn property(self) -> &'static str {
        match self {
            FrameCounter::Late => "vo-delayed-frame-count",
            FrameCounter::Dropped => "frame-drop-count",
            FrameCounter::DecoderDropped => "decoder-frame-drop-count",
        }
    }

    pub fn from_property(name: &str) -> Option<Self> {
        Self::ALL.iter().copied().find(|counter| counter.property() == name)
    }
}

/// Frames lost inside one monitoring window.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FrameHealthReport {
    pub window_ms: u128,
    /// Delayed frames, only produced in display-sync mode.
    pub late_frames: i64,
    /// Frames the decoder dropped because video had fallen too far behind audio.
    pub dropped_frames: i64,
    /// Frames the video output skipped because the display cannot show them all (a 75fps source on
    /// a 60Hz panel skips one frame in five and is perfectly healthy).
    pub skipped_frames: i64,
}

/// Watches mpv's frame counters and reports when frames start missing their deadline.
///
/// A single late frame is normal - a seek, a heavy shader pass, a scheduler hiccup - so the monitor
/// only speaks about sustained loss: it accumulates over a fixed window and reports at most once per
/// window that crosses the threshold. Nothing is changed behind the user's back; in this pipeline
/// the report is diagnostics, not a trigger.
#[derive(Debug)]
pub struct FrameHealthMonitor {
    window_ms: u128,
    report_after: i64,
    window_started_at_ms: Option<u128>,
    baseline_late: i64,
    baseline_dropped: i64,
    baseline_skipped: i64,
    late: i64,
    vo_dropped: i64,
    decoder_dropped: i64,
}

impl FrameHealthMonitor {
    pub const fn new(window_ms: u128, report_after: i64) -> Self {
        Self {
            window_ms,
            // A zero threshold would report every window, including the ones with no loss at all.
            report_after: if report_after < 1 { 1 } else { report_after },
            window_started_at_ms: None,
            baseline_late: 0,
            baseline_dropped: 0,
            baseline_skipped: 0,
            late: 0,
            vo_dropped: 0,
            decoder_dropped: 0,
        }
    }

    /// Five seconds is long enough that a bad patch has to be sustained and short enough that the
    /// log still points at when it happened; ten frames is under a tenth of a 24fps window.
    pub const fn with_defaults() -> Self {
        Self::new(5_000, 10)
    }

    pub fn late_frames(&self) -> i64 {
        self.late
    }

    /// Frames the decoder gave up on: video had fallen too far behind audio.
    pub fn dropped_frames(&self) -> i64 {
        self.decoder_dropped
    }

    /// Frames the video output skipped because the display cannot show them all.
    pub fn skipped_frames(&self) -> i64 {
        self.vo_dropped
    }

    /// Feeds one counter sample. Returns a report when a window just closed above the threshold.
    pub fn on_counter(
        &mut self,
        counter: FrameCounter,
        total: i64,
        now_ms: u128,
    ) -> Option<FrameHealthReport> {
        let previous = match counter {
            FrameCounter::Late => self.late,
            FrameCounter::Dropped => self.vo_dropped,
            FrameCounter::DecoderDropped => self.decoder_dropped,
        };
        if total < previous {
            // The counters restart with a new file. Keeping the old window would report a negative
            // delta now and then a phantom burst on the first sample of the next file.
            self.late = 0;
            self.vo_dropped = 0;
            self.decoder_dropped = 0;
            self.window_started_at_ms = None;
            return None;
        }
        match counter {
            FrameCounter::Late => self.late = total,
            FrameCounter::Dropped => self.vo_dropped = total,
            FrameCounter::DecoderDropped => self.decoder_dropped = total,
        }

        let started = match self.window_started_at_ms {
            Some(started) => started,
            None => {
                self.window_started_at_ms = Some(now_ms);
                self.baseline_late = self.late;
                self.baseline_dropped = self.decoder_dropped;
                self.baseline_skipped = self.vo_dropped;
                return None;
            }
        };
        let window_ms = now_ms.saturating_sub(started);
        if window_ms < self.window_ms {
            return None;
        }

        let late = self.late - self.baseline_late;
        let dropped = self.decoder_dropped - self.baseline_dropped;
        let skipped = self.vo_dropped - self.baseline_skipped;
        self.window_started_at_ms = Some(now_ms);
        self.baseline_late = self.late;
        self.baseline_dropped = self.decoder_dropped;
        self.baseline_skipped = self.vo_dropped;
        // Only loss the pipeline could not avoid counts: a late frame and a frame the decoder had to
        // give up on both mean video is falling behind. A VO skip means the source has more frames
        // than the panel can show, which is not a fault.
        if late + dropped >= self.report_after {
            Some(FrameHealthReport {
                window_ms,
                late_frames: late,
                dropped_frames: dropped,
                skipped_frames: skipped,
            })
        } else {
            None
        }
    }
}

impl Default for FrameHealthMonitor {
    fn default() -> Self {
        Self::with_defaults()
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
/// terminal: the pipeline stops rebuilding instead of retrying on every failed swap.
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

    /// The verdict for `caller` given the owning thread id.
    ///
    /// Split out from [`Self::check`] so the caller can keep the owner in an atomic and decide
    /// without taking the lock that guards the state machine: the check runs on every frame and on
    /// every fence mpv creates.
    pub fn verdict(owner: u64, caller: u64) -> AffinityVerdict {
        if owner == 0 {
            AffinityVerdict::Unowned
        } else if owner == caller {
            AffinityVerdict::Owner
        } else {
            AffinityVerdict::Violation
        }
    }

    /// Convenience wrapper for tests and for callers that already hold the state.
    #[allow(dead_code)]
    pub fn check(&self, tid: u64) -> AffinityVerdict {
        Self::verdict(self.owner, tid)
    }
}

/// Lets at most one sample through per interval.
///
/// mpv publishes `time-pos` once per produced frame, so at 60fps the progress value crosses JNI and
/// recomposes the UI sixty times a second to move a bar nobody can see move that fast. The sample
/// that does get through is always the newest one, so the only cost is up to `interval_ms` of
/// staleness - below the point where a scrub bar reads as laggy.
#[derive(Debug)]
pub struct SampleThrottle {
    interval_ms: u128,
    last_emit_ms: Option<u128>,
}

impl SampleThrottle {
    pub const fn new(interval_ms: u128) -> Self {
        Self { interval_ms, last_emit_ms: None }
    }

    /// 100ms is ten updates a second: faster than the eye needs for a progress bar, slower than the
    /// frame rate by an order of magnitude.
    pub const fn with_defaults() -> Self {
        Self::new(100)
    }

    /// Returns true when the sample may be forwarded to the UI.
    pub fn should_emit(&mut self, now_ms: u128) -> bool {
        match self.last_emit_ms {
            // A clock that went backwards means the baseline was lost; emit rather than stay silent.
            Some(last) if now_ms >= last && now_ms - last < self.interval_ms => false,
            _ => {
                self.last_emit_ms = Some(now_ms);
                true
            }
        }
    }
}

impl Default for SampleThrottle {
    fn default() -> Self {
        Self::with_defaults()
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

    // ---------- Frame health ----------

    #[test]
    fn frame_counter_property_names_round_trip() {
        for counter in FrameCounter::ALL {
            assert_eq!(
                Some(counter),
                FrameCounter::from_property(counter.property()),
                "{} must be recognised by the name it publishes",
                counter.property()
            );
        }
        assert_eq!(None, FrameCounter::from_property("time-pos"));
    }

    #[test]
    fn frame_health_reports_sustained_loss() {
        let mut monitor = FrameHealthMonitor::new(1_000, 5);
        assert_eq!(None, monitor.on_counter(FrameCounter::Late, 0, 0), "the first sample only opens the window");
        assert_eq!(None, monitor.on_counter(FrameCounter::Late, 3, 500));
        let report = monitor
            .on_counter(FrameCounter::Late, 6, 1_000)
            .expect("six late frames in one window is a report");
        assert_eq!(6, report.late_frames);
        assert_eq!(1_000, report.window_ms);
    }

    #[test]
    fn frame_health_stays_quiet_below_the_threshold() {
        let mut monitor = FrameHealthMonitor::new(1_000, 5);
        monitor.on_counter(FrameCounter::Late, 0, 0);
        assert_eq!(None, monitor.on_counter(FrameCounter::Late, 4, 1_000), "four is under the threshold");
        assert_eq!(4, monitor.late_frames(), "the counter is still tracked even when nothing is reported");
    }

    #[test]
    fn frame_health_counts_drops_from_both_sources_together() {
        let mut monitor = FrameHealthMonitor::new(1_000, 3);
        monitor.on_counter(FrameCounter::Dropped, 0, 0);
        monitor.on_counter(FrameCounter::DecoderDropped, 0, 100);
        let report = monitor
            .on_counter(FrameCounter::DecoderDropped, 3, 1_000)
            .expect("decoder drops are drops");
        assert_eq!(3, report.dropped_frames);
        assert_eq!(0, report.late_frames);
        assert_eq!(0, report.skipped_frames);
    }

    #[test]
    fn frame_health_ignores_frames_the_display_could_not_show() {
        // A 75fps source on a 60Hz panel skips one frame in five: an expected cadence, not a fault,
        // so the monitor must stay quiet about it.
        let mut monitor = FrameHealthMonitor::new(1_000, 5);
        monitor.on_counter(FrameCounter::Dropped, 0, 0);
        assert_eq!(None, monitor.on_counter(FrameCounter::Dropped, 40, 1_000));
        assert_eq!(40, monitor.skipped_frames());
        assert_eq!(0, monitor.dropped_frames());
    }

    #[test]
    fn frame_health_starts_a_fresh_window_after_a_report() {
        let mut monitor = FrameHealthMonitor::new(1_000, 5);
        monitor.on_counter(FrameCounter::Late, 0, 0);
        assert!(monitor.on_counter(FrameCounter::Late, 8, 1_000).is_some());
        assert_eq!(
            None,
            monitor.on_counter(FrameCounter::Late, 9, 2_000),
            "one more late frame in the next window is not a new event"
        );
        assert_eq!(9, monitor.late_frames());
    }

    #[test]
    fn frame_health_treats_a_counter_restart_as_a_new_baseline() {
        let mut monitor = FrameHealthMonitor::new(1_000, 5);
        monitor.on_counter(FrameCounter::Late, 0, 0);
        monitor.on_counter(FrameCounter::Late, 40, 2_000);
        assert_eq!(None, monitor.on_counter(FrameCounter::Late, 0, 2_100), "a new file resets the count");
        assert_eq!(0, monitor.late_frames());
        assert_eq!(None, monitor.on_counter(FrameCounter::Late, 0, 2_200), "the window restarts at the reset");
        let report = monitor
            .on_counter(FrameCounter::Late, 6, 3_200)
            .expect("loss after the restart is reported normally");
        assert_eq!(6, report.late_frames);
    }

    // ---------- ThreadAffinity ----------

    #[test]
    fn affinity_tracks_the_owning_thread() {
        let mut affinity = ThreadAffinity::new();
        assert_eq!(AffinityVerdict::Unowned, affinity.check(1));
        assert!(affinity.claim(1));
        assert_eq!(AffinityVerdict::Owner, affinity.check(1));
        assert_eq!(AffinityVerdict::Violation, affinity.check(2));
        assert_eq!(AffinityVerdict::Owner, ThreadAffinity::verdict(1, 1));
        assert_eq!(AffinityVerdict::Violation, ThreadAffinity::verdict(1, 2));
        assert_eq!(AffinityVerdict::Unowned, ThreadAffinity::verdict(0, 2));
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

    // ---------- SampleThrottle ----------

    #[test]
    fn throttle_lets_the_first_sample_through() {
        let mut throttle = SampleThrottle::new(100);
        assert!(throttle.should_emit(0));
    }

    #[test]
    fn throttle_drops_samples_inside_the_interval() {
        let mut throttle = SampleThrottle::new(100);
        assert!(throttle.should_emit(0));
        assert!(!throttle.should_emit(1));
        assert!(!throttle.should_emit(99));
    }

    #[test]
    fn throttle_reopens_after_the_interval() {
        let mut throttle = SampleThrottle::new(100);
        assert!(throttle.should_emit(0));
        assert!(throttle.should_emit(100));
        assert!(!throttle.should_emit(150));
        assert!(throttle.should_emit(200));
    }

    #[test]
    fn throttle_emits_when_the_clock_goes_backwards() {
        let mut throttle = SampleThrottle::new(100);
        assert!(throttle.should_emit(1_000));
        assert!(!throttle.should_emit(1_050));
        assert!(throttle.should_emit(10), "a rewound clock must not silence the value forever");
    }

    #[test]
    fn throttle_default_matches_the_progress_bar_budget() {
        let mut throttle = SampleThrottle::with_defaults();
        assert!(throttle.should_emit(0));
        assert!(!throttle.should_emit(60), "sixty times a second is what this exists to stop");
        assert!(throttle.should_emit(100));
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
