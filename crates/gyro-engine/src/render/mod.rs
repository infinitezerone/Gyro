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

use std::collections::VecDeque;
use std::ffi::c_void;
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicPtr, AtomicU64, AtomicU8, Ordering};
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
    clear_render_thread, destroy_render_context_sync, detach_surface_sync, ensure_render_thread,
    native_render_loop, render_sender, RenderCommand, G_FRAME_GATE,
};
pub use crate::strategy::{DrawOutcome, RenderBackend, RenderStrategy};

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
    pub active_fences: Mutex<VecDeque<SyncHandle>>,
    pub render_slot_ready: AtomicBool,
    pub pending_vo_check: AtomicBool,
    /// 输出色彩空间，见 `OUTPUT_COLORSPACE_*`。替代了原先的 `hdr_output` 布尔量：HLG 与 PQ
    /// 需要不同的 window surface colorspace，一个 bool 表达不了。
    pub output_colorspace: AtomicU8,
    pub hdr_capable: AtomicBool,
    /// EGL 扩展探测位掩码：`COLORSPACE_PROBED_BIT` 置位前其余位无意义。
    pub hdr_colorspace_support: AtomicU8,
}

impl Default for SessionState {
    fn default() -> Self {
        Self::new()
    }
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
            active_fences: Mutex::new(VecDeque::new()),
            render_slot_ready: AtomicBool::new(false),
            pending_vo_check: AtomicBool::new(false),
            output_colorspace: AtomicU8::new(OUTPUT_COLORSPACE_SDR),
            hdr_capable: AtomicBool::new(false),
            hdr_colorspace_support: AtomicU8::new(0),
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
        self.output_colorspace.store(OUTPUT_COLORSPACE_SDR, Ordering::Release);
        self.hdr_capable.store(false, Ordering::Release);
        self.hdr_colorspace_support.store(0, Ordering::Release);
    }
}

// ---------- 输出色彩空间 ----------

/// 默认 colorspace（SDR），mpv 侧对应 `target-trc=auto`。
pub const OUTPUT_COLORSPACE_SDR: u8 = 0;
/// BT.2020 / PQ，`EGL_GL_COLORSPACE_BT2020_PQ_EXT`。
pub const OUTPUT_COLORSPACE_PQ: u8 = 1;
/// BT.2020 / HLG，`EGL_GL_COLORSPACE_BT2020_HLG_EXT`。
pub const OUTPUT_COLORSPACE_HLG: u8 = 2;

/// 探测完成标志位。置位前，其余位不可信。
pub const COLORSPACE_PROBED_BIT: u8 = 0x80;
/// EGL 支持 BT.2020/PQ window surface。
pub const COLORSPACE_PQ_BIT: u8 = 0x01;
/// EGL 支持 BT.2020/HLG window surface。
pub const COLORSPACE_HLG_BIT: u8 = 0x02;

pub static G_SESSION: SessionState = SessionState::new();
pub static G_RENDER_EPOCH: AtomicU64 = AtomicU64::new(0);

/// 设置输出色彩空间。HLG 与 PQ 走的是不同 surface 属性，因此不再是布尔量。
pub fn set_output_colorspace(colorspace: u8) {
    G_SESSION.output_colorspace.store(colorspace, Ordering::Release);
}

pub fn output_colorspace() -> u8 {
    G_SESSION.output_colorspace.load(Ordering::Acquire)
}

/// 是否处于 HDR 输出（PQ 或 HLG）。渲染侧只关心「是不是 HDR」的地方用它。
pub fn is_hdr_output() -> bool {
    output_colorspace() != OUTPUT_COLORSPACE_SDR
}

pub fn set_hdr_capable(capable: bool) {
    G_SESSION.hdr_capable.store(capable, Ordering::Release);
}

pub fn is_hdr_capable() -> bool {
    G_SESSION.hdr_capable.load(Ordering::Acquire)
}

/// 记录 EGL colorspace 扩展探测结论。
pub fn set_hdr_colorspace_support(pq: bool, hlg: bool) {
    let mut mask = COLORSPACE_PROBED_BIT;
    if pq {
        mask |= COLORSPACE_PQ_BIT;
    }
    if hlg {
        mask |= COLORSPACE_HLG_BIT;
    }
    G_SESSION.hdr_colorspace_support.store(mask, Ordering::Release);
}

/// `None` 表示渲染线程尚未完成 EGL 探测，调用方应保持既有行为而不是关掉 HDR。
pub fn hdr_colorspace_support() -> Option<(bool, bool)> {
    let mask = G_SESSION.hdr_colorspace_support.load(Ordering::Acquire);
    if mask & COLORSPACE_PROBED_BIT == 0 {
        return None;
    }
    Some((mask & COLORSPACE_PQ_BIT != 0, mask & COLORSPACE_HLG_BIT != 0))
}

/// JNI 用的紧凑形式：`-1` 未探测，否则 bit0 = PQ、bit1 = HLG。
pub fn hdr_colorspace_support_mask() -> i32 {
    match hdr_colorspace_support() {
        None => -1,
        Some((pq, hlg)) => {
            let mut mask = 0;
            if pq {
                mask |= COLORSPACE_PQ_BIT as i32;
            }
            if hlg {
                mask |= COLORSPACE_HLG_BIT as i32;
            }
            mask
        }
    }
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
            active_fences: Mutex::new(VecDeque::from([SyncHandle(0xABCD as *mut c_void)])),
            render_slot_ready: AtomicBool::new(true),
            pending_vo_check: AtomicBool::new(true),
            output_colorspace: AtomicU8::new(OUTPUT_COLORSPACE_PQ),
            hdr_capable: AtomicBool::new(true),
            hdr_colorspace_support: AtomicU8::new(COLORSPACE_PROBED_BIT | COLORSPACE_PQ_BIT),
        };

        s.reset();

        assert_eq!(0, s.frames_drawn.load(Ordering::Acquire));
        assert_eq!(0, s.frames_starved.load(Ordering::Acquire));
        assert_eq!(0, s.last_draw_ms.load(Ordering::Acquire));
        assert!(!s.rearm_video.load(Ordering::Acquire));
        assert!(!s.pending_wid_rebind.load(Ordering::Acquire));
        assert!(!s.hdr_capable.load(Ordering::Acquire));
        assert_eq!(OUTPUT_COLORSPACE_SDR, s.output_colorspace.load(Ordering::Acquire));
        assert_eq!(0, s.hdr_colorspace_support.load(Ordering::Acquire));
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
