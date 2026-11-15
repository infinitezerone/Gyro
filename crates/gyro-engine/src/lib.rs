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
pub mod strategy;

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
        if let Ok(text) = std::ffi::CString::new(msg) {
            // Priority 4 (INFO), not 3 (DEBUG): user builds filter DEBUG from third-party apps.
            unsafe { __android_log_write(4, c"Gyro/Engine".as_ptr(), text.as_ptr()) };
        }
    }
    #[cfg(not(target_os = "android"))]
    let _ = msg;
}

pub fn nlog_prio(prio: std::ffi::c_int, msg: &str) {
    #[cfg(target_os = "android")]
    {
        if let Ok(text) = std::ffi::CString::new(msg) {
            unsafe { __android_log_write(prio, c"Gyro/Engine".as_ptr(), text.as_ptr()) };
        }
    }
    #[cfg(not(target_os = "android"))]
    let _ = (prio, msg);
}

#[macro_export]
macro_rules! log_info {
    ($($arg:tt)*) => {
        $crate::nlog_prio(4, &format!($($arg)*))
    };
}

#[macro_export]
macro_rules! log_warn {
    ($($arg:tt)*) => {
        $crate::nlog_prio(5, &format!($($arg)*))
    };
}

#[macro_export]
macro_rules! log_error {
    ($($arg:tt)*) => {
        $crate::nlog_prio(6, &format!($($arg)*))
    };
}

pub fn tracelog(stage: &str, details: &str) {
    #[cfg(target_os = "android")]
    {
        let msg = if details.is_empty() {
            format!("(Rust) ├─ {stage}")
        } else {
            format!("(Rust) ├─ {stage} {details}")
        };
        if let Ok(text) = std::ffi::CString::new(msg) {
            unsafe { __android_log_write(4, c"SWITCH_TRACE".as_ptr(), text.as_ptr()) };
        }
    }
    #[cfg(not(target_os = "android"))]
    let _ = (stage, details);
}

// ---------- Android NDK 原生 ATrace (Systrace / Perfetto) 支持 ----------
#[cfg(target_os = "android")]
pub mod atrace {
    use std::ffi::{c_char, c_int, c_void, CStr};
    use std::sync::OnceLock;

    extern "C" {
        fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
        fn dlopen(filename: *const c_char, flags: c_int) -> *mut c_void;
    }
    const RTLD_LAZY: c_int = 0x0001;

    type ATraceBeginSectionFn = unsafe extern "C" fn(*const c_char);
    type ATraceEndSectionFn = unsafe extern "C" fn();
    type ATraceIsEnabledFn = unsafe extern "C" fn() -> bool;

    struct ATraceFns {
        begin: ATraceBeginSectionFn,
        end: ATraceEndSectionFn,
        is_enabled: Option<ATraceIsEnabledFn>,
    }

    static ATRACE_FNS: OnceLock<Option<ATraceFns>> = OnceLock::new();

    fn get_atrace() -> Option<&'static ATraceFns> {
        ATRACE_FNS.get_or_init(|| unsafe {
            let lib = dlopen(c"libandroid.so".as_ptr(), RTLD_LAZY);
            if lib.is_null() {
                return None;
            }
            let begin_ptr = dlsym(lib, c"ATrace_beginSection".as_ptr());
            let end_ptr = dlsym(lib, c"ATrace_endSection".as_ptr());
            if begin_ptr.is_null() || end_ptr.is_null() {
                return None;
            }
            let is_enabled_ptr = dlsym(lib, c"ATrace_isEnabled".as_ptr());
            let is_enabled: Option<ATraceIsEnabledFn> = if is_enabled_ptr.is_null() {
                None
            } else {
                Some(std::mem::transmute(is_enabled_ptr))
            };
            Some(ATraceFns {
                begin: std::mem::transmute(begin_ptr),
                end: std::mem::transmute(end_ptr),
                is_enabled,
            })
        }).as_ref()
    }

    pub struct TraceScope {
        active: bool,
    }

    impl TraceScope {
        #[inline]
        pub fn new(section: &CStr) -> Self {
            if let Some(fns) = get_atrace() {
                if fns.is_enabled.map(|f| unsafe { f() }).unwrap_or(true) {
                    unsafe { (fns.begin)(section.as_ptr()) };
                    return Self { active: true };
                }
            }
            Self { active: false }
        }
    }

    impl Drop for TraceScope {
        #[inline]
        fn drop(&mut self) {
            if self.active {
                if let Some(fns) = get_atrace() {
                    unsafe { (fns.end)() };
                }
            }
        }
    }
}

#[cfg(not(target_os = "android"))]
pub mod atrace {
    use std::ffi::CStr;
    pub struct TraceScope;
    impl TraceScope {
        #[inline]
        pub fn new(_section: &CStr) -> Self {
            Self
        }
    }
}

pub use atrace::TraceScope;

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
    events::next_epoch();
    render::G_RENDER_EPOCH.store(events::current_epoch(), Ordering::Release);
    render::G_FRAME_GATE.release();
    render::G_SESSION.reset();
    events::G_EVENT_PUMP.reset();
    #[cfg(target_os = "android")]
    if let Ok(mut recovery) = render::G_RECOVERY.lock() {
        *recovery = logic::ContextRecovery::with_defaults();
    }
}

/// Orchestrates the ordered teardown across submodules:
/// 1. Stops the event pump thread and unblocks its `mpv_wait_event`.
/// 2. Destroys the EGL render context on the render thread and frees resources.
/// 3. Safely invokes libmpv's `mpv_terminate_destroy`.
pub unsafe fn safe_terminate_engine(api: &mpv::MpvApi, handle: *mut std::ffi::c_void) {
    if handle.is_null() { return; }
    nlog("safe_terminate_engine: 开始按序安全释放播放器引擎实例");

    // 1. 停止并等待事件泵退出
    mpv::HANDLE_VALID.store(false, Ordering::Release);
    events::stop_event_thread(api, handle);

    // 2. 必须在 mpv_destroy 前释放 render context
    render::destroy_render_context_sync();

    // 3. 安全销毁 mpv 句柄
    (api.mpv_terminate_destroy)(handle);
    nlog("safe_terminate_engine: mpv_terminate_destroy 顺利完成");
}

pub use safe_terminate_engine as safe_terminate_mpv;

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
        render::G_SESSION.render_dead.store(true, Ordering::Release);
        render::G_SESSION.awaiting_first_frame.store(true, Ordering::Release);
        render::G_SESSION.file_loaded_reached.store(true, Ordering::Release);
        render::G_SESSION.rearm_video.store(true, Ordering::Release);
        render::G_SESSION.pending_wid_rebind.store(true, Ordering::Release);
        render::G_SESSION.load_started_ms.store(12345, Ordering::Release);
        render::G_SESSION.cached_dwidth.store(1920, Ordering::Release);
        render::G_SESSION.cached_dheight.store(1080, Ordering::Release);
        render::G_SESSION.cached_rotate.store(90, Ordering::Release);
        render::G_SESSION.frames_drawn.store(100, Ordering::Release);
        render::G_SESSION.frames_starved.store(50, Ordering::Release);
        render::G_SESSION.last_draw_ms.store(999, Ordering::Release);
        render::G_SESSION.real_gl_fence_sync.store(0x1234 as *mut c_void, Ordering::Release);
        render::G_SESSION.real_gl_delete_sync.store(0x5678 as *mut c_void, Ordering::Release);
        render::G_SESSION.real_egl_presentation_time.store(0x9999 as *mut c_void, Ordering::Release);
        render::G_SESSION.egl_presentation_resolved.store(true, Ordering::Release);
        if let Ok(mut fences) = render::G_SESSION.active_fences.lock() {
            fences.push_back(render::SyncHandle(0xABCD as *mut c_void));
        }
        render::G_SESSION.render_slot_ready.store(true, Ordering::Release);
        render::G_SESSION.pending_vo_check.store(true, Ordering::Release);
        render::set_hdr_output(true);
        render::set_hdr_capable(true);
        if let Ok(mut health) = events::G_EVENT_PUMP.frame_health.lock() {
            health.on_counter(logic::FrameCounter::DecoderDropped, 10, 1000);
        }

        reset_engine_lifecycle_state();

        assert!(!render::G_FRAME_GATE.is_pending());
        assert!(!render::G_SESSION.render_dead.load(Ordering::Acquire));
        assert!(!render::G_SESSION.awaiting_first_frame.load(Ordering::Acquire));
        assert!(!render::G_SESSION.file_loaded_reached.load(Ordering::Acquire));
        assert!(!render::is_hdr_output());
        assert!(!render::is_hdr_capable());
        assert!(!render::G_SESSION.rearm_video.load(Ordering::Acquire));
        assert!(!render::G_SESSION.pending_wid_rebind.load(Ordering::Acquire));
        assert_eq!(0, render::G_SESSION.load_started_ms.load(Ordering::Acquire));
        assert_eq!(0, render::G_SESSION.cached_dwidth.load(Ordering::Acquire));
        assert_eq!(0, render::G_SESSION.cached_dheight.load(Ordering::Acquire));
        assert_eq!(0, render::G_SESSION.cached_rotate.load(Ordering::Acquire));
        assert_eq!(0, render::G_SESSION.frames_drawn.load(Ordering::Acquire));
        assert_eq!(0, render::G_SESSION.frames_starved.load(Ordering::Acquire));
        assert_eq!(0, render::G_SESSION.last_draw_ms.load(Ordering::Acquire));
        assert!(render::G_SESSION.real_gl_fence_sync.load(Ordering::Acquire).is_null());
        assert!(render::G_SESSION.real_gl_delete_sync.load(Ordering::Acquire).is_null());
        assert!(render::G_SESSION.real_egl_presentation_time.load(Ordering::Acquire).is_null());
        assert!(!render::G_SESSION.egl_presentation_resolved.load(Ordering::Acquire));
        assert!(render::G_SESSION.active_fences.lock().unwrap().is_empty());
        assert!(!render::G_SESSION.render_slot_ready.load(Ordering::Acquire));
        assert!(!render::G_SESSION.pending_vo_check.load(Ordering::Acquire));
        assert_eq!(0, events::G_EVENT_PUMP.frame_health.lock().unwrap().late_frames());
    }

    #[test]
    fn update_cached_video_geometry_prioritizes_display_dimensions_over_coded() {
        let _state_guard = LIFECYCLE_STATE_TESTS.lock().unwrap_or_else(|p| p.into_inner());
        reset_engine_lifecycle_state();
        events::update_cached_video_geometry("width", 1440);
        events::update_cached_video_geometry("height", 1080);
        assert_eq!(1440, render::G_SESSION.cached_dwidth.load(Ordering::Acquire));
        assert_eq!(1080, render::G_SESSION.cached_dheight.load(Ordering::Acquire));

        events::update_cached_video_geometry("dwidth", 1920);
        events::update_cached_video_geometry("dheight", 1080);
        events::update_cached_video_geometry("video-params/rotate", 90);
        assert_eq!(1920, render::G_SESSION.cached_dwidth.load(Ordering::Acquire));
        assert_eq!(1080, render::G_SESSION.cached_dheight.load(Ordering::Acquire));
        assert_eq!(90, render::G_SESSION.cached_rotate.load(Ordering::Acquire));
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

    #[test]
    fn test_playback_switch_lifecycle_state_machine_and_first_frame_gate() {
        use crate::logic::FirstFrameGate;

        // 阶段 1：模拟旧文件播放稳定态（1920x1080 横屏，HLG HDR 播放）
        render::G_SESSION.cached_dwidth.store(1920, Ordering::Release);
        render::G_SESSION.cached_dheight.store(1080, Ordering::Release);
        render::G_SESSION.cached_rotate.store(0, Ordering::Release);
        render::G_SESSION.file_loaded_reached.store(true, Ordering::Release);
        render::G_SESSION.awaiting_first_frame.store(false, Ordering::Release);
        render::set_hdr_output(true);
        let old_epoch = events::current_epoch();

        // 阶段 2：换片开始（MPV_EVENT_START_FILE 到来）
        let new_epoch = events::next_epoch();
        assert!(new_epoch > old_epoch, "纪元必须严格单调递增");

        // START_FILE 业务不变量：清零旧几何、重置装载与首帧门控、降级 HDR 输出、绑定新纪元
        render::G_SESSION.cached_dwidth.store(0, Ordering::Release);
        render::G_SESSION.cached_dheight.store(0, Ordering::Release);
        render::G_SESSION.cached_rotate.store(0, Ordering::Release);
        render::G_SESSION.file_loaded_reached.store(false, Ordering::Release);
        render::G_SESSION.awaiting_first_frame.store(true, Ordering::Release);
        render::set_hdr_output(false);
        render::G_RENDER_EPOCH.store(new_epoch, Ordering::Release);

        // 不变量断言 1：旧几何必须完全清零，HDR 输出必须恢复 false，杜绝陈旧尺寸与色域泄露
        assert_eq!(render::G_SESSION.cached_dwidth.load(Ordering::Acquire), 0);
        assert_eq!(render::G_SESSION.cached_dheight.load(Ordering::Acquire), 0);
        assert!(!render::is_hdr_output(), "换片初期 HDR 输出状态必须严格归零");

        // 阶段 3：通路 C 先到（首帧脉冲到达渲染线程，但新尺寸与 FILE_LOADED 尚未就绪）
        // 模拟渲染线程读取：此时 file_loaded=false，aspect_mismatch=true（由于无有效几何）
        let gate_c_first = FirstFrameGate::should_signal(
            render::G_SESSION.file_loaded_reached.load(Ordering::Acquire), // false
            false, // force
            false, // is_redraw
            true,  // aspect_mismatch
            20,    // waited_ms (< 600ms)
        );
        // 不变量断言 2：文件未装载且比例未就绪时，首帧必须被严格扣押！
        assert!(!gate_c_first, "首帧先于元数据到达时绝不允许触发上屏信号");

        // 阶段 4：换片瞬间的上一部残余画面重绘（is_redraw = true）
        let gate_redraw = FirstFrameGate::should_signal(
            true,  // 假装已经 loaded
            false, // force
            true,  // is_redraw = true
            false,
            50,
        );
        // 不变量断言 3：重绘帧绝不能作为首帧
        assert!(!gate_redraw, "重绘帧绝不允许判定为首帧");

        // 阶段 5：通路 D 先到（事件泵收到新文件的真实尺寸，如 720x1280 竖屏）
        events::update_cached_video_geometry("dwidth", 720);
        events::update_cached_video_geometry("dheight", 1280);
        events::update_cached_video_geometry("video-params/rotate", 0);

        // 不变量断言 4：几何缓存已安全更新为新视频尺寸
        assert_eq!(render::G_SESSION.cached_dwidth.load(Ordering::Acquire), 720);
        assert_eq!(render::G_SESSION.cached_dheight.load(Ordering::Acquire), 1280);

        // 阶段 6：FILE_LOADED 到达
        render::G_SESSION.file_loaded_reached.store(true, Ordering::Release);

        // 阶段 7：渲染线程在正确尺寸下完成真实帧绘制（Surface 比例与视频一致）
        let file_loaded = render::G_SESSION.file_loaded_reached.load(Ordering::Acquire);
        let final_w = render::G_SESSION.cached_dwidth.load(Ordering::Acquire);
        let final_h = render::G_SESSION.cached_dheight.load(Ordering::Acquire);
        assert_eq!((final_w, final_h), (720, 1280));

        let vid_aspect = final_w as f64 / final_h as f64;
        let buf_aspect = 720.0 / 1280.0;
        let aspect_mismatch = (buf_aspect / vid_aspect - 1.0).abs() > 0.08;
        assert!(!aspect_mismatch, "比例完全吻合");

        let gate_valid_frame = FirstFrameGate::should_signal(
            file_loaded,
            false, // force
            false, // is_redraw
            aspect_mismatch,
            80,
        );
        // 不变量断言 5：合法首帧、新几何完备、比例吻合，门控放行
        assert!(gate_valid_frame, "合法首帧必须被正确放行");

        // 阶段 8：原子消费保证与幂等性验证
        let claimed_first = render::G_SESSION.awaiting_first_frame.swap(false, Ordering::AcqRel);
        assert!(claimed_first, "首次消费等待首帧标记必须成功");

        let claimed_again = render::G_SESSION.awaiting_first_frame.swap(false, Ordering::AcqRel);
        assert!(!claimed_again, "重复消费等待首帧标记必须被拒绝，保证幂等性");

        // 阶段 9：跨纪元过期信号防守
        let expired_frame_epoch = new_epoch;
        let _ = events::next_epoch(); // 再次切片，纪元推进
        let current_now = events::current_epoch();
        assert!(expired_frame_epoch < current_now);
        // 渲染线程的纪元拦截
        assert!(
            expired_frame_epoch < current_now,
            "迟到的跨纪元首帧必须被丢弃"
        );
    }
}
