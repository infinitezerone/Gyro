//! Gyro Engine 原生渲染子系统
//!
//! 模块划分：
//! - `types`: EGL / NDK 基础类型、常量定义与 FFI 外部链接
//! - `egl`: EGL 上下文、SurfaceSlot 槽位与 EglRenderStrategy 策略实现
//! - `frame`: 帧绘制、eglPresentationTime 硬件锁相、Fence 管理与上下文丢失恢复
//! - `probe`: 显卡驱动环境离屏探针 (GPU 厂商、型号、零拷贝能力探测)
//! - `thread`: 常驻渲染线程 `gyro-render`、命令队列与策略调度循环

pub mod egl;
pub mod frame;
pub mod probe;
pub mod thread;
pub mod types;

use std::ffi::c_void;
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicPtr, AtomicU64, Ordering};
use std::sync::Mutex;

// ---------- 重导出对外公共接口，保持向后完全兼容 ----------

pub use self::types::*;
pub use self::egl::{
    choose_egl_config, egl_error_name, window_ref, EglRenderStrategy, SurfaceSlot,
    WINDOW_REF_BALANCE,
};
pub use self::frame::{
    compute_monotonic_presentation_ns, drain_active_fences, draw_frame_native,
    ensure_render_context, free_render_context, get_egl_presentation_time_fn,
    mpv_update_callback, skip_frame_native, EglPresentationTimeAndroidFn, SyncHandle,
    G_RECOVERY, MAX_TRACKED_FENCES,
};
pub use self::probe::{G_GPU_RENDERER, G_GPU_VENDOR, G_ZERO_COPY_SAFE};
pub use self::thread::{
    clear_render_thread, destroy_render_context_sync, ensure_render_thread, native_render_loop,
    render_sender, RenderCommand, G_FRAME_GATE, G_PREFER_VULKAN,
};
pub use crate::strategy::{DrawOutcome, RenderBackend, RenderStrategy};

// ---------- 全局 mpv 句柄指针镜像 ----------

pub(crate) static G_MPVP: AtomicPtr<c_void> = AtomicPtr::new(std::ptr::null_mut());

// ---------- 渲染线程会话状态 ----------

pub struct SessionState {
    pub frames_drawn: AtomicU64,
    pub frames_starved: AtomicU64,
    pub last_draw_ms: AtomicU64,
    pub rearm_video: AtomicBool,
    pub pending_wid_rebind: AtomicBool,
    pub awaiting_first_frame: AtomicBool,
    pub file_loaded_reached: AtomicBool,
    pub load_started_ms: AtomicU64,
    pub cached_dwidth: AtomicI64,
    pub cached_dheight: AtomicI64,
    pub cached_rotate: AtomicI64,
    pub render_dead: AtomicBool,
    pub real_gl_fence_sync: AtomicPtr<c_void>,
    pub real_gl_delete_sync: AtomicPtr<c_void>,
    pub real_egl_presentation_time: AtomicPtr<c_void>,
    pub egl_presentation_resolved: AtomicBool,
    pub active_fences: Mutex<Vec<SyncHandle>>,
    pub render_slot_ready: AtomicBool,
    pub pending_vo_check: AtomicBool,
}

impl SessionState {
    pub const fn new() -> Self {
        Self {
            frames_drawn: AtomicU64::new(0),
            frames_starved: AtomicU64::new(0),
            last_draw_ms: AtomicU64::new(0),
            rearm_video: AtomicBool::new(false),
            pending_wid_rebind: AtomicBool::new(false),
            awaiting_first_frame: AtomicBool::new(false),
            file_loaded_reached: AtomicBool::new(false),
            load_started_ms: AtomicU64::new(0),
            cached_dwidth: AtomicI64::new(0),
            cached_dheight: AtomicI64::new(0),
            cached_rotate: AtomicI64::new(0),
            render_dead: AtomicBool::new(false),
            real_gl_fence_sync: AtomicPtr::new(std::ptr::null_mut()),
            real_gl_delete_sync: AtomicPtr::new(std::ptr::null_mut()),
            real_egl_presentation_time: AtomicPtr::new(std::ptr::null_mut()),
            egl_presentation_resolved: AtomicBool::new(false),
            active_fences: Mutex::new(Vec::new()),
            render_slot_ready: AtomicBool::new(false),
            pending_vo_check: AtomicBool::new(false),
        }
    }

    pub fn reset(&self) {
        self.frames_drawn.store(0, Ordering::Release);
        self.frames_starved.store(0, Ordering::Release);
        self.last_draw_ms.store(0, Ordering::Release);
        self.rearm_video.store(false, Ordering::Release);
        self.pending_wid_rebind.store(false, Ordering::Release);
        self.awaiting_first_frame.store(false, Ordering::Release);
        self.file_loaded_reached.store(false, Ordering::Release);
        self.load_started_ms.store(0, Ordering::Release);
        self.cached_dwidth.store(0, Ordering::Release);
        self.cached_dheight.store(0, Ordering::Release);
        self.cached_rotate.store(0, Ordering::Release);
        self.render_dead.store(false, Ordering::Release);
        self.real_gl_fence_sync.store(std::ptr::null_mut(), Ordering::Release);
        self.real_gl_delete_sync.store(std::ptr::null_mut(), Ordering::Release);
        self.real_egl_presentation_time.store(std::ptr::null_mut(), Ordering::Release);
        self.egl_presentation_resolved.store(false, Ordering::Release);
        if let Ok(mut fences) = self.active_fences.lock() {
            fences.clear();
        }
        self.render_slot_ready.store(false, Ordering::Release);
        self.pending_vo_check.store(false, Ordering::Release);
    }
}

pub static G_SESSION: SessionState = SessionState::new();
pub static G_RENDER_EPOCH: AtomicU64 = AtomicU64::new(0);

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
static G_REQUESTED_VO: Mutex<String> = Mutex::new(String::new());

pub fn set_requested_vo(vo: &str) {
    if let Ok(mut g) = G_REQUESTED_VO.lock() {
        *g = vo.to_string();
    }
}

pub fn get_requested_vo() -> String {
    G_REQUESTED_VO.lock().map(|g| g.clone()).unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn session_state_reset_clears_every_field() {
        let s = SessionState {
            frames_drawn: AtomicU64::new(100),
            frames_starved: AtomicU64::new(50),
            last_draw_ms: AtomicU64::new(999),
            rearm_video: AtomicBool::new(true),
            pending_wid_rebind: AtomicBool::new(true),
            awaiting_first_frame: AtomicBool::new(true),
            file_loaded_reached: AtomicBool::new(true),
            load_started_ms: AtomicU64::new(12345),
            cached_dwidth: AtomicI64::new(1920),
            cached_dheight: AtomicI64::new(1080),
            cached_rotate: AtomicI64::new(90),
            render_dead: AtomicBool::new(true),
            real_gl_fence_sync: AtomicPtr::new(0x1234 as *mut c_void),
            real_gl_delete_sync: AtomicPtr::new(0x5678 as *mut c_void),
            real_egl_presentation_time: AtomicPtr::new(0x9999 as *mut c_void),
            egl_presentation_resolved: AtomicBool::new(true),
            active_fences: Mutex::new(vec![SyncHandle(0xABCD as *mut c_void)]),
            render_slot_ready: AtomicBool::new(true),
            pending_vo_check: AtomicBool::new(true),
        };

        s.reset();

        assert_eq!(0, s.frames_drawn.load(Ordering::Acquire));
        assert_eq!(0, s.frames_starved.load(Ordering::Acquire));
        assert_eq!(0, s.last_draw_ms.load(Ordering::Acquire));
        assert!(!s.rearm_video.load(Ordering::Acquire));
        assert!(!s.pending_wid_rebind.load(Ordering::Acquire));
        assert!(!s.awaiting_first_frame.load(Ordering::Acquire));
        assert!(!s.file_loaded_reached.load(Ordering::Acquire));
        assert_eq!(0, s.load_started_ms.load(Ordering::Acquire));
        assert_eq!(0, s.cached_dwidth.load(Ordering::Acquire));
        assert_eq!(0, s.cached_dheight.load(Ordering::Acquire));
        assert_eq!(0, s.cached_rotate.load(Ordering::Acquire));
        assert!(!s.render_dead.load(Ordering::Acquire));
        assert!(s.real_gl_fence_sync.load(Ordering::Acquire).is_null());
        assert!(s.real_gl_delete_sync.load(Ordering::Acquire).is_null());
        assert!(s.real_egl_presentation_time.load(Ordering::Acquire).is_null());
        assert!(!s.egl_presentation_resolved.load(Ordering::Acquire));
        assert!(s.active_fences.lock().unwrap().is_empty());
        assert!(!s.render_slot_ready.load(Ordering::Acquire));
        assert!(!s.pending_vo_check.load(Ordering::Acquire));
    }

    #[test]
    fn window_ref_balance_returns_to_baseline_after_a_full_handoff_cycle() {
        let baseline = WINDOW_REF_BALANCE.load(Ordering::Acquire);
        window_ref(2, "test handoff");
        assert_eq!(baseline + 2, WINDOW_REF_BALANCE.load(Ordering::Acquire));
        window_ref(-1, "test release handoff");
        window_ref(-1, "test detach release");
        assert_eq!(baseline, WINDOW_REF_BALANCE.load(Ordering::Acquire));

        window_ref(2, "test handoff");
        window_ref(-2, "test failed-attach release");
        assert_eq!(baseline, WINDOW_REF_BALANCE.load(Ordering::Acquire));
    }
}
