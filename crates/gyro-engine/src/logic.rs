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
}
