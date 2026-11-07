//! Gyro Engine: libmpv 核心 JNI 桥与完全下沉的纯原生 EGL 渲染管线
//!
//! 模块职责划分：
//! - `logic`: 纯状态机与数值决策算法（纯 Rust，无外部依赖，宿主可单测）
//! - `mpv`: libmpv 动态加载表、FFI 绑定与生命周期守卫
//! - `render`: EGL / OpenGL ES 渲染管线、常驻渲染线程、Surface 绑定与 VSync 硬件时间戳
//! - `events`: Kotlin 回调与 mpv 事件泵线程
//! - `jni`: 对外导出的 JNI 原生方法 (`GyroNative`)

pub mod events;
pub mod jni;
pub mod logic;
pub mod mpv;
pub mod render;

use std::sync::atomic::Ordering;
use std::sync::OnceLock;
use std::time::Instant;

#[cfg(target_os = "android")]
#[link(name = "log")]
extern "C" {
    fn __android_log_write(
        prio: std::ffi::c_int,
        tag: *const std::ffi::c_char,
        text: *const std::ffi::c_char,
    ) -> std::ffi::c_int;
}

pub fn nlog(msg: &str) {
    #[cfg(target_os = "android")]
    {
        if let (Ok(text), Ok(tag)) = (
            std::ffi::CString::new(msg),
            std::ffi::CString::new("Gyro/Engine"),
        ) {
            // Priority 4 (INFO), not 3 (DEBUG): user builds filter DEBUG from third-party apps.
            unsafe { __android_log_write(4, tag.as_ptr(), text.as_ptr()) };
        }
    }
    #[cfg(not(target_os = "android"))]
    let _ = msg;
}

pub fn tracelog(stage: &str, details: &str) {
    #[cfg(target_os = "android")]
    {
        let msg = if details.is_empty() {
            format!("(Rust) ├─ {stage}")
        } else {
            format!("(Rust) ├─ {stage} {details}")
        };
        if let (Ok(text), Ok(tag)) = (
            std::ffi::CString::new(msg),
            std::ffi::CString::new("SWITCH_TRACE"),
        ) {
            unsafe { __android_log_write(4, tag.as_ptr(), text.as_ptr()) };
        }
    }
    #[cfg(not(target_os = "android"))]
    let _ = (stage, details);
}

#[cfg(unix)]
mod libc {
    use std::ffi::{c_int, c_long};
    #[repr(C)]
    pub struct timespec {
        pub tv_sec: c_long,
        pub tv_nsec: c_long,
    }
    #[cfg(target_os = "macos")]
    pub const CLOCK_MONOTONIC: c_int = 6;
    #[cfg(not(target_os = "macos"))]
    pub const CLOCK_MONOTONIC: c_int = 1;

    extern "C" {
        pub fn clock_gettime(clk_id: c_int, tp: *mut timespec) -> c_int;
    }
}

static G_MONOTONIC_BASE: OnceLock<Instant> = OnceLock::new();

/// Monotonic nanoseconds, process-wide.
///
/// Contract: non-decreasing and self-consistent within the process — nothing more. On unix this
/// reads CLOCK_MONOTONIC (boot-relative, effectively always positive); on non-unix hosts it
/// measures elapsed time from a lazily-initialized base, so the very first sample can legitimately
/// be 0. Consumers (presentation-time conversion, throttle/health windows) depend only on
/// monotonicity and cross-call consistency, never on absolute magnitude — do not assert `> 0`
/// against an arbitrary sample.
pub fn monotonic_ns() -> i64 {
    #[cfg(unix)]
    unsafe {
        let mut ts = libc::timespec { tv_sec: 0, tv_nsec: 0 };
        if libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut ts) == 0 {
            return (ts.tv_sec as i64)
                .saturating_mul(1_000_000_000)
                .saturating_add(ts.tv_nsec as i64);
        }
        0
    }
    #[cfg(not(unix))]
    {
        G_MONOTONIC_BASE.get_or_init(Instant::now).elapsed().as_nanos() as i64
    }
}

pub fn monotonic_ms() -> u128 {
    G_MONOTONIC_BASE.get_or_init(Instant::now).elapsed().as_millis()
}

/// Centralizes per-session lifecycle state reset so `create()`, `destroy()`, and
/// `RenderCommand::Shutdown` leave no stale flags, counters, throttles, or GL function pointers
/// behind across engine restarts.
pub fn reset_engine_lifecycle_state() {
    render::G_FRAME_GATE.release();
    render::G_RENDER_DEAD.store(false, Ordering::Release);
    render::G_AWAITING_FIRST_FRAME.store(false, Ordering::Release);
    render::G_FILE_LOADED_REACHED.store(false, Ordering::Release);
    render::G_REARM_VIDEO.store(false, Ordering::Release);
    render::G_PENDING_WID_REBIND.store(false, Ordering::Release);
    render::G_LOAD_STARTED_MS.store(0, Ordering::Release);
    render::G_CACHED_DWIDTH.store(0, Ordering::Release);
    render::G_CACHED_DHEIGHT.store(0, Ordering::Release);
    render::G_CACHED_ROTATE.store(0, Ordering::Release);
    render::G_FRAMES_DRAWN.store(0, Ordering::Release);
    render::G_FRAMES_STARVED.store(0, Ordering::Release);
    render::G_LAST_DRAW_MS.store(0, Ordering::Release);
    render::REAL_GL_FENCE_SYNC.store(std::ptr::null_mut(), Ordering::Release);
    render::REAL_GL_DELETE_SYNC.store(std::ptr::null_mut(), Ordering::Release);
    render::REAL_EGL_PRESENTATION_TIME.store(std::ptr::null_mut(), Ordering::Release);
    render::EGL_PRESENTATION_RESOLVED.store(false, Ordering::Release);
    if let Ok(mut fences) = render::ACTIVE_FENCES.lock() {
        fences.clear();
    }
    if let Ok(mut health) = events::G_FRAME_HEALTH.lock() {
        *health = logic::FrameHealthMonitor::with_defaults();
    }
    if let Ok(mut tp) = events::G_TIME_POS_THROTTLE.lock() {
        *tp = logic::SampleThrottle::with_defaults();
    }
    if let Ok(mut ct) = events::G_CACHE_TIME_THROTTLE.lock() {
        *ct = logic::SampleThrottle::with_defaults();
    }
    #[cfg(target_os = "android")]
    if let Ok(mut recovery) = render::G_RECOVERY.lock() {
        *recovery = logic::ContextRecovery::with_defaults();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::c_void;
    use std::sync::Mutex;

    /// Tests in this module mutate the same global lifecycle statics; cargo runs tests in
    /// parallel threads, so any two of them touching e.g. G_CACHED_* race (measured: ~25% of
    /// runs). Serialize the state-mutating tests over this lock.
    static LIFECYCLE_STATE_TESTS: Mutex<()> = Mutex::new(());

    #[test]
    fn reset_engine_lifecycle_state_clears_all_session_flags_and_pointers() {
        let _state_guard = LIFECYCLE_STATE_TESTS.lock().unwrap_or_else(|p| p.into_inner());
        let _ = render::G_FRAME_GATE.try_acquire();
        render::G_RENDER_DEAD.store(true, Ordering::Release);
        render::G_AWAITING_FIRST_FRAME.store(true, Ordering::Release);
        render::G_FILE_LOADED_REACHED.store(true, Ordering::Release);
        render::G_REARM_VIDEO.store(true, Ordering::Release);
        render::G_PENDING_WID_REBIND.store(true, Ordering::Release);
        render::G_LOAD_STARTED_MS.store(12345, Ordering::Release);
        render::G_CACHED_DWIDTH.store(1920, Ordering::Release);
        render::G_CACHED_DHEIGHT.store(1080, Ordering::Release);
        render::G_CACHED_ROTATE.store(90, Ordering::Release);
        render::G_FRAMES_DRAWN.store(100, Ordering::Release);
        render::G_FRAMES_STARVED.store(50, Ordering::Release);
        render::G_LAST_DRAW_MS.store(999, Ordering::Release);
        render::REAL_GL_FENCE_SYNC.store(0x1234 as *mut c_void, Ordering::Release);
        render::REAL_GL_DELETE_SYNC.store(0x5678 as *mut c_void, Ordering::Release);
        render::REAL_EGL_PRESENTATION_TIME.store(0x9999 as *mut c_void, Ordering::Release);
        render::EGL_PRESENTATION_RESOLVED.store(true, Ordering::Release);
        if let Ok(mut fences) = render::ACTIVE_FENCES.lock() {
            fences.push(render::SyncHandle(0xABCD as *mut c_void));
        }

        reset_engine_lifecycle_state();

        assert!(!render::G_FRAME_GATE.is_pending());
        assert!(!render::G_RENDER_DEAD.load(Ordering::Acquire));
        assert!(!render::G_AWAITING_FIRST_FRAME.load(Ordering::Acquire));
        assert!(!render::G_FILE_LOADED_REACHED.load(Ordering::Acquire));
        assert!(!render::G_REARM_VIDEO.load(Ordering::Acquire));
        assert!(!render::G_PENDING_WID_REBIND.load(Ordering::Acquire));
        assert_eq!(0, render::G_LOAD_STARTED_MS.load(Ordering::Acquire));
        assert_eq!(0, render::G_CACHED_DWIDTH.load(Ordering::Acquire));
        assert_eq!(0, render::G_CACHED_DHEIGHT.load(Ordering::Acquire));
        assert_eq!(0, render::G_CACHED_ROTATE.load(Ordering::Acquire));
        assert_eq!(0, render::G_FRAMES_DRAWN.load(Ordering::Acquire));
        assert_eq!(0, render::G_FRAMES_STARVED.load(Ordering::Acquire));
        assert_eq!(0, render::G_LAST_DRAW_MS.load(Ordering::Acquire));
        assert!(render::REAL_GL_FENCE_SYNC.load(Ordering::Acquire).is_null());
        assert!(render::REAL_GL_DELETE_SYNC.load(Ordering::Acquire).is_null());
        assert!(render::REAL_EGL_PRESENTATION_TIME.load(Ordering::Acquire).is_null());
        assert!(!render::EGL_PRESENTATION_RESOLVED.load(Ordering::Acquire));
        assert!(render::ACTIVE_FENCES.lock().unwrap().is_empty());
    }

    #[test]
    fn update_cached_video_geometry_prioritizes_display_dimensions_over_coded() {
        let _state_guard = LIFECYCLE_STATE_TESTS.lock().unwrap_or_else(|p| p.into_inner());
        reset_engine_lifecycle_state();
        events::update_cached_video_geometry("width", 1440);
        events::update_cached_video_geometry("height", 1080);
        assert_eq!(1440, render::G_CACHED_DWIDTH.load(Ordering::Acquire));
        assert_eq!(1080, render::G_CACHED_DHEIGHT.load(Ordering::Acquire));

        events::update_cached_video_geometry("dwidth", 1920);
        events::update_cached_video_geometry("dheight", 1080);
        events::update_cached_video_geometry("video-params/rotate", 90);
        assert_eq!(1920, render::G_CACHED_DWIDTH.load(Ordering::Acquire));
        assert_eq!(1080, render::G_CACHED_DHEIGHT.load(Ordering::Acquire));
        assert_eq!(90, render::G_CACHED_ROTATE.load(Ordering::Acquire));
    }

    #[test]
    fn mpv_lifecycle_guard_prevents_concurrent_access_after_teardown_take() {
        let dummy_ptr = 0xDEADBEEFusize as *mut c_void;
        {
            let mut w = mpv::G_MPV_LIFECYCLE.write().unwrap();
            mpv::G_MPVP.store(dummy_ptr, Ordering::Release);
            mpv::HANDLE_VALID.store(true, Ordering::Release);
            *w = dummy_ptr as usize;
        }

        {
            let (_guard, h) = unsafe { mpv::mpv_handle_guarded().expect("valid handle") };
            assert_eq!(dummy_ptr, h);
        }

        let taken = mpv::take_mpv_handle_for_teardown();
        assert_eq!(dummy_ptr, taken);
        assert!(unsafe { mpv::mpv_handle_guarded().is_err() });
        assert!(unsafe { mpv::mpv_handle().is_err() });
        assert!(!mpv::HANDLE_VALID.load(Ordering::Acquire));
    }

    #[test]
    fn monotonic_ns_returns_positive_increasing_timestamps() {
        // On the Windows host the clock reads elapsed-from-a-lazy-base and the very first
        // sample can legitimately be 0ns; demand positivity only after a real delay.
        let t0 = monotonic_ns();
        std::thread::sleep(std::time::Duration::from_millis(1));
        let t1 = monotonic_ns();
        let t2 = monotonic_ns();
        assert!(t1 > 0);
        assert!(t2 >= t1);
        assert!(t1 >= t0);

        for (prop, expected) in [
            ("time-pos", c"time-pos"),
            ("duration", c"duration"),
            ("pause", c"pause"),
            ("paused-for-cache", c"paused-for-cache"),
            ("demuxer-cache-time", c"demuxer-cache-time"),
            ("speed", c"speed"),
            ("dwidth", c"dwidth"),
            ("dheight", c"dheight"),
            ("width", c"width"),
            ("height", c"height"),
            ("video-out-params/dw", c"video-out-params/dw"),
            ("video-out-params/dh", c"video-out-params/dh"),
            ("video-out-params/rotate", c"video-out-params/rotate"),
            ("video-params/rotate", c"video-params/rotate"),
            ("estimated-vf-fps", c"estimated-vf-fps"),
            ("frame-drop-count", c"frame-drop-count"),
            ("hwdec-current", c"hwdec-current"),
            ("media-title", c"media-title"),
            ("display-fps", c"display-fps"),
            ("sub-delay", c"sub-delay"),
            ("audio-delay", c"audio-delay"),
            ("sub-scale", c"sub-scale"),
            ("sub-pos", c"sub-pos"),
            ("chapter", c"chapter"),
            ("deband", c"deband"),
            ("brightness", c"brightness"),
            ("contrast", c"contrast"),
            ("saturation", c"saturation"),
            ("volume", c"volume"),
            ("af", c"af"),
        ] {
            assert_eq!(jni::match_static_property(prop), Some(expected));
        }
        assert_eq!(jni::match_static_property("unknown-prop"), None);
    }
}
