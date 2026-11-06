//! 原生 EGL / OpenGL ES 渲染管线与常驻渲染线程

#![cfg_attr(not(target_os = "android"), allow(dead_code, unused_imports))]

use std::ffi::{c_char, c_int, c_void, CStr};
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicPtr, AtomicU64, Ordering};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Mutex, OnceLock};
use std::thread::JoinHandle;
use std::time::Instant;

use crate::logic::{
    compute_monotonic_presentation_ns, AckOutcome, AffinityVerdict, FrameCoalescer, ThreadAffinity,
    request_ack,
};
#[cfg(target_os = "android")]
use crate::logic::{
    egl_config_attempts, egl_error_name, ContextRecovery, EglConfigRequest, RecoveryVerdict,
    CONTEXT_CLIENT_VERSIONS, EGL_BAD_SURFACE, EGL_CONTEXT_LOST,
};
use crate::mpv::{
    mpv_handle_guarded, with_api, with_api_logged, MpvOpenGLFbo, MpvOpenGLInitParams,
    MpvRenderFrameInfo, MpvRenderParam, G_MPVP, MPV_ERROR_SUCCESS, MPV_FORMAT_INT64,
    MPV_RENDER_FRAME_INFO_REDRAW, MPV_RENDER_PARAM_API_TYPE,
    MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME, MPV_RENDER_PARAM_FLIP_Y,
    MPV_RENDER_PARAM_NEXT_FRAME_INFO, MPV_RENDER_PARAM_OPENGL_FBO,
    MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, MPV_RENDER_PARAM_SKIP_RENDERING, MPV_RENDER_UPDATE_FRAME,
};
use crate::{monotonic_ms, monotonic_ns, nlog, reset_engine_lifecycle_state};

// ---------- NDK 原生绑定 (EGL / Android Native Window) ----------

pub type EGLDisplay = *mut c_void;
pub type EGLConfig = *mut c_void;
pub type EGLContext = *mut c_void;
pub type EGLSurface = *mut c_void;
pub type EGLint = i32;
pub type ANativeWindow = c_void;

#[derive(Clone, Copy)]
pub struct NativeWindowPtr(pub *mut ANativeWindow);
unsafe impl Send for NativeWindowPtr {}
unsafe impl Sync for NativeWindowPtr {}

pub const EGL_NO_DISPLAY: EGLDisplay = std::ptr::null_mut();
pub const EGL_NO_CONTEXT: EGLContext = std::ptr::null_mut();
pub const EGL_NO_SURFACE: EGLSurface = std::ptr::null_mut();
pub const EGL_DEFAULT_DISPLAY: *mut c_void = std::ptr::null_mut();

pub const EGL_TRUE: EGLint = 1;
pub const EGL_WIDTH: EGLint = 0x3057;
pub const EGL_HEIGHT: EGLint = 0x3056;
pub const EGL_RENDERABLE_TYPE: EGLint = 0x3040;
pub const EGL_OPENGL_ES2_BIT: EGLint = 0x00000004;
pub const EGL_OPENGL_ES3_BIT: EGLint = 0x00000040;
pub const EGL_SURFACE_TYPE: EGLint = 0x3033;
pub const EGL_WINDOW_BIT: EGLint = 0x0004;
pub const EGL_PBUFFER_BIT: EGLint = 0x0001;
pub const EGL_RED_SIZE: EGLint = 0x3024;
pub const EGL_GREEN_SIZE: EGLint = 0x3023;
pub const EGL_BLUE_SIZE: EGLint = 0x3022;
pub const EGL_ALPHA_SIZE: EGLint = 0x3021;
pub const EGL_DEPTH_SIZE: EGLint = 0x3025;
pub const EGL_STENCIL_SIZE: EGLint = 0x3026;
pub const EGL_NONE: EGLint = 0x3038;
pub const EGL_CONTEXT_CLIENT_VERSION: EGLint = 0x3098;
pub const EGL_NATIVE_VISUAL_ID: EGLint = 0x302E;
pub const EGL_EXTENSIONS: EGLint = 0x3055;
pub const EGL_CONFIG_CAVEAT: EGLint = 0x3027;
pub const EGL_CONFIG_ID: EGLint = 0x3028;
pub const EGL_SLOW_CONFIG: EGLint = 0x3050;
pub const EGL_NON_CONFORMANT_CONFIG: EGLint = 0x3051;

pub const GL_VENDOR: u32 = 0x1F00;
pub const GL_RENDERER: u32 = 0x1F01;
pub const GL_EXTENSIONS: u32 = 0x1F03;

/// Upper bound on how long the DestroyContext *caller* waits for the render thread's ack.
///
/// Unlike AttachSurface, teardown ordering is a real requirement here: `mpv_terminate_destroy`
/// must not run while the render thread still holds the render context. There is no fallback
/// path that makes a timed-out destroy safe, hence the generous bound.
pub const DESTROY_ACK_TIMEOUT_MS: u64 = 2000;

#[cfg(target_os = "android")]
#[link(name = "EGL")]
extern "C" {
    fn eglGetDisplay(display_id: *mut c_void) -> EGLDisplay;
    fn eglInitialize(dpy: EGLDisplay, major: *mut EGLint, minor: *mut EGLint) -> EGLint;
    fn eglChooseConfig(
        dpy: EGLDisplay,
        attrib_list: *const EGLint,
        configs: *mut EGLConfig,
        config_size: EGLint,
        num_config: *mut EGLint,
    ) -> EGLint;
    fn eglGetConfigAttrib(
        dpy: EGLDisplay,
        config: EGLConfig,
        attribute: EGLint,
        value: *mut EGLint,
    ) -> EGLint;
    fn eglCreateContext(
        dpy: EGLDisplay,
        config: EGLConfig,
        share_context: EGLContext,
        attrib_list: *const EGLint,
    ) -> EGLContext;
    fn eglCreateWindowSurface(
        dpy: EGLDisplay,
        config: EGLConfig,
        win: *mut ANativeWindow,
        attrib_list: *const EGLint,
    ) -> EGLSurface;
    fn eglCreatePbufferSurface(
        dpy: EGLDisplay,
        config: EGLConfig,
        attrib_list: *const EGLint,
    ) -> EGLSurface;
    fn eglMakeCurrent(
        dpy: EGLDisplay,
        draw: EGLSurface,
        read: EGLSurface,
        ctx: EGLContext,
    ) -> EGLint;
    fn eglQuerySurface(
        dpy: EGLDisplay,
        surface: EGLSurface,
        attribute: EGLint,
        value: *mut EGLint,
    ) -> EGLint;
    fn eglQueryString(dpy: EGLDisplay, name: EGLint) -> *const c_char;
    fn eglGetError() -> EGLint;
    fn eglSwapBuffers(dpy: EGLDisplay, surface: EGLSurface) -> EGLint;
    fn eglSwapInterval(dpy: EGLDisplay, interval: EGLint) -> EGLint;
    fn eglDestroySurface(dpy: EGLDisplay, surface: EGLSurface) -> EGLint;
    fn eglDestroyContext(dpy: EGLDisplay, ctx: EGLContext) -> EGLint;
    fn eglReleaseThread() -> EGLint;
    fn eglTerminate(dpy: EGLDisplay) -> EGLint;
    fn eglGetProcAddress(procname: *const c_char) -> *mut c_void;
}

#[cfg(target_os = "android")]
#[link(name = "android")]
extern "C" {
    pub fn ANativeWindow_fromSurface(env: *mut jni::sys::JNIEnv, surface: jni::sys::jobject) -> *mut ANativeWindow;
    pub fn ANativeWindow_acquire(window: *mut ANativeWindow);
    pub fn ANativeWindow_release(window: *mut ANativeWindow);
}

// ---------- 原生渲染命令与常驻线程 ----------

pub enum RenderCommand {
    /// Fire-and-forget: the render thread owns the whole attach sequence (window surface,
    /// make-current, black clear, render context). Nothing on the Kotlin side requires the
    /// surface to be bound when the JNI call returns — a loadfile may land before the bind and
    /// mpv simply starts pushing frames once the render context exists — so a blocking ack
    /// would only pin the UI thread for a first-paint nicety worth at most one frame.
    AttachSurface(NativeWindowPtr),
    DetachSurface,
    Redraw,
    NotifyFrame,
    /// Acknowledged: `safe_terminate_mpv` must not destroy the mpv handle while the render
    /// thread still holds the render context, so the caller waits for this ack.
    DestroyContext(std::sync::mpsc::SyncSender<()>),
    Shutdown,
}

struct RenderThreadHandle {
    sender: Sender<RenderCommand>,
    _thread: JoinHandle<()>,
}

static RENDER_THREAD: OnceLock<Mutex<Option<RenderThreadHandle>>> = OnceLock::new();
pub static G_FRAME_GATE: FrameCoalescer = FrameCoalescer::new();
pub static G_FRAMES_DRAWN: AtomicU64 = AtomicU64::new(0);
pub static G_FRAMES_STARVED: AtomicU64 = AtomicU64::new(0);
pub static G_LAST_DRAW_MS: AtomicU64 = AtomicU64::new(0);
pub static G_REARM_VIDEO: AtomicBool = AtomicBool::new(false);
pub static G_PENDING_WID_REBIND: AtomicBool = AtomicBool::new(false);
pub static G_AWAITING_FIRST_FRAME: AtomicBool = AtomicBool::new(false);
pub static G_FILE_LOADED_REACHED: AtomicBool = AtomicBool::new(false);
pub static G_LOAD_STARTED_MS: AtomicU64 = AtomicU64::new(0);

// ---------- 线程亲和：GL 上下文只归渲染线程所有 ----------
static G_AFFINITY: Mutex<ThreadAffinity> = Mutex::new(ThreadAffinity::new());
static G_AFFINITY_OWNER: AtomicU64 = AtomicU64::new(0);
static NEXT_LOGICAL_TID: AtomicU64 = AtomicU64::new(1);

fn logical_thread_id() -> u64 {
    thread_local! {
        static TID: u64 = NEXT_LOGICAL_TID.fetch_add(1, Ordering::Relaxed);
    }
    TID.with(|id| *id)
}

#[inline]
unsafe fn assert_render_thread(what: &str) {
    let tid = logical_thread_id();
    let owner = G_AFFINITY_OWNER.load(Ordering::Relaxed);
    if ThreadAffinity::verdict(owner, tid) == AffinityVerdict::Violation {
        nlog(&format!("FATAL: {what} 在非渲染线程被调用 (tid={tid})"));
        debug_assert!(false, "{what} called off the render thread");
    }
}

// ---------- 防御层 1：冷启动离屏环境探针 (Static Env Probe) ----------
pub static G_ZERO_COPY_SAFE: AtomicBool = AtomicBool::new(true);
pub static G_GPU_RENDERER: OnceLock<String> = OnceLock::new();
pub static G_GPU_VENDOR: OnceLock<String> = OnceLock::new();

// ---------- 防御层 3：GL 上下文丢失重建 (Context Recovery) ----------
#[cfg(target_os = "android")]
pub static G_RECOVERY: Mutex<ContextRecovery> = Mutex::new(ContextRecovery::with_defaults());
pub static G_RENDER_DEAD: AtomicBool = AtomicBool::new(false);

enum ProbeTarget {
    Surfaceless,
    Pbuffer(EGLSurface),
}

#[cfg(target_os = "android")]
impl ProbeTarget {
    unsafe fn release(self, display: EGLDisplay) {
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if let ProbeTarget::Pbuffer(surface) = self {
            if eglDestroySurface(display, surface) != EGL_TRUE {
                egl_failure("eglDestroySurface(probe)");
            }
        }
    }
}

#[cfg(target_os = "android")]
unsafe fn bind_probe_target(display: EGLDisplay, ctx: EGLContext) -> Option<ProbeTarget> {
    if eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx) == EGL_TRUE {
        return Some(ProbeTarget::Surfaceless);
    }
    let pbuffer_attribs: [EGLint; 5] = [EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE];
    let config_attribs: [EGLint; 5] = [
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_NONE,
    ];
    let mut config: EGLConfig = std::ptr::null_mut();
    let mut num_configs: EGLint = 0;
    if eglChooseConfig(display, config_attribs.as_ptr(), &mut config, 1, &mut num_configs) != EGL_TRUE
        || num_configs < 1
    {
        egl_failure("eglChooseConfig(probe pbuffer)");
        return None;
    }
    let surface = eglCreatePbufferSurface(display, config, pbuffer_attribs.as_ptr());
    if surface == EGL_NO_SURFACE {
        egl_failure("eglCreatePbufferSurface(probe)");
        return None;
    }
    if eglMakeCurrent(display, surface, surface, ctx) != EGL_TRUE {
        egl_failure("eglMakeCurrent(probe pbuffer)");
        let _ = eglDestroySurface(display, surface);
        return None;
    }
    Some(ProbeTarget::Pbuffer(surface))
}

#[cfg(target_os = "android")]
unsafe fn probe_graphics_environment(display: EGLDisplay, ctx: EGLContext) {
    let mut renderer_str = String::from("unknown");
    let mut vendor_str = String::from("unknown");
    let mut has_image_base = false;
    let mut has_android_native = false;
    let mut has_oes_external = false;

    let egl_exts_ptr = eglQueryString(display, EGL_EXTENSIONS);
    if !egl_exts_ptr.is_null() {
        let egl_exts = CStr::from_ptr(egl_exts_ptr).to_string_lossy();
        has_image_base = egl_exts.contains("EGL_KHR_image_base");
        has_android_native = egl_exts.contains("EGL_ANDROID_image_native_buffer");
    }

    if let Some(target) = bind_probe_target(display, ctx) {
        let gl_get_string: Option<unsafe extern "C" fn(u32) -> *const c_char> = {
            let proc = eglGetProcAddress(b"glGetString\0".as_ptr().cast());
            if proc.is_null() { None } else { Some(std::mem::transmute(proc)) }
        };

        if let Some(get_str) = gl_get_string {
            let r_ptr = get_str(GL_RENDERER);
            if !r_ptr.is_null() {
                renderer_str = CStr::from_ptr(r_ptr).to_string_lossy().into_owned();
            }
            let v_ptr = get_str(GL_VENDOR);
            if !v_ptr.is_null() {
                vendor_str = CStr::from_ptr(v_ptr).to_string_lossy().into_owned();
            }
            let ext_ptr = get_str(GL_EXTENSIONS);
            if !ext_ptr.is_null() {
                let gl_exts = CStr::from_ptr(ext_ptr).to_string_lossy();
                has_oes_external = gl_exts.contains("GL_OES_EGL_image_external");
            }
        }
        target.release(display);
    } else {
        nlog("PROBE: 无法绑定探测目标，GL 字符串与扩展均不可读");
    }

    let zero_copy_safe = (has_image_base || has_android_native) && has_oes_external;
    G_ZERO_COPY_SAFE.store(zero_copy_safe, Ordering::Release);
    let _ = G_GPU_RENDERER.set(renderer_str.clone());
    let _ = G_GPU_VENDOR.set(vendor_str.clone());

    nlog(&format!(
        "PROBE: GPU='{}' Vendor='{}' ZeroCopySafe={} (img_base={}, native_buf={}, oes_ext={})",
        renderer_str, vendor_str, zero_copy_safe, has_image_base, has_android_native, has_oes_external
    ));
}

#[cfg(not(target_os = "android"))]
unsafe fn probe_graphics_environment(_display: EGLDisplay, _ctx: EGLContext) {}

fn request_video_rearm() {
    G_REARM_VIDEO.store(true, Ordering::Release);
    if let Ok((_guard, handle)) = unsafe { mpv_handle_guarded() } {
        let _ = with_api(|api| unsafe { (api.mpv_wakeup)(handle) });
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub struct SyncHandle(pub *mut c_void);
unsafe impl Send for SyncHandle {}
unsafe impl Sync for SyncHandle {}

pub static REAL_GL_FENCE_SYNC: AtomicPtr<c_void> = AtomicPtr::new(std::ptr::null_mut());
pub static REAL_GL_DELETE_SYNC: AtomicPtr<c_void> = AtomicPtr::new(std::ptr::null_mut());
pub static ACTIVE_FENCES: Mutex<Vec<SyncHandle>> = Mutex::new(Vec::new());

unsafe extern "C" fn wrapped_gl_fence_sync(condition: u32, flags: u32) -> *mut c_void {
    assert_render_thread("glFenceSync");
    let real_fn = REAL_GL_FENCE_SYNC.load(Ordering::Relaxed);
    if real_fn.is_null() {
        return std::ptr::null_mut();
    }
    let f: unsafe extern "C" fn(u32, u32) -> *mut c_void = std::mem::transmute(real_fn);
    let sync = f(condition, flags);
    if !sync.is_null() {
        if let Ok(mut lock) = ACTIVE_FENCES.lock() {
            lock.push(SyncHandle(sync));
            if lock.len() > 2 {
                let old_sync = lock.remove(0);
                let del_fn = REAL_GL_DELETE_SYNC.load(Ordering::Relaxed);
                if !del_fn.is_null() {
                    let del: unsafe extern "C" fn(*mut c_void) = std::mem::transmute(del_fn);
                    del(old_sync.0);
                }
            }
        }
    }
    sync
}

unsafe extern "C" fn wrapped_gl_delete_sync(sync: *mut c_void) {
    assert_render_thread("glDeleteSync");
    if !sync.is_null() {
        let was_tracked = if let Ok(mut lock) = ACTIVE_FENCES.lock() {
            if let Some(pos) = lock.iter().position(|s| s.0 == sync) {
                lock.remove(pos);
                true
            } else {
                false
            }
        } else {
            false
        };
        if was_tracked {
            let del_fn = REAL_GL_DELETE_SYNC.load(Ordering::Relaxed);
            if !del_fn.is_null() {
                let del: unsafe extern "C" fn(*mut c_void) = std::mem::transmute(del_fn);
                del(sync);
            }
        }
    }
}

unsafe fn drain_active_fences() {
    assert_render_thread("drain_active_fences");
    let Ok(mut fences) = ACTIVE_FENCES.lock() else { return };
    if fences.is_empty() {
        return;
    }
    let del_fn = REAL_GL_DELETE_SYNC.load(Ordering::Relaxed);
    if del_fn.is_null() {
        fences.clear();
        return;
    }
    let del: unsafe extern "C" fn(*mut c_void) = std::mem::transmute(del_fn);
    let leaked = fences.len();
    for sync in fences.drain(..) {
        del(sync.0);
    }
    nlog(&format!("EGL: 已释放 {leaked} 个残留 fence 对象"));
}

unsafe extern "C" fn gl_proc_address(_ctx: *mut c_void, name: *const c_char) -> *mut c_void {
    #[cfg(target_os = "android")]
    {
        if !name.is_null() {
            let s = CStr::from_ptr(name).to_bytes();
            if s == b"glFenceSync" || s == b"glFenceSyncAPPLE" {
                let real = eglGetProcAddress(name);
                if !real.is_null() {
                    REAL_GL_FENCE_SYNC.store(real, Ordering::Relaxed);
                    if REAL_GL_DELETE_SYNC.load(Ordering::Relaxed).is_null() {
                        let del = eglGetProcAddress(b"glDeleteSync\0".as_ptr().cast());
                        if !del.is_null() {
                            REAL_GL_DELETE_SYNC.store(del, Ordering::Relaxed);
                        }
                    }
                    return wrapped_gl_fence_sync as *mut c_void;
                }
            } else if s == b"glDeleteSync" || s == b"glDeleteSyncAPPLE" {
                let real = eglGetProcAddress(name);
                if !real.is_null() {
                    REAL_GL_DELETE_SYNC.store(real, Ordering::Relaxed);
                    return wrapped_gl_delete_sync as *mut c_void;
                }
            }
        }
        eglGetProcAddress(name)
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = name;
        std::ptr::null_mut()
    }
}

// ---------- eglPresentationTimeANDROID 硬件锁相支持 ----------

pub type EglPresentationTimeAndroidFn = unsafe extern "C" fn(
    dpy: EGLDisplay,
    surface: EGLSurface,
    time: i64,
) -> EGLint;

pub static REAL_EGL_PRESENTATION_TIME: AtomicPtr<c_void> = AtomicPtr::new(std::ptr::null_mut());
pub static EGL_PRESENTATION_RESOLVED: AtomicBool = AtomicBool::new(false);

#[cfg(target_os = "android")]
pub fn get_egl_presentation_time_fn() -> Option<EglPresentationTimeAndroidFn> {
    if !EGL_PRESENTATION_RESOLVED.load(Ordering::Acquire) {
        unsafe {
            let proc = eglGetProcAddress(b"eglPresentationTimeANDROID\0".as_ptr().cast());
            if !proc.is_null() {
                REAL_EGL_PRESENTATION_TIME.store(proc, Ordering::Release);
                nlog("EGL: 成功动态解析 EGL_ANDROID_presentation_time 扩展");
            } else {
                nlog("EGL: 当前驱动未导出 eglPresentationTimeANDROID 扩展");
            }
        }
        EGL_PRESENTATION_RESOLVED.store(true, Ordering::Release);
    }
    let p = REAL_EGL_PRESENTATION_TIME.load(Ordering::Acquire);
    if p.is_null() {
        None
    } else {
        unsafe { Some(std::mem::transmute::<*mut c_void, EglPresentationTimeAndroidFn>(p)) }
    }
}

#[cfg(not(target_os = "android"))]
pub fn get_egl_presentation_time_fn() -> Option<EglPresentationTimeAndroidFn> {
    None
}

// ---------- EGL 资源所有权 ----------

#[cfg(target_os = "android")]
fn egl_failure(what: &str) -> EGLint {
    let err = unsafe { eglGetError() };
    nlog(&format!(
        "EGL: {what} 失败 (error={} / 0x{err:X})",
        egl_error_name(err)
    ));
    err
}

#[cfg(target_os = "android")]
fn egl_config_attributes(request: &EglConfigRequest, out: &mut Vec<EGLint>) {
    out.clear();
    out.extend_from_slice(&[
        EGL_RENDERABLE_TYPE,
        if request.require_es3 {
            EGL_OPENGL_ES3_BIT
        } else {
            EGL_OPENGL_ES2_BIT
        },
        EGL_RED_SIZE,
        request.red_bits as EGLint,
        EGL_GREEN_SIZE,
        request.green_bits as EGLint,
        EGL_BLUE_SIZE,
        request.blue_bits as EGLint,
        EGL_ALPHA_SIZE,
        request.alpha_bits as EGLint,
        EGL_DEPTH_SIZE,
        request.depth_bits as EGLint,
        EGL_STENCIL_SIZE,
        request.stencil_bits as EGLint,
    ]);
    if request.require_window {
        out.extend_from_slice(&[EGL_SURFACE_TYPE, EGL_WINDOW_BIT]);
    }
    out.push(EGL_NONE);
}

#[cfg(target_os = "android")]
unsafe fn config_attrib(
    display: EGLDisplay,
    config: EGLConfig,
    attribute: EGLint,
    default: EGLint,
) -> EGLint {
    let mut value: EGLint = default;
    if eglGetConfigAttrib(display, config, attribute, &mut value) != EGL_TRUE {
        egl_failure(&format!("eglGetConfigAttrib(0x{attribute:X})"));
        return default;
    }
    value
}

#[cfg(target_os = "android")]
struct ChosenConfig {
    config: EGLConfig,
    visual_id: EGLint,
}

#[cfg(target_os = "android")]
unsafe fn choose_egl_config(display: EGLDisplay, prefer_10bit: bool) -> Option<ChosenConfig> {
    let mut configs: [EGLConfig; 4] = [std::ptr::null_mut(); 4];
    let mut attributes: Vec<EGLint> = Vec::with_capacity(16);
    let attempts = egl_config_attempts(prefer_10bit);
    for request in attempts {
        egl_config_attributes(request, &mut attributes);
        let mut matched: EGLint = 0;
        let accepted = eglChooseConfig(
            display,
            attributes.as_ptr(),
            configs.as_mut_ptr(),
            configs.len() as EGLint,
            &mut matched,
        );
        if accepted == EGL_TRUE && matched > 0 {
            let chosen = configs[0];
            let r = config_attrib(display, chosen, EGL_RED_SIZE, 0);
            let g = config_attrib(display, chosen, EGL_GREEN_SIZE, 0);
            let b = config_attrib(display, chosen, EGL_BLUE_SIZE, 0);
            let a = config_attrib(display, chosen, EGL_ALPHA_SIZE, 0);
            let caveat = config_attrib(display, chosen, EGL_CONFIG_CAVEAT, EGL_NONE);
            let visual_id = config_attrib(display, chosen, EGL_NATIVE_VISUAL_ID, 0);
            nlog(&format!(
                "EGL: 选中配置 ({}) RGBA={r}/{g}/{b}/{a} caveat=0x{caveat:X} visual_id=0x{visual_id:X} (matched={matched})",
                request.name,
            ));
            return Some(ChosenConfig { config: chosen, visual_id });
        }
    }
    egl_failure("eglChooseConfig(exhausted)");
    None
}

#[cfg(target_os = "android")]
struct EglCore {
    display: EGLDisplay,
    config: EGLConfig,
    context: EGLContext,
    context_major: EGLint,
    visual_id: EGLint,
}

#[cfg(target_os = "android")]
impl EglCore {
    unsafe fn create_context_with_major(
        display: EGLDisplay,
        config: EGLConfig,
        major: EGLint,
    ) -> EGLContext {
        let attribs = [EGL_CONTEXT_CLIENT_VERSION, major, EGL_NONE];
        eglCreateContext(display, config, EGL_NO_CONTEXT, attribs.as_ptr())
    }

    unsafe fn recreate_context(&mut self) -> bool {
        assert_render_thread("recreate_context");
        if self.context != EGL_NO_CONTEXT {
            eglMakeCurrent(self.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            let _ = eglDestroyContext(self.display, self.context);
            self.context = EGL_NO_CONTEXT;
        }

        for major in CONTEXT_CLIENT_VERSIONS {
            let ctx = Self::create_context_with_major(self.display, self.config, major);
            if ctx != EGL_NO_CONTEXT {
                nlog(&format!("EGL: 上下文重建成功 (GLES v{major})"));
                self.context = ctx;
                self.context_major = major;
                return true;
            }
        }
        egl_failure("recreate_context");
        false
    }

    unsafe fn destroy(&mut self) {
        assert_render_thread("EglCore::destroy");
        if self.display == EGL_NO_DISPLAY {
            return;
        }
        eglMakeCurrent(self.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if self.context != EGL_NO_CONTEXT {
            if eglDestroyContext(self.display, self.context) != EGL_TRUE {
                egl_failure("eglDestroyContext");
            }
            self.context = EGL_NO_CONTEXT;
        }
        let _ = eglReleaseThread();
        let _ = eglTerminate(self.display);
        self.display = EGL_NO_DISPLAY;
    }
}

#[cfg(target_os = "android")]
unsafe fn clear_window_surface(display: EGLDisplay, surface: EGLSurface, context_major: EGLint) {
    let mut prev_fbo: EGLint = 0;
    let mut prev_draw_fbo: EGLint = 0;
    let mut prev_read_fbo: EGLint = 0;
    let mut prev_clear_color: [f32; 4] = [0.0; 4];
    let mut prev_viewport: [EGLint; 4] = [0; 4];
    let mut prev_scissor_box: [EGLint; 4] = [0; 4];
    let prev_scissor_enabled: EGLint;

    let get_integerv = eglGetProcAddress(b"glGetIntegerv\0".as_ptr().cast());
    let get_floatv = eglGetProcAddress(b"glGetFloatv\0".as_ptr().cast());
    let is_enabled = eglGetProcAddress(b"glIsEnabled\0".as_ptr().cast());
    let clear_color = eglGetProcAddress(b"glClearColor\0".as_ptr().cast());
    let clear = eglGetProcAddress(b"glClear\0".as_ptr().cast());
    let viewport = eglGetProcAddress(b"glViewport\0".as_ptr().cast());
    let disable = eglGetProcAddress(b"glDisable\0".as_ptr().cast());
    let enable = eglGetProcAddress(b"glEnable\0".as_ptr().cast());
    let scissor = eglGetProcAddress(b"glScissor\0".as_ptr().cast());

    if get_integerv.is_null()
        || get_floatv.is_null()
        || is_enabled.is_null()
        || clear_color.is_null()
        || clear.is_null()
        || viewport.is_null()
        || disable.is_null()
        || enable.is_null()
        || scissor.is_null()
    {
        return;
    }

    let gl_get_integerv: unsafe extern "C" fn(u32, *mut EGLint) = std::mem::transmute(get_integerv);
    let gl_get_floatv: unsafe extern "C" fn(u32, *mut f32) = std::mem::transmute(get_floatv);
    let gl_is_enabled: unsafe extern "C" fn(u32) -> u8 = std::mem::transmute(is_enabled);
    let gl_clear_color: unsafe extern "C" fn(f32, f32, f32, f32) = std::mem::transmute(clear_color);
    let gl_clear: unsafe extern "C" fn(u32) = std::mem::transmute(clear);
    let gl_viewport: unsafe extern "C" fn(EGLint, EGLint, EGLint, EGLint) = std::mem::transmute(viewport);
    let gl_disable: unsafe extern "C" fn(u32) = std::mem::transmute(disable);
    let gl_enable: unsafe extern "C" fn(u32) = std::mem::transmute(enable);
    let gl_scissor: unsafe extern "C" fn(EGLint, EGLint, EGLint, EGLint) = std::mem::transmute(scissor);

    const GL_FRAMEBUFFER_BINDING: u32 = 0x8CA6;
    const GL_DRAW_FRAMEBUFFER_BINDING: u32 = 0x8CA6;
    const GL_READ_FRAMEBUFFER_BINDING: u32 = 0x8CAA;
    const GL_COLOR_CLEAR_VALUE: u32 = 0x0C22;
    const GL_VIEWPORT: u32 = 0x0BA2;
    const GL_SCISSOR_BOX: u32 = 0x0C10;
    const GL_SCISSOR_TEST: u32 = 0x0C11;
    const GL_COLOR_BUFFER_BIT: u32 = 0x00004000;

    let bind_framebuffer = eglGetProcAddress(b"glBindFramebuffer\0".as_ptr().cast());
    if context_major >= 3 && !bind_framebuffer.is_null() {
        gl_get_integerv(GL_DRAW_FRAMEBUFFER_BINDING, &mut prev_draw_fbo);
        gl_get_integerv(GL_READ_FRAMEBUFFER_BINDING, &mut prev_read_fbo);
        let gl_bind_framebuffer: unsafe extern "C" fn(u32, u32) = std::mem::transmute(bind_framebuffer);
        const GL_FRAMEBUFFER: u32 = 0x8D40;
        gl_bind_framebuffer(GL_FRAMEBUFFER, 0);

        let draw_buffers = eglGetProcAddress(b"glDrawBuffers\0".as_ptr().cast());
        if !draw_buffers.is_null() {
            let gl_draw_buffers: unsafe extern "C" fn(EGLint, *const u32) = std::mem::transmute(draw_buffers);
            const GL_BACK: u32 = 0x0405;
            gl_draw_buffers(1, [GL_BACK].as_ptr());
        }
        let read_buffer = eglGetProcAddress(b"glReadBuffer\0".as_ptr().cast());
        if !read_buffer.is_null() {
            let gl_read_buffer: unsafe extern "C" fn(u32) = std::mem::transmute(read_buffer);
            const GL_BACK: u32 = 0x0405;
            gl_read_buffer(GL_BACK);
        }
    } else if !bind_framebuffer.is_null() {
        gl_get_integerv(GL_FRAMEBUFFER_BINDING, &mut prev_fbo);
        let gl_bind_framebuffer: unsafe extern "C" fn(u32, u32) = std::mem::transmute(bind_framebuffer);
        const GL_FRAMEBUFFER: u32 = 0x8D40;
        gl_bind_framebuffer(GL_FRAMEBUFFER, 0);
    }

    gl_get_floatv(GL_COLOR_CLEAR_VALUE, prev_clear_color.as_mut_ptr());
    gl_get_integerv(GL_VIEWPORT, prev_viewport.as_mut_ptr());
    gl_get_integerv(GL_SCISSOR_BOX, prev_scissor_box.as_mut_ptr());
    prev_scissor_enabled = gl_is_enabled(GL_SCISSOR_TEST) as EGLint;

    let mut w: EGLint = 0;
    let mut h: EGLint = 0;
    eglQuerySurface(display, surface, EGL_WIDTH, &mut w);
    eglQuerySurface(display, surface, EGL_HEIGHT, &mut h);
    if w <= 0 || h <= 0 {
        return;
    }

    gl_disable(GL_SCISSOR_TEST);
    gl_viewport(0, 0, w, h);
    gl_clear_color(0.0, 0.0, 0.0, 1.0);
    gl_clear(GL_COLOR_BUFFER_BIT);
    eglSwapBuffers(display, surface);

    gl_clear_color(
        prev_clear_color[0],
        prev_clear_color[1],
        prev_clear_color[2],
        prev_clear_color[3],
    );
    gl_viewport(
        prev_viewport[0],
        prev_viewport[1],
        prev_viewport[2],
        prev_viewport[3],
    );
    gl_scissor(
        prev_scissor_box[0],
        prev_scissor_box[1],
        prev_scissor_box[2],
        prev_scissor_box[3],
    );
    if prev_scissor_enabled != 0 {
        gl_enable(GL_SCISSOR_TEST);
    }

    if context_major >= 3 && !bind_framebuffer.is_null() {
        let gl_bind_framebuffer: unsafe extern "C" fn(u32, u32) = std::mem::transmute(bind_framebuffer);
        const GL_DRAW_FRAMEBUFFER: u32 = 0x8CA9;
        const GL_READ_FRAMEBUFFER: u32 = 0x8CA8;
        gl_bind_framebuffer(GL_DRAW_FRAMEBUFFER, prev_draw_fbo as u32);
        gl_bind_framebuffer(GL_READ_FRAMEBUFFER, prev_read_fbo as u32);
    } else if !bind_framebuffer.is_null() {
        let gl_bind_framebuffer: unsafe extern "C" fn(u32, u32) = std::mem::transmute(bind_framebuffer);
        const GL_FRAMEBUFFER: u32 = 0x8D40;
        gl_bind_framebuffer(GL_FRAMEBUFFER, prev_fbo as u32);
    }
}

// ---------- ANativeWindow 引用台账 ----------

/// JNI 侧到渲染线程的窗口引用余额。
///
/// ANativeWindow 没有读取引用计数的 API，所以泄漏只能靠自己对账。所有权规则：
/// JNI 每次 handoff 携带恰好两个引用（`ANativeWindow_fromSurface` + `ANativeWindow_acquire`）；
/// 渲染线程成功接管后立即退还一个，留一个给 [SurfaceSlot::detach] 退还；同窗口重绑与所有失败
/// 路径把两个全部退还。`eglCreateWindowSurface`/`eglDestroySurface` 对窗口的那对引用在 EGL
/// 内部自身对称，不入账。一个完整的 attach→detach 周期结束后余额必须回到 0，任何漂移都直接
/// 指向泄漏的站点。
pub static WINDOW_REF_BALANCE: AtomicI64 = AtomicI64::new(0);

pub fn window_ref(delta: i64, site: &str) {
    let balance = WINDOW_REF_BALANCE.fetch_add(delta, Ordering::AcqRel) + delta;
    nlog(&format!("WINREF: {site} {delta:+} balance={balance}"));
}

#[cfg(target_os = "android")]
struct SurfaceSlot {
    display: EGLDisplay,
    config: EGLConfig,
    surface: EGLSurface,
    window: *mut ANativeWindow,
}

#[cfg(target_os = "android")]
impl SurfaceSlot {
    fn new(display: EGLDisplay, config: EGLConfig) -> Self {
        Self {
            display,
            config,
            surface: EGL_NO_SURFACE,
            window: std::ptr::null_mut(),
        }
    }

    fn is_ready(&self) -> bool {
        self.surface != EGL_NO_SURFACE && !self.window.is_null()
    }

    fn surface(&self) -> EGLSurface {
        self.surface
    }

    unsafe fn attach(
        &mut self,
        new_window: *mut ANativeWindow,
        context: EGLContext,
        context_major: EGLint,
        _visual_id: EGLint,
    ) -> Result<(), EGLint> {
        assert_render_thread("SurfaceSlot::attach");
        if new_window.is_null() {
            return Err(EGL_BAD_SURFACE);
        }

        if !self.window.is_null() && self.window == new_window && self.surface != EGL_NO_SURFACE {
            // Same window: the slot keeps the reference it already holds, so the entire JNI
            // handoff (fromSurface + acquire) is returned right here.
            window_ref(-2, "attach(same-window) release handoff");
            ANativeWindow_release(new_window);
            ANativeWindow_release(new_window);
            if eglMakeCurrent(self.display, self.surface, self.surface, context) != EGL_TRUE {
                let err = egl_failure("eglMakeCurrent(reattach-same)");
                return Err(err);
            }
            return Ok(());
        }

        self.detach();

        let attribs = [EGL_NONE];
        let surface = eglCreateWindowSurface(self.display, self.config, new_window, attribs.as_ptr());
        if surface == EGL_NO_SURFACE {
            let err = egl_failure("eglCreateWindowSurface");
            window_ref(-2, "attach(createSurface failed) release handoff");
            ANativeWindow_release(new_window);
            ANativeWindow_release(new_window);
            return Err(err);
        }

        if eglMakeCurrent(self.display, surface, surface, context) != EGL_TRUE {
            let err = egl_failure("eglMakeCurrent(attach)");
            eglDestroySurface(self.display, surface);
            window_ref(-2, "attach(makeCurrent failed) release handoff");
            ANativeWindow_release(new_window);
            ANativeWindow_release(new_window);
            return Err(err);
        }

        eglSwapInterval(self.display, 1);
        clear_window_surface(self.display, surface, context_major);

        self.surface = surface;
        self.window = new_window;
        // Ownership established: the slot keeps one reference for detach to release, and the
        // remaining handoff reference goes back. This is the release the old pipeline was
        // missing on this path — every fresh attach leaked one reference.
        window_ref(-1, "attach(success) release handoff, slot keeps 1");
        ANativeWindow_release(new_window);
        Ok(())
    }

    unsafe fn detach(&mut self) {
        assert_render_thread("SurfaceSlot::detach");
        if self.surface != EGL_NO_SURFACE {
            eglMakeCurrent(self.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if eglDestroySurface(self.display, self.surface) != EGL_TRUE {
                egl_failure("eglDestroySurface");
            }
            self.surface = EGL_NO_SURFACE;
        }
        if !self.window.is_null() {
            window_ref(-1, "detach release slot reference");
            ANativeWindow_release(self.window);
            self.window = std::ptr::null_mut();
        }
    }

    unsafe fn drop_surface(&mut self) {
        assert_render_thread("SurfaceSlot::drop_surface");
        if self.surface != EGL_NO_SURFACE {
            eglMakeCurrent(self.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            let _ = eglDestroySurface(self.display, self.surface);
            self.surface = EGL_NO_SURFACE;
        }
    }

    unsafe fn rebuild_surface(
        &mut self,
        context: EGLContext,
        context_major: EGLint,
    ) -> Result<(), EGLint> {
        assert_render_thread("SurfaceSlot::rebuild_surface");
        if self.window.is_null() {
            return Ok(());
        }
        let attribs = [EGL_NONE];
        let surface = eglCreateWindowSurface(self.display, self.config, self.window, attribs.as_ptr());
        if surface == EGL_NO_SURFACE {
            let err = egl_failure("eglCreateWindowSurface(rebuild)");
            return Err(err);
        }
        if eglMakeCurrent(self.display, surface, surface, context) != EGL_TRUE {
            let err = egl_failure("eglMakeCurrent(rebuild)");
            let _ = eglDestroySurface(self.display, surface);
            return Err(err);
        }
        eglSwapInterval(self.display, 1);
        clear_window_surface(self.display, surface, context_major);
        self.surface = surface;
        Ok(())
    }
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
unsafe fn ensure_render_context(mpv_render_ctx: &mut *mut c_void) {
    if !mpv_render_ctx.is_null() {
        return;
    }
    with_api_logged("mpv_render_context_create", |api| {
        let (_guard, handle) = match mpv_handle_guarded() {
            Ok(h) => h,
            Err(_) => return,
        };
        let mut init_params = MpvOpenGLInitParams {
            get_proc_address: gl_proc_address,
            get_proc_address_ctx: std::ptr::null_mut(),
        };
        let mut api_type = [
            b'o' as c_char, b'p' as c_char, b'e' as c_char, b'n' as c_char,
            b'g' as c_char, b'l' as c_char, 0,
        ];
        let mut params = [
            MpvRenderParam {
                param_type: MPV_RENDER_PARAM_API_TYPE,
                data: api_type.as_mut_ptr() as *mut c_void,
            },
            MpvRenderParam {
                param_type: MPV_RENDER_PARAM_OPENGL_INIT_PARAMS,
                data: &mut init_params as *mut _ as *mut c_void,
            },
            MpvRenderParam {
                param_type: 0,
                data: std::ptr::null_mut(),
            },
        ];
        let rc = (api.mpv_render_context_create)(mpv_render_ctx, handle, params.as_mut_ptr());
        if rc == MPV_ERROR_SUCCESS && !mpv_render_ctx.is_null() {
            (api.mpv_render_context_set_update_callback)(
                *mpv_render_ctx,
                Some(mpv_update_callback),
                std::ptr::null_mut(),
            );
            nlog("EGL: mpv_render_context 创建成功并注册 update callback");
        } else {
            nlog(&format!("EGL: mpv_render_context 创建失败 rc={rc}"));
        }
    });
}

#[cfg(target_os = "android")]
unsafe fn free_render_context(
    display: EGLDisplay,
    context: EGLContext,
    mpv_render_ctx: &mut *mut c_void,
) {
    assert_render_thread("free_render_context");
    if mpv_render_ctx.is_null() {
        return;
    }
    let target = bind_probe_target(display, context);
    with_api_logged("mpv_render_context_free", |api| {
        (api.mpv_render_context_set_update_callback)(*mpv_render_ctx, None, std::ptr::null_mut());
        (api.mpv_render_context_free)(*mpv_render_ctx);
    });
    *mpv_render_ctx = std::ptr::null_mut();
    drain_active_fences();
    if let Some(target) = target {
        target.release(display);
    }
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
unsafe fn skip_frame_native(ctx: *mut c_void) {
    let _ = with_api(|api| {
        let flags = (api.mpv_render_context_update)(ctx);
        if flags & MPV_RENDER_UPDATE_FRAME == 0 {
            return;
        }
        let skip: c_int = 1;
        let block_for_target: c_int = 0;
        let mut params = [
            MpvRenderParam {
                param_type: MPV_RENDER_PARAM_SKIP_RENDERING,
                data: &skip as *const _ as *mut c_void,
            },
            MpvRenderParam {
                param_type: MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME,
                data: &block_for_target as *const _ as *mut c_void,
            },
            MpvRenderParam {
                param_type: 0,
                data: std::ptr::null_mut(),
            },
        ];
        let _ = (api.mpv_render_context_render)(ctx, params.as_mut_ptr());
    });
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
enum DrawOutcome {
    Presented,
    Skipped,
    ContextLost,
}

#[cfg(target_os = "android")]
unsafe fn draw_frame_native(
    display: EGLDisplay,
    surface: EGLSurface,
    ctx: *mut c_void,
    force: bool,
) -> DrawOutcome {
    if ctx.is_null() || surface == EGL_NO_SURFACE {
        return DrawOutcome::Skipped;
    }
    assert_render_thread("draw_frame_native");
    with_api(|api| {
        let flags = (api.mpv_render_context_update)(ctx);
        if !force && (flags & MPV_RENDER_UPDATE_FRAME == 0) {
            let starved = G_FRAMES_STARVED.fetch_add(1, Ordering::Relaxed) + 1;
            if starved % 120 == 0 {
                nlog(&format!(
                    "RENDER: presented={} starved={}",
                    G_FRAMES_DRAWN.load(Ordering::Relaxed),
                    starved
                ));
            }
            return DrawOutcome::Skipped;
        }

        let awaiting_first_frame = G_AWAITING_FIRST_FRAME.load(Ordering::Acquire);
        let mut frame_info = MpvRenderFrameInfo { flags: 0, target_time: 0 };
        let param = MpvRenderParam {
            param_type: MPV_RENDER_PARAM_NEXT_FRAME_INFO,
            data: &mut frame_info as *mut MpvRenderFrameInfo as *mut c_void,
        };
        let have_info = (api.mpv_render_context_get_info)(ctx, param) == MPV_ERROR_SUCCESS;
        let is_redraw = have_info && frame_info.flags & MPV_RENDER_FRAME_INFO_REDRAW != 0;
        if is_redraw && awaiting_first_frame {
            nlog("RENDER: 换片中的重绘帧（内容仍是上一部），继续等待首帧");
        }

        let mut w: EGLint = 0;
        let mut h: EGLint = 0;
        eglQuerySurface(display, surface, EGL_WIDTH, &mut w);
        eglQuerySurface(display, surface, EGL_HEIGHT, &mut h);
        if w <= 0 || h <= 0 {
            return DrawOutcome::Skipped;
        }

        let fbo = MpvOpenGLFbo {
            fbo: 0,
            w,
            h,
            internal_format: 0,
        };
        let flip: c_int = 1;
        let pts_fn = get_egl_presentation_time_fn();
        let block_for_target: c_int = if pts_fn.is_some() { 0 } else { 1 };
        let mut params = [
            MpvRenderParam { param_type: MPV_RENDER_PARAM_OPENGL_FBO, data: &fbo as *const _ as *mut c_void },
            MpvRenderParam { param_type: MPV_RENDER_PARAM_FLIP_Y, data: &flip as *const _ as *mut c_void },
            MpvRenderParam { param_type: MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME, data: &block_for_target as *const _ as *mut c_void },
            MpvRenderParam { param_type: 0, data: std::ptr::null_mut() },
        ];

        let presentation_ns = if have_info && !is_redraw && frame_info.target_time > 0 {
            let mpv_h = G_MPVP.load(Ordering::Acquire);
            let now_mpv_us = (api.mpv_get_time_us)(mpv_h);
            let now_mono_ns = monotonic_ns();
            compute_monotonic_presentation_ns(frame_info.target_time, now_mpv_us, now_mono_ns)
        } else {
            None
        };

        let render_start = Instant::now();
        let render_rc = (api.mpv_render_context_render)(ctx, params.as_mut_ptr());
        let render_ms = render_start.elapsed().as_millis();

        if let (Some(target_mono_ns), Some(set_pts)) = (presentation_ns, pts_fn) {
            set_pts(display, surface, target_mono_ns);
        }

        let swap_start = Instant::now();
        let swap_rc = eglSwapBuffers(display, surface);
        let swap_ms = swap_start.elapsed().as_millis();
        if render_ms > 150 || swap_ms > 150 {
            nlog(&format!(
                "RENDER: SLOW render={render_ms}ms swap={swap_ms}ms buf={w}x{h}"
            ));
        }

        // The geometry probe only feeds the first-frame gate below: its verdict is consumed
        // exclusively inside the `awaiting_first_frame` branch, where it keeps a redraw of the
        // previous file's last buffer from being mistaken for the new file's first frame. During
        // steady playback nobody reads it, so polling dwidth/dheight/rotate here would burn 3-4
        // synchronous mpv_get_property calls per presented frame for nothing.
        let (final_w, final_h, aspect_mismatch) = if awaiting_first_frame {
            let mpv_h = G_MPVP.load(Ordering::Acquire);
            let mut dwidth: i64 = 0;
            let mut dheight: i64 = 0;
            let mut rotate: i64 = 0;
            if !mpv_h.is_null() {
                let dwidth_cstr = b"dwidth\0";
                let dheight_cstr = b"dheight\0";
                let rotate_cstr = b"video-params/rotate\0";
                let _ = (api.mpv_get_property)(
                    mpv_h,
                    dwidth_cstr.as_ptr() as *const c_char,
                    MPV_FORMAT_INT64,
                    &mut dwidth as *mut _ as *mut c_void,
                );
                let _ = (api.mpv_get_property)(
                    mpv_h,
                    dheight_cstr.as_ptr() as *const c_char,
                    MPV_FORMAT_INT64,
                    &mut dheight as *mut _ as *mut c_void,
                );
                let _ = (api.mpv_get_property)(
                    mpv_h,
                    rotate_cstr.as_ptr() as *const c_char,
                    MPV_FORMAT_INT64,
                    &mut rotate as *mut _ as *mut c_void,
                );
                if rotate == 0 {
                    let out_rotate_cstr = b"video-out-params/rotate\0";
                    let _ = (api.mpv_get_property)(
                        mpv_h,
                        out_rotate_cstr.as_ptr() as *const c_char,
                        MPV_FORMAT_INT64,
                        &mut rotate as *mut _ as *mut c_void,
                    );
                }
            }

            let (fw, fh) = if rotate % 180 != 0 {
                (dheight, dwidth)
            } else {
                (dwidth, dheight)
            };
            let mismatch = if fw > 0 && fh > 0 && w > 0 && h > 0 {
                let vid_aspect = fw as f64 / fh as f64;
                let buf_aspect = w as f64 / h as f64;
                (buf_aspect / vid_aspect - 1.0).abs() > 0.08
            } else {
                false
            };
            (fw, fh, mismatch)
        } else {
            (0, 0, false)
        };

        if render_rc == MPV_ERROR_SUCCESS && swap_rc == EGL_TRUE {
            (api.mpv_render_context_report_swap)(ctx);
            let file_loaded = G_FILE_LOADED_REACHED.load(Ordering::Acquire);
            if awaiting_first_frame {
                crate::tracelog(
                    "NATIVE_SWAP_WHILE_AWAITING",
                    &format!(
                        "force={force}, is_redraw={is_redraw}, file_loaded={file_loaded}, buf={w}x{h}, vid={final_w}x{final_h}, mismatch={aspect_mismatch}"
                    ),
                );
                // Pipeline separation (Plan A):
                // 1. Redraws (force=true) and mpv internal repaints (is_redraw=true) are NEVER first frames.
                // 2. Demuxer must have loaded the file (file_loaded=true).
                // 3. Buffer geometry must match video aspect (aspect_mismatch=false).
                if file_loaded && !force && !is_redraw && !aspect_mismatch {
                    crate::events::signal_first_frame();
                } else {
                    crate::tracelog(
                        "NATIVE_SUPPRESS_FIRST_FRAME",
                        &format!(
                            "file_loaded={file_loaded}, force={force}, is_redraw={is_redraw}, mismatch={aspect_mismatch}"
                        ),
                    );
                }
            }
        }
        let elapsed = render_start.elapsed();

        if swap_rc != EGL_TRUE {
            let err = eglGetError();
            nlog(&format!("RENDER: eglSwapBuffers 失败 rc={swap_rc} error={}(0x{err:X})", egl_error_name(err)));
            return if err == EGL_CONTEXT_LOST {
                DrawOutcome::ContextLost
            } else {
                DrawOutcome::Skipped
            };
        }

        let now_ms = monotonic_ms() as u64;
        let previous_ms = G_LAST_DRAW_MS.swap(now_ms, Ordering::Relaxed);
        let gap_ms = if previous_ms == 0 { 0 } else { now_ms.saturating_sub(previous_ms) as u128 };
        if gap_ms > 100 {
            nlog(&format!("RENDER: GAP {gap_ms}ms buf={w}x{h}"));
        }

        let presented = G_FRAMES_DRAWN.fetch_add(1, Ordering::Relaxed) + 1;
        if presented <= 3 || presented % 300 == 0 {
            let (delayed, mistimed, vo_drop, dec_drop) = match crate::events::G_FRAME_HEALTH.lock() {
                Ok(m) => (
                    m.late_frames(),
                    m.mistimed_frames(),
                    m.skipped_frames(),
                    m.dropped_frames(),
                ),
                Err(_) => (0, 0, 0, 0),
            };
            nlog(&format!(
                "RENDER: presented={} elapsed={}ms starved={} vo_drop={} dec_drop={} delayed={} mistimed={}",
                presented,
                elapsed.as_millis(),
                G_FRAMES_STARVED.load(Ordering::Relaxed),
                vo_drop,
                dec_drop,
                delayed,
                mistimed
            ));
        }

        DrawOutcome::Presented
    })
    .unwrap_or(DrawOutcome::Skipped)
}

#[cfg(target_os = "android")]
unsafe fn forget_active_fences() {
    if let Ok(mut fences) = ACTIVE_FENCES.lock() {
        if !fences.is_empty() {
            nlog(&format!("EGL: 上下文已丢失，丢弃 {} 个 fence 句柄", fences.len()));
            fences.clear();
        }
    }
}

#[cfg(target_os = "android")]
unsafe fn revive_dead_pipeline(core: &mut EglCore, mpv_render_ctx: &mut *mut c_void) -> bool {
    if !G_RENDER_DEAD.load(Ordering::Acquire) && core.context != EGL_NO_CONTEXT {
        return true;
    }
    nlog("EGL: 新 surface 到达，尝试为已停用的管线重建上下文");

    if !mpv_render_ctx.is_null() {
        with_api_logged("mpv_render_context_free", |api| {
            (api.mpv_render_context_set_update_callback)(*mpv_render_ctx, None, std::ptr::null_mut());
            (api.mpv_render_context_free)(*mpv_render_ctx);
        });
        *mpv_render_ctx = std::ptr::null_mut();
    }
    forget_active_fences();

    if !core.recreate_context() {
        return false;
    }
    if let Ok(mut recovery) = G_RECOVERY.lock() {
        *recovery = ContextRecovery::with_defaults();
    }
    G_RENDER_DEAD.store(false, Ordering::Release);

    request_video_rearm();
    nlog("EGL: 管线已恢复，渲染重新启用");
    true
}

#[cfg(target_os = "android")]
unsafe fn recover_gl_context(
    core: &mut EglCore,
    slot: &mut SurfaceSlot,
    mpv_render_ctx: &mut *mut c_void,
) -> bool {
    let verdict = match G_RECOVERY.lock() {
        Ok(mut recovery) => recovery.on_context_lost(),
        Err(_) => RecoveryVerdict::Exhausted,
    };
    if verdict == RecoveryVerdict::Exhausted {
        if !G_RENDER_DEAD.swap(true, Ordering::AcqRel) {
            nlog("EGL: 重建预算已用尽，停止重建（驱动持续丢失上下文），渲染已停用");
        }
        return false;
    }

    if !mpv_render_ctx.is_null() {
        with_api_logged("mpv_render_context_free", |api| {
            (api.mpv_render_context_set_update_callback)(*mpv_render_ctx, None, std::ptr::null_mut());
            (api.mpv_render_context_free)(*mpv_render_ctx);
        });
        *mpv_render_ctx = std::ptr::null_mut();
    }
    forget_active_fences();

    slot.drop_surface();
    if !core.recreate_context() {
        return false;
    }
    if let Err(err) = slot.rebuild_surface(core.context, core.context_major) {
        nlog(&format!("EGL: 重建窗口 surface 失败 (error=0x{err:X})"));
        return false;
    }

    ensure_render_context(mpv_render_ctx);
    if mpv_render_ctx.is_null() {
        nlog("EGL: 重建后 mpv_render_context 创建失败");
        return false;
    }

    request_video_rearm();
    nlog("EGL: 上下文管线已重建，恢复渲染");
    true
}

#[cfg(target_os = "android")]
unsafe fn draw_and_recover(
    core: &mut EglCore,
    slot: &mut SurfaceSlot,
    mpv_render_ctx: &mut *mut c_void,
    force: bool,
) {
    if !slot.is_ready() || mpv_render_ctx.is_null() {
        return;
    }
    if G_RENDER_DEAD.load(Ordering::Acquire) {
        return;
    }
    let outcome = draw_frame_native(core.display, slot.surface(), *mpv_render_ctx, force);
    if outcome != DrawOutcome::ContextLost {
        return;
    }

    nlog("EGL: eglSwapBuffers 报告上下文丢失，开始重建管线");
    if recover_gl_context(core, slot, mpv_render_ctx) {
        draw_frame_native(core.display, slot.surface(), *mpv_render_ctx, true);
    }
}

unsafe extern "C" fn mpv_update_callback(_ctx: *mut c_void) {
    if !G_FRAME_GATE.try_acquire() {
        return;
    }
    let delivered = if let Some(lock) = RENDER_THREAD.get() {
        if let Ok(guard) = lock.lock() {
            if let Some(ref handle) = *guard {
                handle.sender.send(RenderCommand::NotifyFrame).is_ok()
            } else {
                false
            }
        } else {
            false
        }
    } else {
        false
    };
    if !delivered {
        G_FRAME_GATE.release();
    }
}

pub fn render_sender() -> Option<Sender<RenderCommand>> {
    let lock = RENDER_THREAD.get()?;
    let guard = lock.lock().ok()?;
    match guard.as_ref() {
        Some(handle) if !handle._thread.is_finished() => Some(handle.sender.clone()),
        _ => None,
    }
}

pub fn ensure_render_thread() {
    let lock = RENDER_THREAD.get_or_init(|| Mutex::new(None));
    if let Ok(mut guard) = lock.lock() {
        let is_dead = match guard.as_ref() {
            None => true,
            Some(handle) => handle._thread.is_finished(),
        };
        if is_dead {
            let (sender, receiver) = channel();
            let thread = std::thread::Builder::new()
                .name("gyro-render".into())
                .spawn(move || native_render_loop(receiver))
                .expect("spawn gyro-render failed");
            *guard = Some(RenderThreadHandle { sender, _thread: thread });
            nlog("ensure_render_thread: gyro-render 原生线程已启动");
        }
    }
}

pub fn clear_render_thread() {
    if let Some(lock) = RENDER_THREAD.get() {
        if let Ok(mut guard) = lock.lock() {
            *guard = None;
        }
    }
}

pub fn destroy_render_context_sync() {
    if let Some(sender) = render_sender() {
        let outcome = request_ack(
            &sender,
            RenderCommand::DestroyContext,
            std::time::Duration::from_millis(DESTROY_ACK_TIMEOUT_MS),
        );
        if outcome != AckOutcome::Acknowledged {
            nlog(&format!(
                "destroy_render_context_sync: 渲染线程未在 {DESTROY_ACK_TIMEOUT_MS}ms 内确认释放 render context ({outcome:?})，可能仍在渲染"
            ));
        }
    }
    if let Ok(mut fences) = ACTIVE_FENCES.lock() {
        fences.clear();
    }
}

pub fn native_render_loop(receiver: Receiver<RenderCommand>) {
    let owner_tid = logical_thread_id();
    if let Ok(mut affinity) = G_AFFINITY.lock() {
        affinity.claim(owner_tid);
    }
    G_AFFINITY_OWNER.store(owner_tid, Ordering::Release);

    #[cfg(target_os = "android")]
    unsafe {
        let display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if display == EGL_NO_DISPLAY {
            egl_failure("eglGetDisplay");
            return;
        }

        let mut major: EGLint = 0;
        let mut minor: EGLint = 0;
        if eglInitialize(display, &mut major, &mut minor) != EGL_TRUE {
            egl_failure("eglInitialize");
            return;
        }
        nlog(&format!("EGL: 初始化完成 version={major}.{minor}"));

        let chosen = match choose_egl_config(display, false) {
            Some(c) => c,
            None => return,
        };

        let mut context: EGLContext = EGL_NO_CONTEXT;
        let mut context_major: EGLint = 0;
        for v in CONTEXT_CLIENT_VERSIONS {
            context = EglCore::create_context_with_major(display, chosen.config, v);
            if context != EGL_NO_CONTEXT {
                nlog(&format!("EGL: 成功创建 OpenGL ES 上下文 (v{v})"));
                context_major = v;
                break;
            }
        }
        if context == EGL_NO_CONTEXT {
            egl_failure("eglCreateContext(all-versions)");
            return;
        }

        probe_graphics_environment(display, context);

        let mut core = EglCore {
            display,
            config: chosen.config,
            context,
            context_major,
            visual_id: chosen.visual_id,
        };

        let mut slot = SurfaceSlot::new(display, chosen.config);
        let mut mpv_render_ctx: *mut c_void = std::ptr::null_mut();

        while let Ok(cmd) = receiver.recv() {
            match cmd {
                RenderCommand::AttachSurface(NativeWindowPtr(window)) => {
                    nlog("EGL: 收到 AttachSurface 命令");
                    let pipeline_ok = revive_dead_pipeline(&mut core, &mut mpv_render_ctx);
                    match slot.attach(window, core.context, core.context_major, core.visual_id) {
                        Ok(()) => {
                            if slot.is_ready() {
                                ensure_render_context(&mut mpv_render_ctx);
                            }
                            nlog(&format!(
                                "EGL: attach 完成 ready={} ctx_null={}",
                                slot.is_ready(),
                                mpv_render_ctx.is_null()
                            ));
                        }
                        Err(err) => {
                            nlog(&format!(
                                "EGL: attach 失败 (error=0x{err:X}) pipeline_ok={pipeline_ok}"
                            ));
                        }
                    }
                }
                RenderCommand::DetachSurface => {
                    slot.detach();
                    nlog("EGL: Surface 已解绑 (EGLContext 与 render context 常驻)");
                }
                RenderCommand::Redraw => {
                    let awaiting = G_AWAITING_FIRST_FRAME.load(Ordering::Acquire);
                    let file_loaded = G_FILE_LOADED_REACHED.load(Ordering::Acquire);
                    let ready = slot.is_ready();
                    crate::tracelog(
                        "NATIVE_CMD_REDRAW",
                        &format!("slot_ready={ready}, awaiting_first_frame={awaiting}, file_loaded={file_loaded}"),
                    );
                    if awaiting {
                        crate::tracelog("NATIVE_REDRAW_SKIPPED_AWAITING_FIRST_FRAME", "");
                        continue;
                    }
                    nlog("EGL: 收到 Redraw 命令");
                    if slot.is_ready() && !mpv_render_ctx.is_null() {
                        draw_and_recover(&mut core, &mut slot, &mut mpv_render_ctx, true);
                    }
                }
                RenderCommand::NotifyFrame => {
                    G_FRAME_GATE.release();
                    if slot.is_ready() {
                        ensure_render_context(&mut mpv_render_ctx);
                        draw_and_recover(&mut core, &mut slot, &mut mpv_render_ctx, false);
                    } else if !mpv_render_ctx.is_null() {
                        skip_frame_native(mpv_render_ctx);
                    }
                }
                RenderCommand::DestroyContext(ack) => {
                    nlog("EGL: 收到 DestroyContext 请求，开始销毁 mpv_render_context");
                    if !mpv_render_ctx.is_null() {
                        free_render_context(core.display, core.context, &mut mpv_render_ctx);
                        nlog("EGL: mpv_render_context 已在渲染线程安全释放");
                    }
                    let _ = ack.send(());
                }
                RenderCommand::Shutdown => {
                    nlog("EGL: 渲染线程正在退出并清理资源");
                    free_render_context(core.display, core.context, &mut mpv_render_ctx);
                    slot.detach();
                    core.destroy();
                    reset_engine_lifecycle_state();
                    if let Ok(mut affinity) = G_AFFINITY.lock() {
                        affinity.release(owner_tid);
                    }
                    G_AFFINITY_OWNER.store(0, Ordering::Release);
                    break;
                }
            }
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        while let Ok(cmd) = receiver.recv() {
            match cmd {
                RenderCommand::DestroyContext(ack) => {
                    let _ = ack.send(());
                }
                RenderCommand::Shutdown => {
                    reset_engine_lifecycle_state();
                    break;
                }
                _ => {}
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn window_ref_balance_returns_to_baseline_after_a_full_handoff_cycle() {
        let baseline = WINDOW_REF_BALANCE.load(Ordering::Acquire);
        // A successful attach: JNI hands off two references, the render thread releases the
        // handoff immediately and detach releases the slot's own reference later.
        window_ref(2, "test handoff");
        assert_eq!(baseline + 2, WINDOW_REF_BALANCE.load(Ordering::Acquire));
        window_ref(-1, "test release handoff");
        window_ref(-1, "test detach release");
        assert_eq!(baseline, WINDOW_REF_BALANCE.load(Ordering::Acquire));

        // A failed attach returns the whole handoff in one place.
        window_ref(2, "test handoff");
        window_ref(-2, "test failed-attach release");
        assert_eq!(baseline, WINDOW_REF_BALANCE.load(Ordering::Acquire));
    }
}
