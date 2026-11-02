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
}
