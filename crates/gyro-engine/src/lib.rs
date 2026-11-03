//! Gyro Engine: libmpv 核心 JNI 桥与完全下沉的纯原生 EGL 渲染管线

mod logic;

use logic::{AckOutcome, AffinityVerdict, FrameCoalescer, ThreadAffinity, request_ack};
// Only the Android render loop reacts to a lost context.
#[cfg(target_os = "android")]
use logic::{ContextRecovery, RecoveryVerdict};
// The EGL bootstrap exists only in the Android render thread; the tables it walks are pure and
// host-tested in `logic`.
#[cfg(target_os = "android")]
use logic::{
    CONTEXT_CLIENT_VERSIONS, EGL_BAD_ALLOC, EGL_BAD_SURFACE, EGL_CONTEXT_LOST, EglConfigRequest,
    egl_config_attempts, egl_error_name,
};
// Frame-loss counters are mpv's own verdicts, sampled on the event thread on every platform.
use logic::{FrameCounter, FrameHealthMonitor};
use jni::objects::{GlobalRef, JClass, JObject, JObjectArray, JString, JValue};
use jni::sys::{jboolean, jdouble, jint, jlong, jobject, JNI_VERSION_1_6};
use jni::{JNIEnv, JavaVM};
use libloading::{Library, Symbol};
use std::ffi::{c_char, c_double, c_int, c_void, CStr, CString};
use std::sync::atomic::{AtomicBool, AtomicPtr, AtomicU64, Ordering};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Mutex, OnceLock};
use std::thread::JoinHandle;
use std::time::Instant;

// ---------- NDK 原生绑定 (EGL / Android Native Window) ----------

type EGLDisplay = *mut c_void;
type EGLConfig = *mut c_void;
type EGLContext = *mut c_void;
type EGLSurface = *mut c_void;
type EGLint = i32;
type ANativeWindow = c_void;

#[derive(Clone, Copy)]
struct NativeWindowPtr(*mut ANativeWindow);
unsafe impl Send for NativeWindowPtr {}
unsafe impl Sync for NativeWindowPtr {}

const EGL_NO_DISPLAY: EGLDisplay = std::ptr::null_mut();
const EGL_NO_CONTEXT: EGLContext = std::ptr::null_mut();
const EGL_NO_SURFACE: EGLSurface = std::ptr::null_mut();
const EGL_DEFAULT_DISPLAY: *mut c_void = std::ptr::null_mut();

const EGL_TRUE: EGLint = 1;
const EGL_WIDTH: EGLint = 0x3057;
const EGL_HEIGHT: EGLint = 0x3056;
const EGL_RENDERABLE_TYPE: EGLint = 0x3040;
const EGL_OPENGL_ES2_BIT: EGLint = 0x00000004;
const EGL_OPENGL_ES3_BIT: EGLint = 0x00000040;
const EGL_SURFACE_TYPE: EGLint = 0x3033;
const EGL_WINDOW_BIT: EGLint = 0x0004;
const EGL_PBUFFER_BIT: EGLint = 0x0001;
const EGL_RED_SIZE: EGLint = 0x3024;
const EGL_GREEN_SIZE: EGLint = 0x3023;
const EGL_BLUE_SIZE: EGLint = 0x3022;
const EGL_ALPHA_SIZE: EGLint = 0x3021;
const EGL_DEPTH_SIZE: EGLint = 0x3025;
const EGL_STENCIL_SIZE: EGLint = 0x3026;
const EGL_NONE: EGLint = 0x3038;
const EGL_CONTEXT_CLIENT_VERSION: EGLint = 0x3098;
const EGL_NATIVE_VISUAL_ID: EGLint = 0x302E;
const EGL_EXTENSIONS: EGLint = 0x3055;
const EGL_CONFIG_CAVEAT: EGLint = 0x3027;
const EGL_CONFIG_ID: EGLint = 0x3028;
const EGL_SLOW_CONFIG: EGLint = 0x3050;
const EGL_NON_CONFORMANT_CONFIG: EGLint = 0x3051;

const GL_VENDOR: u32 = 0x1F00;
const GL_RENDERER: u32 = 0x1F01;
const GL_EXTENSIONS: u32 = 0x1F03;

/// Upper bound for the attach handshake; the caller is the UI thread, so it must never be unbounded.
#[cfg(target_os = "android")]
const ATTACH_ACK_TIMEOUT_MS: u64 = 1000;
/// How long `surfaceCreated` may block before the wait is worth reporting as an ANR risk.
#[cfg(target_os = "android")]
const ATTACH_WAIT_BUDGET_MS: u128 = 200;

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
    fn ANativeWindow_fromSurface(env: *mut jni::sys::JNIEnv, surface: jobject) -> *mut ANativeWindow;
    fn ANativeWindow_acquire(window: *mut ANativeWindow);
    fn ANativeWindow_release(window: *mut ANativeWindow);
    fn ANativeWindow_getWidth(window: *mut ANativeWindow) -> i32;
    fn ANativeWindow_getHeight(window: *mut ANativeWindow) -> i32;
    fn ANativeWindow_setBuffersGeometry(
        window: *mut ANativeWindow,
        width: i32,
        height: i32,
        format: i32,
    ) -> i32;
}

#[cfg(target_os = "android")]
#[link(name = "log")]
extern "C" {
    fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

fn nlog(msg: &str) {
    #[cfg(target_os = "android")]
    {
        if let (Ok(text), Ok(tag)) = (CString::new(msg), CString::new("Gyro/Engine")) {
            // Priority 4 (INFO), not 3 (DEBUG): user builds filter DEBUG from third-party apps,
            // which silently swallowed every diagnostic this engine emits.
            unsafe { __android_log_write(4, tag.as_ptr(), text.as_ptr()) };
        }
    }
    #[cfg(not(target_os = "android"))]
    let _ = msg;
}

// ---------- libmpv 常量与类型 ----------

const MPV_FORMAT_NONE: c_int = 0;
const MPV_FORMAT_STRING: c_int = 1;
const MPV_FORMAT_FLAG: c_int = 3;
const MPV_FORMAT_INT64: c_int = 4;
const MPV_FORMAT_DOUBLE: c_int = 5;

const MPV_EVENT_SHUTDOWN: c_int = 1;
const MPV_EVENT_LOG_MESSAGE: c_int = 2;
const MPV_EVENT_FILE_LOADED: c_int = 8;
const MPV_EVENT_END_FILE: c_int = 7;
const MPV_EVENT_PLAYBACK_RESTART: c_int = 21;
const MPV_EVENT_PROPERTY_CHANGE: c_int = 22;

const MPV_ERROR_SUCCESS: c_int = 0;
const MPV_RENDER_PARAM_API_TYPE: c_int = 1;
const MPV_RENDER_PARAM_OPENGL_INIT_PARAMS: c_int = 2;
const MPV_RENDER_PARAM_OPENGL_FBO: c_int = 3;
const MPV_RENDER_PARAM_FLIP_Y: c_int = 4;
const MPV_RENDER_UPDATE_FRAME: u64 = 1;

#[repr(C)]
struct MpvRenderParam {
    param_type: c_int,
    data: *mut c_void,
}

#[repr(C)]
struct MpvOpenGLInitParams {
    get_proc_address: unsafe extern "C" fn(*mut c_void, *const c_char) -> *mut c_void,
    get_proc_address_ctx: *mut c_void,
}

#[repr(C)]
struct MpvOpenGLFbo {
    fbo: c_int,
    w: c_int,
    h: c_int,
    internal_format: c_int,
}

macro_rules! sym {
    ($lib:expr, $name:ident, $ty:ty) => {{
        let s: Symbol<$ty> = match unsafe { $lib.get(stringify!($name).as_bytes()) } {
            Ok(s) => s,
            Err(_) => return Err(format!("symbol {} not found", stringify!($name))),
        };
        *s
    }};
}

struct MpvApi {
    mpv_create: unsafe extern "C" fn() -> *mut c_void,
    mpv_initialize: unsafe extern "C" fn(*mut c_void) -> c_int,
    mpv_terminate_destroy: unsafe extern "C" fn(*mut c_void),
    mpv_set_option_string: unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char) -> c_int,
    mpv_command: unsafe extern "C" fn(*mut c_void, *mut *mut c_char) -> c_int,
    mpv_get_property: unsafe extern "C" fn(*mut c_void, *const c_char, c_int, *mut c_void) -> c_int,
    mpv_set_property: unsafe extern "C" fn(*mut c_void, *const c_char, c_int, *mut c_void) -> c_int,
    mpv_observe_property: unsafe extern "C" fn(*mut c_void, u64, *const c_char, c_int) -> c_int,
    mpv_request_log_messages: unsafe extern "C" fn(*mut c_void, *const c_char) -> c_int,
    mpv_wait_event: unsafe extern "C" fn(*mut c_void, c_double) -> *mut c_void,
    mpv_wakeup: unsafe extern "C" fn(*mut c_void),
    mpv_render_context_create:
        unsafe extern "C" fn(*mut *mut c_void, *mut c_void, *mut MpvRenderParam) -> c_int,
    mpv_render_context_render: unsafe extern "C" fn(*mut c_void, *mut MpvRenderParam) -> c_int,
    mpv_render_context_report_swap: unsafe extern "C" fn(*mut c_void),
    mpv_render_context_update: unsafe extern "C" fn(*mut c_void) -> u64,
    mpv_render_context_set_update_callback:
        unsafe extern "C" fn(*mut c_void, Option<unsafe extern "C" fn(*mut c_void)>, *mut c_void),
    mpv_render_context_free: unsafe extern "C" fn(*mut c_void),
    mpv_free: unsafe extern "C" fn(*mut c_void),
}

unsafe impl Send for MpvApi {}
unsafe impl Sync for MpvApi {}

// ---------- 全局状态与符号解析 ----------

static G_MPVP: AtomicPtr<c_void> = AtomicPtr::new(std::ptr::null_mut());
static G_EVENT_THREAD_EXIT: AtomicBool = AtomicBool::new(false);
static HANDLE_VALID: AtomicBool = AtomicBool::new(false);

struct VmPtr(*mut JavaVM);
unsafe impl Send for VmPtr {}
unsafe impl Sync for VmPtr {}
static G_VM: OnceLock<VmPtr> = OnceLock::new();
static G_APP_CTX: OnceLock<GlobalRef> = OnceLock::new();

static MPV_LIB: OnceLock<Mutex<Option<Library>>> = OnceLock::new();
static API: OnceLock<MpvApi> = OnceLock::new();

fn mpv_lib() -> Result<&'static Mutex<Option<Library>>, String> {
    let lock = MPV_LIB.get_or_init(|| {
        unsafe {
            if let Ok(avutil) = Library::new("libavutil.so") {
                std::mem::forget(avutil);
            }
        }
        Mutex::new(unsafe { Library::new("libmpv.so").ok() })
    });
    let loaded = lock.lock().map(|g| g.is_some()).unwrap_or(false);
    if !loaded {
        return Err("dlopen libmpv.so failed".into());
    }
    Ok(lock)
}

fn cached_api() -> Result<&'static MpvApi, String> {
    if let Some(api) = API.get() {
        return Ok(api);
    }
    let lock = mpv_lib()?;
    let guard = lock.lock().map_err(|_| "mpv lib mutex poisoned")?;
    let lib = match guard.as_ref() {
        Some(l) => l,
        None => return Err("mpv lib unloaded".into()),
    };
    let api = MpvApi {
        mpv_create: sym!(lib, mpv_create, unsafe extern "C" fn() -> *mut c_void),
        mpv_initialize: sym!(lib, mpv_initialize, unsafe extern "C" fn(*mut c_void) -> c_int),
        mpv_terminate_destroy: sym!(lib, mpv_terminate_destroy, unsafe extern "C" fn(*mut c_void)),
        mpv_set_option_string: sym!(lib, mpv_set_option_string, unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char) -> c_int),
        mpv_command: sym!(lib, mpv_command, unsafe extern "C" fn(*mut c_void, *mut *mut c_char) -> c_int),
        mpv_get_property: sym!(lib, mpv_get_property, unsafe extern "C" fn(*mut c_void, *const c_char, c_int, *mut c_void) -> c_int),
        mpv_set_property: sym!(lib, mpv_set_property, unsafe extern "C" fn(*mut c_void, *const c_char, c_int, *mut c_void) -> c_int),
        mpv_observe_property: sym!(lib, mpv_observe_property, unsafe extern "C" fn(*mut c_void, u64, *const c_char, c_int) -> c_int),
        mpv_request_log_messages: sym!(lib, mpv_request_log_messages, unsafe extern "C" fn(*mut c_void, *const c_char) -> c_int),
        mpv_wait_event: sym!(lib, mpv_wait_event, unsafe extern "C" fn(*mut c_void, c_double) -> *mut c_void),
        mpv_wakeup: sym!(lib, mpv_wakeup, unsafe extern "C" fn(*mut c_void)),
        mpv_render_context_create: sym!(lib, mpv_render_context_create, unsafe extern "C" fn(*mut *mut c_void, *mut c_void, *mut MpvRenderParam) -> c_int),
        mpv_render_context_render: sym!(lib, mpv_render_context_render, unsafe extern "C" fn(*mut c_void, *mut MpvRenderParam) -> c_int),
        mpv_render_context_report_swap: sym!(lib, mpv_render_context_report_swap, unsafe extern "C" fn(*mut c_void)),
        mpv_render_context_update: sym!(lib, mpv_render_context_update, unsafe extern "C" fn(*mut c_void) -> u64),
        mpv_render_context_set_update_callback: sym!(lib, mpv_render_context_set_update_callback, unsafe extern "C" fn(*mut c_void, Option<unsafe extern "C" fn(*mut c_void)>, *mut c_void)),
        mpv_render_context_free: sym!(lib, mpv_render_context_free, unsafe extern "C" fn(*mut c_void)),
        mpv_free: sym!(lib, mpv_free, unsafe extern "C" fn(*mut c_void)),
    };
    let _ = API.set(api);
    API.get().ok_or_else(|| "api cache init failed".to_string())
}

fn with_api<T>(f: impl FnOnce(&MpvApi) -> T) -> Result<T, String> {
    Ok(f(cached_api()?))
}

/// Runs an mpv call whose result cannot be acted on, but whose failure must not vanish.
///
/// The only way these fail is a missing symbol in libmpv, which used to surface as a silently
/// dead code path: the pipeline kept running with no render context and no explanation.
fn with_api_logged(what: &str, f: impl FnOnce(&MpvApi)) {
    if let Err(err) = with_api(f) {
        nlog(&format!("mpv: {what} 无法执行 ({err})"));
    }
}

unsafe fn mpv_handle() -> Result<*mut c_void, String> {
    let p = G_MPVP.load(Ordering::Acquire);
    if p.is_null() {
        Err("mpv handle is null".into())
    } else {
        Ok(p)
    }
}

#[cfg(unix)]
extern "C" {
    fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
    fn dlopen(filename: *const c_char, flags: c_int) -> *mut c_void;
}
#[cfg(unix)]
const RTLD_LAZY: c_int = 0x0001;

/// Resolves a symbol out of libavcodec/libavutil for the JNI bridge.
///
/// Only the Android `init` path calls this, but `dlopen`/`dlsym` live in `libdl`, which does not
/// exist in the MSVC import libraries - leaving it unguarded made `cargo test` fail to link on a
/// Windows host and took the whole host test suite with it. Non-unix hosts get a null stub, which
/// the caller already handles as "ffmpeg JNI hooks unavailable".
#[cfg(unix)]
fn resolve_avcodec_symbol(name: &str) -> *mut c_void {
    const CANDIDATES: [&str; 2] = ["libavcodec.so", "libavutil.so"];
    let Ok(cname) = CString::new(name) else { return std::ptr::null_mut() };
    unsafe {
        let mut p = dlsym(std::ptr::null_mut(), cname.as_ptr());
        if p.is_null() {
            for lib in CANDIDATES {
                let Ok(soname) = CString::new(lib) else { continue };
                let handle = dlopen(soname.as_ptr(), RTLD_LAZY);
                if !handle.is_null() {
                    p = dlsym(handle, cname.as_ptr());
                    if !p.is_null() { break; }
                }
            }
        }
        p
    }
}

#[cfg(not(unix))]
fn resolve_avcodec_symbol(_name: &str) -> *mut c_void {
    std::ptr::null_mut()
}

// ---------- 原生渲染命令与常驻线程 ----------

enum RenderCommand {
    AttachSurface(NativeWindowPtr, std::sync::mpsc::SyncSender<()>),
    DetachSurface,
    Redraw,
    NotifyFrame,
    DestroyContext(std::sync::mpsc::SyncSender<()>),
    Shutdown,
}

struct RenderThreadHandle {
    sender: Sender<RenderCommand>,
    _thread: JoinHandle<()>,
}

static RENDER_THREAD: OnceLock<Mutex<Option<RenderThreadHandle>>> = OnceLock::new();
/// Collapses mpv's per-frame update notifications into one queued render command.
static G_FRAME_GATE: FrameCoalescer = FrameCoalescer::new();
/// Frames actually presented, used to tell "nothing drawn" apart from "drawn but black".
static G_FRAMES_DRAWN: AtomicU64 = AtomicU64::new(0);
/// Frames skipped because mpv had nothing new, used to spot a stalled producer.
static G_FRAMES_STARVED: AtomicU64 = AtomicU64::new(0);

// ---------- 线程亲和：GL 上下文只归渲染线程所有 ----------
static G_AFFINITY: Mutex<ThreadAffinity> = Mutex::new(ThreadAffinity::new());
static NEXT_LOGICAL_TID: AtomicU64 = AtomicU64::new(1);

/// Lazily assigned per-thread id. `0` is reserved for "unowned".
fn logical_thread_id() -> u64 {
    thread_local! {
        static TID: u64 = NEXT_LOGICAL_TID.fetch_add(1, Ordering::Relaxed);
    }
    TID.with(|id| *id)
}

/// Fails loudly in debug builds when a GL entry point runs on a thread that does not own the
/// context. Release builds compile this down to a single relaxed atomic load.
#[inline]
unsafe fn assert_render_thread(what: &str) {
    let tid = logical_thread_id();
    let verdict = match G_AFFINITY.lock() {
        Ok(affinity) => affinity.check(tid),
        Err(_) => return,
    };
    if verdict == AffinityVerdict::Violation {
        nlog(&format!("FATAL: {what} 在非渲染线程被调用 (tid={tid})"));
        debug_assert!(false, "{what} called off the render thread");
    }
}

// ---------- 防御层 1：冷启动离屏环境探针 (Static Env Probe) ----------
static G_ZERO_COPY_SAFE: AtomicBool = AtomicBool::new(true);
static G_GPU_RENDERER: OnceLock<String> = OnceLock::new();
static G_GPU_VENDOR: OnceLock<String> = OnceLock::new();

// ---------- 帧健康监测 (Frame Health) ----------
// 判定逻辑见 logic::FrameHealthMonitor（宿主机可单测）；这里只负责把 mpv 的计数器喂给它。
static G_FRAME_HEALTH: Mutex<FrameHealthMonitor> = Mutex::new(FrameHealthMonitor::with_defaults());
/// Monotonic base for the monitor's windows; the first sample starts the clock.
static G_HEALTH_CLOCK: OnceLock<Instant> = OnceLock::new();

// ---------- 防御层 3：GL 上下文丢失重建 (Context Recovery) ----------
// 判定逻辑同样在 logic::ContextRecovery（宿主机可单测）
#[cfg(target_os = "android")]
static G_RECOVERY: Mutex<ContextRecovery> = Mutex::new(ContextRecovery::with_defaults());
/// Set once the rebuild budget is gone. Every later frame would fail the same swap, so the render
/// thread stops touching GL instead of retrying and logging at frame rate.
#[cfg(target_os = "android")]
static G_RENDER_DEAD: AtomicBool = AtomicBool::new(false);

/// Where the GL strings are read from, and what has to be torn down afterwards.
#[cfg(target_os = "android")]
enum ProbeTarget {
    /// `EGL_KHR_surfaceless_context` lets the context be current with no surface at all.
    Surfaceless,
    /// Placeholder pbuffer for drivers that refuse a surfaceless current context.
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

/// Makes the context current without a window, so GL can be queried before any surface exists.
///
/// media3 splits the same problem the same way (`GlUtil.isSurfacelessContextExtensionSupported`
/// against `createFocusedPlaceholderEglSurface`). This used to go straight for a pbuffer created
/// from the *window* config, which forced `EGL_PBUFFER_BIT` into that config and made
/// `eglChooseConfig` come back empty on drivers that will not list the window+pbuffer combination.
/// The pbuffer path now picks its own config, and only when surfaceless is unavailable.
#[cfg(target_os = "android")]
unsafe fn bind_probe_target(display: EGLDisplay, ctx: EGLContext) -> Option<ProbeTarget> {
    let extensions = eglQueryString(display, EGL_EXTENSIONS);
    let has_surfaceless = !extensions.is_null()
        && CStr::from_ptr(extensions)
            .to_string_lossy()
            .contains("EGL_KHR_surfaceless_context");
    if has_surfaceless {
        if eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx) == EGL_TRUE {
            return Some(ProbeTarget::Surfaceless);
        }
        // The extension is advertised but the driver refused: fall through to the pbuffer, which
        // is the only remaining way to get a current context.
        egl_failure("eglMakeCurrent(probe/surfaceless)");
    }

    let config_attribs = [
        EGL_RENDERABLE_TYPE,
        EGL_OPENGL_ES2_BIT,
        EGL_SURFACE_TYPE,
        EGL_PBUFFER_BIT,
        EGL_RED_SIZE,
        8,
        EGL_GREEN_SIZE,
        8,
        EGL_BLUE_SIZE,
        8,
        EGL_ALPHA_SIZE,
        8,
        EGL_DEPTH_SIZE,
        0,
        EGL_STENCIL_SIZE,
        0,
        EGL_NONE,
    ];
    let mut config: EGLConfig = std::ptr::null_mut();
    let mut matched: EGLint = 0;
    if eglChooseConfig(display, config_attribs.as_ptr(), &mut config, 1, &mut matched) != EGL_TRUE
        || matched < 1
        || config.is_null()
    {
        egl_failure("eglChooseConfig(probe/pbuffer)");
        return None;
    }

    let surface_attribs = [EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE];
    let surface = eglCreatePbufferSurface(display, config, surface_attribs.as_ptr());
    if surface == EGL_NO_SURFACE {
        egl_failure("eglCreatePbufferSurface(probe)");
        return None;
    }
    if eglMakeCurrent(display, surface, surface, ctx) != EGL_TRUE {
        egl_failure("eglMakeCurrent(probe/pbuffer)");
        eglDestroySurface(display, surface);
        return None;
    }
    Some(ProbeTarget::Pbuffer(surface))
}

/// Reads the GPU identity and the extensions that decide whether zero-copy is safe.
///
/// Runs once, before any window surface exists. Every branch that cannot read GL leaves the verdict
/// at "not safe", so an unverifiable driver keeps the copy path instead of promising a zero-copy
/// pipeline it may not be able to honour.
#[cfg(target_os = "android")]
unsafe fn probe_graphics_environment(display: EGLDisplay, ctx: EGLContext) {
    let egl_ext_ptr = eglQueryString(display, EGL_EXTENSIONS);
    let egl_ext = if !egl_ext_ptr.is_null() {
        CStr::from_ptr(egl_ext_ptr).to_string_lossy().into_owned()
    } else {
        String::new()
    };

    let has_image_base = egl_ext.contains("EGL_KHR_image_base") || egl_ext.contains("EGL_KHR_image");
    let has_android_native = egl_ext.contains("EGL_ANDROID_image_native_buffer");

    let mut renderer_str = String::from("unknown");
    let mut vendor_str = String::from("unknown");
    let mut has_oes_external = false;

    if let Some(target) = bind_probe_target(display, ctx) {
        let proc = eglGetProcAddress(b"glGetString\0".as_ptr().cast());
        if !proc.is_null() {
            let gl_get_string: unsafe extern "C" fn(u32) -> *const c_char = std::mem::transmute(proc);
            let r_ptr = gl_get_string(GL_RENDERER);
            if !r_ptr.is_null() {
                renderer_str = CStr::from_ptr(r_ptr).to_string_lossy().into_owned();
            }
            let v_ptr = gl_get_string(GL_VENDOR);
            if !v_ptr.is_null() {
                vendor_str = CStr::from_ptr(v_ptr).to_string_lossy().into_owned();
            }
            let ext_ptr = gl_get_string(GL_EXTENSIONS);
            if !ext_ptr.is_null() {
                let gl_ext = CStr::from_ptr(ext_ptr).to_string_lossy();
                has_oes_external = gl_ext.contains("GL_OES_EGL_image_external");
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

/// Runs one mpv command and returns its status code, or `None` when mpv could not be reached at all.
fn run_mpv_command(args: &[&str]) -> Option<c_int> {
    with_api(|api| {
        let handle = unsafe { mpv_handle() }.ok()?;
        let owned: Vec<CString> = args.iter().map(|a| CString::new(*a).unwrap_or_default()).collect();
        let mut arg_ptrs: Vec<*mut c_char> = owned.iter().map(|c| c.as_ptr() as *mut c_char).collect();
        arg_ptrs.push(std::ptr::null_mut());
        Some(unsafe { (api.mpv_command)(handle, arg_ptrs.as_mut_ptr()) })
    })
    .ok()
    .flatten()
}

/// Feeds one of mpv's frame counters into the monitor and logs a sustained loss.
///
/// mpv does the deadline arithmetic itself, so this never guesses from how long one draw call took.
/// These properties are diagnostics with no Java consumer: they are answered here and never cross
/// the JNI boundary.
fn record_frame_counter(counter: FrameCounter, total: i64) {
    let now_ms = G_HEALTH_CLOCK.get_or_init(Instant::now).elapsed().as_millis();
    let Ok(mut monitor) = G_FRAME_HEALTH.lock() else { return };
    let Some(report) = monitor.on_counter(counter, total, now_ms) else { return };
    nlog(&format!(
        "HEALTH: 过去 {}s 迟到 {} 帧、解码器丢弃 {} 帧（跟不上音画同步）；显示跳过 {} 帧。累计 迟到={} 解码丢帧={} 显示跳过={}",
        report.window_ms / 1000,
        report.late_frames,
        report.dropped_frames,
        report.skipped_frames,
        monitor.late_frames(),
        monitor.dropped_frames(),
        monitor.skipped_frames()
    ));
}

/// Makes mpv rebuild its video output against whatever render context is current now.
///
/// `video-reload` is the obvious call here and it does not work: freeing the render context makes
/// mpv tear video output down ("Video: no video"), after which that command returns
/// MPV_ERROR_COMMAND because there is no video output left to reload. Re-selecting the video track
/// is what actually re-creates it - verified on device, video out comes back as
/// `VO: [libmpv] ... mediacodec` and frame presentation resumes. The explicit track selection is
/// lost (mpv falls back to its default video track), which is acceptable for a path that only runs
/// after the driver dropped the GL context.
#[allow(dead_code)]
fn rearm_video_output() -> bool {
    run_mpv_command(&["set", "vid", "no"]);
    let restored = run_mpv_command(&["set", "vid", "auto"]);
    if restored != Some(MPV_ERROR_SUCCESS) {
        nlog(&format!("EGL: 重新启用视频轨失败 rc={restored:?}"));
    }
    restored == Some(MPV_ERROR_SUCCESS)
}

#[derive(Clone, Copy, PartialEq, Eq)]
struct SyncHandle(*mut c_void);
unsafe impl Send for SyncHandle {}
unsafe impl Sync for SyncHandle {}

static REAL_GL_FENCE_SYNC: AtomicPtr<c_void> = AtomicPtr::new(std::ptr::null_mut());
static REAL_GL_DELETE_SYNC: AtomicPtr<c_void> = AtomicPtr::new(std::ptr::null_mut());
static ACTIVE_FENCES: Mutex<Vec<SyncHandle>> = Mutex::new(Vec::new());

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
            // 环形滑动窗口：在 vo_libmpv 模式下，mpv 每帧提交 FenceSync 但从不调用 swap_buffers
            // 导致内部从不执行 glDeleteSync（mpv upstream issue #17217）。
            // 在此自动回收超过 2 帧的旧 Fence，促使驱动立即释放底层的 anon_inode:sync_file 内核句柄，
            // 彻底根治播放 15~20 分钟后 FD 耗尽（32768）导致的 status=-24 闪退！
            let del_fn = REAL_GL_DELETE_SYNC.load(Ordering::Relaxed);
            if !del_fn.is_null() {
                let del: unsafe extern "C" fn(*mut c_void) = std::mem::transmute(del_fn);
                while lock.len() > 2 {
                    let old_sync = lock.remove(0);
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
        // 只有当前栅栏仍在 ACTIVE_FENCES 中时，才真正下发 del 调用；
        // 若已被滑动窗口自动回收，说明底层的 glDeleteSync 已经被调用过，避免二次释放（Double Free）！
        if was_tracked {
            let del_fn = REAL_GL_DELETE_SYNC.load(Ordering::Relaxed);
            if !del_fn.is_null() {
                let del: unsafe extern "C" fn(*mut c_void) = std::mem::transmute(del_fn);
                del(sync);
            }
        }
    }
}

/// Deletes fence objects left behind by mpv's renderer.
///
/// Must be called on the render thread while the EGL context is current: GL calls from any other
/// thread have no context and are undefined behaviour. When mpv's render context is freed it
/// deletes the fences it still tracks through [wrapped_gl_delete_sync], so whatever survives that is
/// a leak from the sliding window and is safe to delete here.
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
                    // 顺便预解析 glDeleteSync，保证 wrapped_gl_fence_sync 可立即释放旧栅栏
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

// ---------- EGL 资源所有权 ----------

/// Logs the current EGL error next to the operation that failed.
///
/// Android-only: EGL is linked there, while the host build only exercises the pure logic.
///
/// Every failure path here used to log a bare string, which made "surface creation failed"
/// indistinguishable from "context lost" in a bug report.
#[cfg(target_os = "android")]
fn egl_failure(what: &str) -> EGLint {
    let err = unsafe { eglGetError() };
    nlog(&format!(
        "EGL: {what} 失败 (error={} / 0x{err:X})",
        egl_error_name(err)
    ));
    err
}

/// Fills `out` with the `EGLConfig` attribute list for one candidate.
///
/// Only the attributes the request actually constrains are emitted: every extra attribute is one
/// more way for `eglChooseConfig` to match nothing on a driver that is picky about the exact shape
/// of the list.
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

/// Reads one attribute of a config the driver just returned.
///
/// A driver that refuses `eglGetConfigAttrib` costs a log line, not the pipeline: every value here
/// is diagnostic, and `visual_id` has a documented fallback (0 means "let the window keep its
/// format", which `ANativeWindow_setBuffersGeometry` accepts).
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

/// The configuration the window surface is created from.
#[cfg(target_os = "android")]
struct ChosenConfig {
    config: EGLConfig,
    visual_id: EGLint,
}

/// Walks the candidate list and returns the first configuration the driver accepts.
///
/// Two failures mean "try the next tier" instead of "give up": `eglChooseConfig` returning
/// `EGL_FALSE`, and — the one that used to take the render thread down silently — returning
/// `EGL_TRUE` with zero matches, which is what a driver does when it cannot satisfy the request
/// exactly. The accepted config is then logged with its channel sizes and caveat, because "the
/// picture is black on this one phone" is otherwise unanswerable from a bug report.
#[cfg(target_os = "android")]
unsafe fn choose_egl_config(display: EGLDisplay, prefer_10bit: bool) -> Option<ChosenConfig> {
    // Some drivers report more matches than were requested, so leave room for a few and keep the
    // first; the count is only used to make the log say how large the field was.
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
        if accepted != EGL_TRUE {
            let err = eglGetError();
            nlog(&format!(
                "EGL: eglChooseConfig 被拒绝 (tier={}, error={} / 0x{err:X})",
                request.name,
                egl_error_name(err)
            ));
            continue;
        }
        if matched < 1 || configs[0].is_null() {
            nlog(&format!("EGL: 无匹配配置 (tier={})", request.name));
            continue;
        }

        let config = configs[0];
        let caveat = config_attrib(display, config, EGL_CONFIG_CAVEAT, 0);
        if caveat == EGL_SLOW_CONFIG || caveat == EGL_NON_CONFORMANT_CONFIG {
            // Usable, but worth recording: a slow config is often the first sign that the GPU is
            // software-rendered or that the request fell back further than intended.
            nlog(&format!(
                "EGL: tier={} 带 CAVEAT=0x{caveat:X}（非最优或非规范配置）",
                request.name
            ));
        }
        let visual_id = config_attrib(display, config, EGL_NATIVE_VISUAL_ID, 0);
        nlog(&format!(
            "EGL: 选定配置 tier={} matched={matched} id={} visual_id=0x{visual_id:X} rgba={}{}{}{} depth={}",
            request.name,
            config_attrib(display, config, EGL_CONFIG_ID, 0),
            config_attrib(display, config, EGL_RED_SIZE, 0),
            config_attrib(display, config, EGL_GREEN_SIZE, 0),
            config_attrib(display, config, EGL_BLUE_SIZE, 0),
            config_attrib(display, config, EGL_ALPHA_SIZE, 0),
            config_attrib(display, config, EGL_DEPTH_SIZE, 0),
        ));
        return Some(ChosenConfig { config, visual_id });
    }

    nlog(&format!(
        "EGL: {} 个 EGLConfig 候选全部失败，当前驱动无法提供窗口配置",
        attempts.len()
    ));
    None
}

/// Creates the GL context, negotiating the ES version.
///
/// ES 3 is what mpv's `vo_gpu` needs, so it is tried first. The 2.x attempt is not a working
/// fallback; it exists so the failure is attributable ("this driver has no ES 3") instead of
/// surfacing later as an `mpv_render_context_create` error over a black screen.
///
/// The version that landed is returned with the handle: it decides whether the ES 3 entry points
/// used after binding a surface (`glDrawBuffers`, `glReadBuffer`) may be called at all.
#[cfg(target_os = "android")]
unsafe fn create_egl_context(
    display: EGLDisplay,
    config: EGLConfig,
) -> Option<(EGLContext, i32)> {
    for &version in CONTEXT_CLIENT_VERSIONS.iter() {
        let attributes = [EGL_CONTEXT_CLIENT_VERSION, version, EGL_NONE];
        let context = eglCreateContext(display, config, EGL_NO_CONTEXT, attributes.as_ptr());
        if context != EGL_NO_CONTEXT {
            nlog(&format!("EGL: GLES {version}.x 上下文创建成功"));
            if version < 3 {
                nlog("EGL: 驱动不支持 GLES 3，已回退到 2.x；vo_gpu 需要 GLES 3，视频输出预计会失败");
            }
            return Some((context, version));
        }
        let err = eglGetError();
        nlog(&format!(
            "EGL: GLES {version}.x 上下文创建失败 (error={} / 0x{err:X})",
            egl_error_name(err)
        ));
    }
    None
}

/// Restores the default framebuffer's draw and read target after a window surface became current.
///
/// EGL resets `GL_DRAW_BUFFER` and `GL_READ_BUFFER` to `GL_NONE` when a context is made current
/// without a surface, and making a window surface current again does not restore them: colour
/// output is disabled, every draw goes nowhere and the window stays black while audio keeps
/// playing. That is exactly the transition this pipeline makes on `detachSurface` followed by
/// `attachSurface`. media3 restores the same state in `GlUtil.focusRenderTarget` for framebuffer 0
/// (androidx/media#2982), and the entry points only exist on GLES 3 - an ES 2 context cannot have
/// lost the state this way, so the call is skipped there.
#[cfg(target_os = "android")]
unsafe fn bind_default_framebuffer(context_major: i32) {
    const GL_FRAMEBUFFER: u32 = 0x8D40;
    const GL_BACK: u32 = 0x0405;

    // Binding 0 first is mandatory: `GL_BACK` is only legal for the default framebuffer, and mpv
    // may have left one of its own FBOs bound.
    let bind_framebuffer = eglGetProcAddress(b"glBindFramebuffer\0".as_ptr().cast());
    if !bind_framebuffer.is_null() {
        let bind: unsafe extern "C" fn(u32, u32) = std::mem::transmute(bind_framebuffer);
        bind(GL_FRAMEBUFFER, 0);
    }
    if context_major < 3 {
        return;
    }

    let draw_buffers = eglGetProcAddress(b"glDrawBuffers\0".as_ptr().cast());
    if !draw_buffers.is_null() {
        let set_draw_buffers: unsafe extern "C" fn(i32, *const u32) = std::mem::transmute(draw_buffers);
        let back = [GL_BACK];
        set_draw_buffers(1, back.as_ptr());
    }
    let read_buffer = eglGetProcAddress(b"glReadBuffer\0".as_ptr().cast());
    if !read_buffer.is_null() {
        let set_read_buffer: unsafe extern "C" fn(u32) = std::mem::transmute(read_buffer);
        set_read_buffer(GL_BACK);
    }
}

/// Owns the EGL window surface together with the native window it was created from.
///
/// Teardown order is fixed and was previously duplicated in three places: unbind the context,
/// destroy the surface, then drop the native window reference. It lives here once, and `Drop`
/// releases the window reference even on the paths that never produced a surface.
#[cfg(target_os = "android")]
struct SurfaceSlot {
    display: EGLDisplay,
    config: EGLConfig,
    window: *mut ANativeWindow,
    surface: EGLSurface,
    visual_id: EGLint,
}

#[cfg(target_os = "android")]
impl SurfaceSlot {
    unsafe fn new(display: EGLDisplay, config: EGLConfig) -> Self {
        Self {
            display,
            config,
            window: std::ptr::null_mut(),
            surface: EGL_NO_SURFACE,
            visual_id: 0,
        }
    }

    fn surface(&self) -> EGLSurface {
        self.surface
    }

    fn is_ready(&self) -> bool {
        !self.surface.is_null() && self.surface != EGL_NO_SURFACE
    }

    /// Binds a new native window and makes its surface current.
    ///
    /// Any previous surface is torn down first. On failure the slot is left empty and the window
    /// reference released, so a broken attach can neither leak nor leave a half-bound state.
    unsafe fn attach(
        &mut self,
        window: *mut ANativeWindow,
        ctx: EGLContext,
        context_major: i32,
        visual_id: EGLint,
    ) -> Result<(), EGLint> {
        self.detach();
        if window.is_null() {
            return Ok(());
        }
        self.window = window;
        self.visual_id = visual_id;

        ANativeWindow_setBuffersGeometry(window, 0, 0, visual_id);

        match self.rebuild_surface(ctx, context_major) {
            Ok(()) => Ok(()),
            Err(err) => {
                self.detach();
                Err(err)
            }
        }
    }

    /// Recreates the window surface when the native window was resized underneath it.
    ///
    /// Rotation, split screen and PiP resize the window without destroying it, and an existing
    /// EGL surface keeps presenting buffers of the old size: `eglQuerySurface` still reports the
    /// previous geometry, `setBuffersGeometry` does not resize the surface either. A new EGL
    /// surface created from the same window picks the new size up, so the frame is rendered at the
    /// size it is actually shown at instead of being rendered huge and scaled down by the
    /// compositor. Returns true when the surface was rebuilt.
    ///
    /// This is the same push the caller makes in media3 (`setOutputSurfaceInfo` → compare
    /// `SurfaceInfo.width/height` → rebuild the output EGLSurface): the resize is reported from
    /// outside, never polled, and only acted on when the size actually differs.
    unsafe fn sync_geometry(&mut self, ctx: EGLContext, context_major: i32) -> bool {
        if self.window.is_null() || !self.is_ready() {
            return false;
        }
        let win_w = ANativeWindow_getWidth(self.window);
        let win_h = ANativeWindow_getHeight(self.window);
        let mut surf_w: EGLint = 0;
        let mut surf_h: EGLint = 0;
        eglQuerySurface(self.display, self.surface, EGL_WIDTH, &mut surf_w);
        eglQuerySurface(self.display, self.surface, EGL_HEIGHT, &mut surf_h);
        if win_w <= 0 || win_h <= 0 || (win_w == surf_w && win_h == surf_h) {
            return false;
        }
        nlog(&format!(
            "EGL: 窗口尺寸 {win_w}x{win_h} 与 surface {surf_w}x{surf_h} 不一致，重建 surface"
        ));
        ANativeWindow_setBuffersGeometry(self.window, 0, 0, self.visual_id);
        self.drop_surface();
        let mut rebuilt = self.rebuild_surface(ctx, context_major).is_ok();
        if !rebuilt {
            // The old surface is already gone at this point, so a failure here means no picture at
            // all until the next attach. One immediate retry costs nothing and covers the transient
            // EGL_BAD_ALLOC a resize can provoke while the compositor is still releasing the
            // previous buffer.
            nlog("EGL: 尺寸变化后重建 surface 失败，立即重试一次");
            rebuilt = self.rebuild_surface(ctx, context_major).is_ok();
        }
        if rebuilt {
            // Read back rather than trusting the request: this is the geometry mpv asks for on
            // every frame, so it is also the answer to "what size is the picture actually rendered
            // at", and a driver that ignores the resize shows up here instead of as a blurry PiP.
            let mut new_w: EGLint = 0;
            let mut new_h: EGLint = 0;
            eglQuerySurface(self.display, self.surface, EGL_WIDTH, &mut new_w);
            eglQuerySurface(self.display, self.surface, EGL_HEIGHT, &mut new_h);
            nlog(&format!("EGL: surface 已按 {new_w}x{new_h} 重建"));
        }
        rebuilt
    }

    /// Destroys the window surface but keeps the `ANativeWindow` reference.
    ///
    /// Context recovery needs exactly this split: the window survives a driver reset, the EGL
    /// surface does not, and releasing the window here would force a re-attach from Java.
    ///
    /// The order is the one EGL requires and media3 follows in `GlUtil.destroyEglSurface` /
    /// `destroyEglContext`: unbind first, then destroy the surface, and only then - in
    /// `EglCore::destroy` - the context. Destroying a surface that is still current works on most
    /// drivers and leaks on the ones it does not.
    unsafe fn drop_surface(&mut self) {
        if self.is_ready() {
            eglMakeCurrent(self.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if eglDestroySurface(self.display, self.surface) != EGL_TRUE {
                egl_failure("eglDestroySurface");
            }
            self.surface = EGL_NO_SURFACE;
        }
    }

    /// Creates a window surface from the retained window and makes it current.
    unsafe fn rebuild_surface(&mut self, ctx: EGLContext, context_major: i32) -> Result<(), EGLint> {
        if self.window.is_null() {
            return Err(EGL_BAD_SURFACE);
        }
        // An explicit EGL_NONE terminator rather than a null pointer: media3 always passes one
        // (`EGL_WINDOW_SURFACE_ATTRIBUTES_NONE`) and vendors that inspect the list unconditionally
        // are exactly the ones this pipeline tends to break on.
        let surface_attribs = [EGL_NONE];
        let surface = eglCreateWindowSurface(
            self.display,
            self.config,
            self.window,
            surface_attribs.as_ptr(),
        );
        if surface == EGL_NO_SURFACE {
            let err = egl_failure("eglCreateWindowSurface");
            if err == EGL_BAD_ALLOC {
                // The driver could not hand out a buffer; a later attach usually succeeds, so the
                // failure is recorded as retryable instead of fatal.
                nlog("EGL: 驱动暂时无法分配窗口缓冲 (EGL_BAD_ALLOC)，下一次 attach 可重试");
            }
            return Err(err);
        }
        self.surface = surface;

        if eglMakeCurrent(self.display, surface, surface, ctx) != EGL_TRUE {
            let err = egl_failure("eglMakeCurrent");
            self.drop_surface();
            return Err(err);
        }
        bind_default_framebuffer(context_major);

        // Without vsync the render thread free-spins and tears.
        if eglSwapInterval(self.display, 1) != EGL_TRUE {
            // Not fatal: drivers that reject a non-zero interval still present in FIFO order.
            egl_failure("eglSwapInterval");
        }
        Ok(())
    }

    /// Unbinds and destroys the surface, then releases the window reference.
    unsafe fn detach(&mut self) {
        self.drop_surface();
        if !self.window.is_null() {
            ANativeWindow_release(self.window);
            self.window = std::ptr::null_mut();
        }
    }
}

/// The long-lived EGL objects owned by the render thread.
///
/// Display and config outlive a context, so a lost context costs one `eglCreateContext` instead of a
/// full EGL teardown, and the window reference taken in attach survives the rebuild untouched.
#[cfg(target_os = "android")]
struct EglCore {
    display: EGLDisplay,
    config: EGLConfig,
    context: EGLContext,
    /// GLES major version of `context`; decides whether the ES 3 entry points may be called.
    context_major: i32,
    visual_id: EGLint,
}

#[cfg(target_os = "android")]
impl EglCore {
    /// Replaces a dead context with a fresh one on the same display and config.
    ///
    /// The version negotiation runs again rather than assuming ES 3: after a driver reset the
    /// surviving config is the one that was already accepted, but nothing guarantees the context
    /// that failed is the one that can be created again.
    unsafe fn recreate_context(&mut self) -> bool {
        if self.context != EGL_NO_CONTEXT {
            // Expected to fail on a lost context; the handle is unusable either way.
            eglDestroyContext(self.display, self.context);
            self.context = EGL_NO_CONTEXT;
        }
        let Some((context, major)) = create_egl_context(self.display, self.config) else {
            return false;
        };
        self.context = context;
        self.context_major = major;
        nlog("EGL: 上下文重建成功");
        true
    }

    /// Releases the context and the display in the order EGL requires.
    ///
    /// `eglReleaseThread` sits between them for the reason media3 calls it in
    /// `GlUtil.terminate()`: the display keeps the thread's current-context binding alive, so
    /// terminating first leaves the driver holding state for a thread that is about to exit. The
    /// surface is already gone by the time this runs - `SurfaceSlot::detach` unbinds and destroys
    /// it first, which is the make-current-none → destroy-surface → destroy-context order.
    unsafe fn destroy(&mut self) {
        if self.context != EGL_NO_CONTEXT && eglDestroyContext(self.display, self.context) != EGL_TRUE {
            egl_failure("eglDestroyContext");
        }
        self.context = EGL_NO_CONTEXT;
        if eglReleaseThread() != EGL_TRUE {
            egl_failure("eglReleaseThread");
        }
        if eglTerminate(self.display) != EGL_TRUE {
            egl_failure("eglTerminate");
        }
    }
}

#[cfg(target_os = "android")]
impl Drop for SurfaceSlot {
    fn drop(&mut self) {
        unsafe { self.detach() };
    }
}

/// Creates mpv's render context on first use and hands it the GL context.
///
/// Two call sites (first attach, deferred frame) shared a 30 line parameter table that had already
/// drifted apart in wording; the table now lives once.
unsafe fn ensure_render_context(slot: &mut *mut c_void) {
    if !slot.is_null() {
        return;
    }
    let Ok(handle) = mpv_handle() else { return };
    with_api_logged("mpv_render_context_create", |api| {
        let init_params = MpvOpenGLInitParams {
            get_proc_address: gl_proc_address,
            get_proc_address_ctx: std::ptr::null_mut(),
        };
        let api_type = CString::new("opengl").unwrap_or_default();
        let mut params = [
            MpvRenderParam { param_type: MPV_RENDER_PARAM_API_TYPE, data: api_type.as_ptr() as *mut c_void },
            MpvRenderParam { param_type: MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, data: &init_params as *const _ as *mut c_void },
            MpvRenderParam { param_type: 0, data: std::ptr::null_mut() },
        ];
        let rc = (api.mpv_render_context_create)(slot, handle, params.as_mut_ptr());
        if rc != MPV_ERROR_SUCCESS {
            nlog(&format!("EGL: mpv_render_context_create 失败 rc={rc}"));
            return;
        }
        (api.mpv_render_context_set_update_callback)(
            *slot,
            Some(mpv_update_callback),
            std::ptr::null_mut(),
        );
        nlog("EGL: mpv_render_context 已挂载到渲染线程");
    });
}

// 渲染线程主循环（纯原生 EGL 闭环）
fn native_render_loop(receiver: Receiver<RenderCommand>) {
    nlog("native_render_loop: 渲染线程已就绪");

    #[cfg(target_os = "android")]
    unsafe {
        let owner_tid = logical_thread_id();
        if let Ok(mut affinity) = G_AFFINITY.lock() {
            if !affinity.claim(owner_tid) {
                nlog("EGL: 上下文已归其他线程所有，当前渲染线程的声明被拒绝");
            }
        }

        let display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if display == EGL_NO_DISPLAY {
            nlog("EGL: eglGetDisplay 失败");
            return;
        }

        let mut major = 0;
        let mut minor = 0;
        if eglInitialize(display, &mut major, &mut minor) != EGL_TRUE {
            egl_failure("eglInitialize");
            return;
        }

        // 10-bit stays off until the HDR output path exists, see `logic::egl_config_attempts`.
        let Some(chosen) = choose_egl_config(display, false) else {
            nlog("EGL: 无可用 EGLConfig，渲染线程退出（画面将全黑）");
            return;
        };
        let Some((egl_context, context_major)) = create_egl_context(display, chosen.config) else {
            nlog("EGL: 无法创建 GL 上下文，渲染线程退出（画面将全黑）");
            return;
        };

        nlog("EGL: 常驻 EGLContext 创建成功");
        probe_graphics_environment(display, egl_context);

        // Display, config, context and visual id travel together so the recovery path can rebuild a
        // context without redoing the whole EGL handshake.
        let mut core = EglCore {
            display,
            config: chosen.config,
            context: egl_context,
            context_major,
            visual_id: chosen.visual_id,
        };

        // Owns the window + surface pair; teardown order is enforced in one place.
        let mut slot = SurfaceSlot::new(display, chosen.config);
        let mut mpv_render_ctx: *mut c_void = std::ptr::null_mut();


        while let Ok(cmd) = receiver.recv() {
            match cmd {
                RenderCommand::AttachSurface(NativeWindowPtr(window), ack) => {
                    nlog("EGL: 收到 AttachSurface 命令");
                    // Must run before attach: attach makes `core.context` current, and a dead or
                    // missing context would make it fail, leaving the new video black.
                    let pipeline_ok = revive_dead_pipeline(&mut core, &mut mpv_render_ctx);
                    match slot.attach(window, core.context, core.context_major, core.visual_id) {
                        Ok(()) => {
                            // Establish the render context BEFORE acknowledging the caller.
                            //
                            // The Kotlin side usually follows surfaceCreated with a loadfile. If
                            // that load reaches libmpv first, video out initialisation finds no
                            // render context, logs "No render context set", and permanently gives
                            // up on video for that file: the audio plays over a black screen. The
                            // acknowledgement is what unblocks the load, so it has to come after.
                            if slot.is_ready() {
                                ensure_render_context(&mut mpv_render_ctx);
                            }
                            nlog(&format!(
                                "EGL: attach 完成 ready={} ctx_null={}",
                                slot.is_ready(),
                                mpv_render_ctx.is_null()
                            ));
                            let _ = ack.send(());

                            // First paint is the expensive part (hwdec init, video-out setup) and the
                            // calling load only needs the render context to exist, so painting runs
                            // after the acknowledgement instead of inside surfaceCreated's wait.
                            if slot.is_ready() {
                                draw_and_recover(&mut core, &mut slot, &mut mpv_render_ctx, true);
                            }
                        }
                        Err(err) => {
                            // The caller is blocked on this acknowledgement; release it even when
                            // the attach failed, or surfaceCreated never returns.
                            nlog(&format!(
                                "EGL: attach 失败 (error=0x{err:X}) pipeline_ok={pipeline_ok}"
                            ));
                            let _ = ack.send(());
                        }
                    }
                }
                RenderCommand::DetachSurface => {
                    // The caller does not wait for this. It used to, and that put a blocking wait on
                    // the UI thread inside surfaceDestroyed, for a guarantee this side already has:
                    // the window reference taken in attachSurface keeps the buffer queue alive past
                    // the callback returning. Ordering is still safe because attach and detach
                    // travel the same channel, so a later attach cannot overtake this.
                    slot.detach();
                    nlog("EGL: Surface 已解绑 (EGLContext 与 render context 常驻)");
                }
                RenderCommand::Redraw => {
                    // The only path that repaints while playback is paused, so it is worth a log:
                    // without it a stale buffer looks like a rendering bug with no trace.
                    nlog("EGL: 收到 Redraw 命令");
                    if slot.is_ready() && !mpv_render_ctx.is_null() {
                        slot.sync_geometry(core.context, core.context_major);
                        draw_and_recover(&mut core, &mut slot, &mut mpv_render_ctx, true);
                    }
                }
                RenderCommand::NotifyFrame => {
                    G_FRAME_GATE.release();
                    if slot.is_ready() {
                        ensure_render_context(&mut mpv_render_ctx);
                        draw_and_recover(&mut core, &mut slot, &mut mpv_render_ctx, false);
                    }
                }
                RenderCommand::DestroyContext(ack) => {
                    nlog("EGL: 收到 DestroyContext 请求，开始销毁 mpv_render_context");
                    if !mpv_render_ctx.is_null() {
                        with_api_logged("mpv_render_context_free", |api| (api.mpv_render_context_free)(mpv_render_ctx));
                        mpv_render_ctx = std::ptr::null_mut();
                        nlog("EGL: mpv_render_context 已在渲染线程安全释放");
                    }
                    // Fence objects belong to the GL context that is current on this thread only.
                    // mpv releases the ones it still tracks, so anything left is a leak; deleting it
                    // from the caller thread would be a GL call without a current context.
                    drain_active_fences();
                    let _ = ack.send(());
                }
                RenderCommand::Shutdown => {
                    nlog("EGL: 渲染线程正在退出并清理资源");
                    if !mpv_render_ctx.is_null() {
                        with_api_logged("mpv_render_context_free", |api| (api.mpv_render_context_free)(mpv_render_ctx));
                        // No assignment: the loop breaks right after, so a nulled local would never
                        // be read. The render context is owned by the thread that is exiting.
                    }
                    // Tear the surface down while the context is still current.
                    slot.detach();
                    core.destroy();
                    // Release ownership last: anything still holding the id would otherwise look
                    // like a live render thread.
                    if let Ok(mut affinity) = G_AFFINITY.lock() {
                        affinity.release(owner_tid);
                    }
                    break;
                }
            }
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        while let Ok(cmd) = receiver.recv() {
            if let RenderCommand::Shutdown = cmd { break; }
        }
    }
}

/// What one attempt at presenting a frame achieved.
#[cfg(target_os = "android")]
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
enum DrawOutcome {
    /// The frame was swapped to the window.
    Presented,
    /// Nothing to present, or a swap failure the driver recovered from on its own.
    Skipped,
    /// The driver dropped the EGL context; every later call on it is void, so the pipeline has to
    /// be rebuilt rather than retried.
    ContextLost,
}

// 原生帧绘制（在渲染线程内部执行，0 JNI 开销）
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
        if !force {
            let flags = (api.mpv_render_context_update)(ctx);
            if flags & MPV_RENDER_UPDATE_FRAME == 0 {
                let starved = G_FRAMES_STARVED.fetch_add(1, Ordering::Relaxed) + 1;
                // A rising starved count with a frozen presented count means mpv stopped producing
                // frames, not that the render loop stopped asking for them.
                if starved % 120 == 0 {
                    nlog(&format!(
                        "RENDER: presented={} starved={}",
                        G_FRAMES_DRAWN.load(Ordering::Relaxed),
                        starved
                    ));
                }
                return DrawOutcome::Skipped;
            }
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
        let mut params = [
            MpvRenderParam { param_type: MPV_RENDER_PARAM_OPENGL_FBO, data: &fbo as *const _ as *mut c_void },
            MpvRenderParam { param_type: MPV_RENDER_PARAM_FLIP_Y, data: &flip as *const _ as *mut c_void },
            MpvRenderParam { param_type: 0, data: std::ptr::null_mut() },
        ];

        let render_start = Instant::now();
        (api.mpv_render_context_render)(ctx, params.as_mut_ptr());
        let swap_rc = eglSwapBuffers(display, surface);
        // Only a frame that actually reached the window is a flip. mpv's `flip_page` blocks on this
        // count for vsync pacing (`vo_libmpv` waits while `expected_flip_count > flip_count`), so
        // reporting before the swap both defeats that wait and, when the swap fails, books a present
        // that never happened. `mpv_render_context_report_swap` is documented as "the frame was
        // flipped".
        if swap_rc == EGL_TRUE {
            (api.mpv_render_context_report_swap)(ctx);
        }
        let elapsed = render_start.elapsed();
        let presented = G_FRAMES_DRAWN.fetch_add(1, Ordering::Relaxed) + 1;
        if presented <= 3 || presented % 300 == 0 {
            nlog(&format!(
                "RENDER: presented={} elapsed={}ms starved={}",
                presented,
                elapsed.as_millis(),
                G_FRAMES_STARVED.load(Ordering::Relaxed)
            ));
        }

        if swap_rc != EGL_TRUE {
            let err = eglGetError();
            nlog(&format!("WATCHDOG: eglSwapBuffers 失败 rc={swap_rc} error=0x{err:X}"));
            // A lost context is not a decoding problem, so switching the decoder cannot help; the
            // caller has to rebuild the pipeline instead.
            return if err == EGL_CONTEXT_LOST {
                DrawOutcome::ContextLost
            } else {
                DrawOutcome::Skipped
            };
        }

        DrawOutcome::Presented
    })
    .unwrap_or(DrawOutcome::Skipped)
}

/// Forgets fence handles whose context is gone.
///
/// The sync objects die together with the EGL context, so handing their dangling handles to
/// `glDeleteSync` would be a use-after-free on the driver side. Dropping them without a delete is
/// the only correct option, and it also keeps [`drain_active_fences`] from firing on a dead context.
#[cfg(target_os = "android")]
unsafe fn forget_active_fences() {
    if let Ok(mut fences) = ACTIVE_FENCES.lock() {
        if !fences.is_empty() {
            nlog(&format!("EGL: 上下文已丢失，丢弃 {} 个 fence 句柄", fences.len()));
            fences.clear();
        }
    }
}

/// Gives a dead pipeline one more chance when a new surface arrives.
///
/// The EGL context belongs to the render thread, not to the surface, so a spent budget would keep
/// every later video black until the process restarts - and `EGL_CONTEXT_LOST` is usually the system
/// reclaiming GPU resources while the app sat in the background, which a fresh context fixes. The
/// attach of a new surface is the only meaningful retry point, because it is the only moment a fresh
/// context can be paired with a surface again; nothing else in the loop can trigger it.
///
/// The rebuild budget is reset together with the context: those two attempts belong to the context
/// that died, and a user reopening the player has started a new session. A driver that keeps dropping
/// contexts therefore costs a bounded number of attempts per entry, never a loop.
#[cfg(target_os = "android")]
unsafe fn revive_dead_pipeline(core: &mut EglCore, mpv_render_ctx: &mut *mut c_void) -> bool {
    // Fast path: a live context is the normal case, so this must cost one atomic load.
    if !G_RENDER_DEAD.load(Ordering::Acquire) && core.context != EGL_NO_CONTEXT {
        return true;
    }
    nlog("EGL: 新 surface 到达，尝试为已停用的管线重建上下文");

    // mpv's render context is bound to the dead context; release it before that context goes away.
    if !mpv_render_ctx.is_null() {
        with_api_logged("mpv_render_context_free", |api| (api.mpv_render_context_free)(*mpv_render_ctx));
        *mpv_render_ctx = std::ptr::null_mut();
    }
    forget_active_fences();

    if !core.recreate_context() {
        // Stays dead: the flag is left set so the loop keeps skipping GL instead of retrying.
        return false;
    }
    if let Ok(mut recovery) = G_RECOVERY.lock() {
        *recovery = ContextRecovery::with_defaults();
    }
    G_RENDER_DEAD.store(false, Ordering::Release);

    // The video output gave up on this file when it found no render context, so it has to be
    // re-armed against the fresh one; `video-reload` cannot do that (see `rearm_video_output`).
    rearm_video_output();
    nlog("EGL: 管线已恢复，渲染重新启用");
    true
}

/// Rebuilds the EGL pipeline after the driver dropped the context.
///
/// Runs on the render thread with the old context already unusable. Returns true when a frame can be
/// drawn again. Steps are ordered so that mpv never holds a render context bound to a destroyed GL
/// context: release mpv's context first, then the surface, then the context itself, and only then
/// build the replacements in the opposite order.
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
        // Latched once: without the flag every subsequent frame would relog this and re-enter mpv.
        if !G_RENDER_DEAD.swap(true, Ordering::AcqRel) {
            nlog("EGL: 重建预算已用尽，停止重建（驱动持续丢失上下文），渲染已停用");
        }
        return false;
    }

    // mpv owns GL objects created in the lost context; it must be told before that context goes
    // away. Best effort: the objects are already invalid, only the bookkeeping matters.
    if !mpv_render_ctx.is_null() {
        with_api_logged("mpv_render_context_free", |api| (api.mpv_render_context_free)(*mpv_render_ctx));
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

    // vo_libmpv only initialises video out while a render context exists (see the attach path), and
    // freeing the context tore the current one down, so video must be re-armed against the new one.
    rearm_video_output();
    nlog("EGL: 上下文管线已重建，恢复渲染");
    true
}

/// Draws a frame and, when the driver reports the context is gone, rebuilds the pipeline and draws
/// once more.
///
/// The retry is what keeps a driver reset invisible: without it the surface keeps a dead context
/// current and every following frame fails silently, which is a black screen until the app restarts.
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
    // The context is gone for good; drawing would call GL on a dead context once per frame.
    if G_RENDER_DEAD.load(Ordering::Acquire) {
        return;
    }
    let outcome = draw_frame_native(
        core.display,
        slot.surface(),
        *mpv_render_ctx,
        force,
    );
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
    // Hand the slot back whenever the notification could not be queued: holding it would close the
    // gate forever and the next playback session would render one frame and then freeze.
    if !delivered {
        G_FRAME_GATE.release();
    }
}

/// Clones the render command sender out of the global slot.
///
/// Every caller needs to queue a command and then wait for its acknowledgement, and that wait can
/// last hundreds of milliseconds. Doing it while holding `RENDER_THREAD` would block
/// `mpv_update_callback`, which needs the same lock on mpv's thread, so the lock is only held long
/// enough to clone the sender.
fn render_sender() -> Option<Sender<RenderCommand>> {
    let lock = RENDER_THREAD.get()?;
    let guard = lock.lock().ok()?;
    guard.as_ref().map(|handle| handle.sender.clone())
}

fn ensure_render_thread() {
    let lock = RENDER_THREAD.get_or_init(|| Mutex::new(None));
    if let Ok(mut guard) = lock.lock() {
        if guard.is_none() {
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

// ---------- Kotlin 回调类与事件泵 ----------

const GYRO_NATIVE_CLASS: &str = "dev/gyro/engine/GyroNative";
static G_CLASS: OnceLock<GlobalRef> = OnceLock::new();

fn cache_class(env: &mut JNIEnv) {
    if G_CLASS.get().is_some() {
        return;
    }
    if let Ok(class) = env.find_class(GYRO_NATIVE_CLASS) {
        if let Ok(global) = env.new_global_ref(class) {
            let _ = G_CLASS.set(global);
        }
    }
}

fn call_static(env: &mut JNIEnv, name: &str, sig: &str, args: &[JValue]) {
    let class = match G_CLASS.get() {
        Some(global) => unsafe { JClass::from_raw(global.as_obj().as_raw().cast()) },
        None => match env.find_class(GYRO_NATIVE_CLASS) {
            Ok(c) => c,
            Err(_) => return,
        },
    };
    let _ = env.call_static_method(class, name, sig, args);
}

#[repr(C)]
struct MpvEvent {
    event_id: c_int,
    error: c_int,
    reply_userdata: u64,
    data: *mut c_void,
}

#[repr(C)]
struct MpvEventProperty {
    name: *const c_char,
    format: c_int,
    data: *mut c_void,
}

#[repr(C)]
struct MpvEventLogMessage {
    prefix: *const c_char,
    level: *const c_char,
    text: *const c_char,
    log_level: c_int,
}

fn dispatch_property(env: &mut JNIEnv, name: &str, format: c_int, data: *mut c_void) {
    // The frame counters are consumed here; Java has no use for them.
    if let Some(counter) = FrameCounter::from_property(name) {
        if format == MPV_FORMAT_INT64 && !data.is_null() {
            let total = unsafe { *(data as *const i64) };
            record_frame_counter(counter, total);
        }
        return;
    }

    let Ok(jname) = env.new_string(name) else { return };
    if data.is_null() {
        call_static(env, "onPropertyNone", "(Ljava/lang/String;)V", &[JValue::Object(&jname)]);
        return;
    }

    unsafe {
        match format {
            MPV_FORMAT_FLAG => {
                let v = *(data as *const c_int) != 0;
                call_static(env, "onPropertyFlag", "(Ljava/lang/String;Z)V", &[JValue::Object(&jname), JValue::Bool(v as u8)]);
            }
            MPV_FORMAT_INT64 => {
                let v = *(data as *const i64);
                call_static(env, "onPropertyLong", "(Ljava/lang/String;J)V", &[JValue::Object(&jname), JValue::Long(v)]);
            }
            MPV_FORMAT_DOUBLE => {
                let v = *(data as *const f64);
                call_static(env, "onPropertyDouble", "(Ljava/lang/String;D)V", &[JValue::Object(&jname), JValue::Double(v)]);
            }
            MPV_FORMAT_STRING => {
                let str_ptr = *(data as *const *const c_char);
                if !str_ptr.is_null() {
                    let s = CStr::from_ptr(str_ptr).to_string_lossy();
                    if let Ok(jv) = env.new_string(s.as_ref()) {
                        call_static(env, "onPropertyString", "(Ljava/lang/String;Ljava/lang/String;)V", &[JValue::Object(&jname), JValue::Object(&jv)]);
                    }
                }
            }
            _ => {
                call_static(env, "onPropertyNone", "(Ljava/lang/String;)V", &[JValue::Object(&jname)]);
            }
        }
    }
}

static EVENT_THREAD: Mutex<Option<JoinHandle<()>>> = Mutex::new(None);

unsafe fn safe_terminate_mpv(api: &MpvApi, handle: *mut c_void) {
    if handle.is_null() { return; }
    nlog("safe_terminate_mpv: 开始安全释放 mpv 实例");

    // 1. 停止并等待事件泵退出
    HANDLE_VALID.store(false, Ordering::Release);
    G_EVENT_THREAD_EXIT.store(true, Ordering::Release);
    (api.mpv_wakeup)(handle);

    if let Ok(mut lock) = EVENT_THREAD.lock() {
        if let Some(jh) = lock.take() {
            let _ = jh.join();
            nlog("safe_terminate_mpv: gyro-event 线程已安全 join 退出");
        }
    }

    // 2. 必须在 mpv_destroy 前释放 render context
    //    The wait happens outside the global lock, and a timeout is reported instead of being
    //    silently swallowed: proceeding to mpv_terminate_destroy while the render thread might still
    //    be inside mpv_render_context_render is the classic use-after-free shape.
    if let Some(sender) = render_sender() {
        let outcome = request_ack(
            &sender,
            RenderCommand::DestroyContext,
            std::time::Duration::from_secs(2),
        );
        if outcome != AckOutcome::Acknowledged {
            nlog(&format!(
                "safe_terminate_mpv: 渲染线程未在 2s 内确认释放 render context ({outcome:?})，可能仍在渲染"
            ));
        }
    }

    // Fence cleanup already happened inside the DestroyContext round-trip above, on the render
    // thread where the GL context is current. Dropping the handles here only clears stale slots.
    if let Ok(mut fences) = ACTIVE_FENCES.lock() {
        fences.clear();
    }

    // 3. 安全销毁 mpv 句柄
    (api.mpv_terminate_destroy)(handle);
    nlog("safe_terminate_mpv: mpv_terminate_destroy 顺利完成");
}

fn event_thread() {
    nlog("event_thread: 启动");
    let VmPtr(vm_ptr) = match G_VM.get() {
        Some(p) => p,
        None => return,
    };
    let Ok(vm) = (unsafe { JavaVM::from_raw(vm_ptr.cast()) }) else { return };
    let Ok(mut guard) = vm.attach_current_thread() else { return };
    let env: &mut JNIEnv = &mut guard;

    let wait_event = match with_api(|api| api.mpv_wait_event) {
        Ok(f) => f,
        Err(_) => return,
    };

    loop {
        if G_EVENT_THREAD_EXIT.load(Ordering::Acquire) { break; }
        let handle = match unsafe { mpv_handle() } {
            Ok(h) => h,
            Err(_) => break,
        };
        if !HANDLE_VALID.load(Ordering::Acquire) { break; }

        let event = unsafe { wait_event(handle, 0.5) };
        if G_EVENT_THREAD_EXIT.load(Ordering::Acquire) { break; }
        if event.is_null() { continue; }

        unsafe {
            let ev = event as *const MpvEvent;
            let event_id = (*ev).event_id;
            match event_id {
                MPV_EVENT_PROPERTY_CHANGE => {
                    let prop = (*ev).data as *const MpvEventProperty;
                    if prop.is_null() { continue; }
                    let name_ptr = (*prop).name;
                    let format = (*prop).format;
                    let data = (*prop).data;
                    if name_ptr.is_null() { continue; }
                    if let Ok(name) = CStr::from_ptr(name_ptr).to_str() {
                        dispatch_property(env, name, format, data);
                    }
                }
                MPV_EVENT_LOG_MESSAGE => {
                    let log = (*ev).data as *const MpvEventLogMessage;
                    if !log.is_null() && !(*log).text.is_null() {
                        let prefix = if (*log).prefix.is_null() { "" } else { CStr::from_ptr((*log).prefix).to_str().unwrap_or("") };
                        let text = CStr::from_ptr((*log).text).to_string_lossy();
                        nlog(&format!("[mpv/{prefix}] {}", text.trim_end()));
                    }
                }
                MPV_EVENT_SHUTDOWN => {
                    nlog("event_thread: 收到 MPV_EVENT_SHUTDOWN 事件");
                    HANDLE_VALID.store(false, Ordering::Release);
                    call_static(env, "onEvent", "(I)V", &[JValue::Int(MPV_EVENT_SHUTDOWN)]);
                    break;
                }
                MPV_EVENT_FILE_LOADED => {
                    nlog("event_thread: 收到 MPV_EVENT_FILE_LOADED 事件");
                    call_static(env, "onEvent", "(I)V", &[JValue::Int(event_id)]);
                }
                MPV_EVENT_END_FILE | MPV_EVENT_PLAYBACK_RESTART => {
                    nlog(&format!("event_thread: 收到事件 event_id={event_id}"));
                    call_static(env, "onEvent", "(I)V", &[JValue::Int(event_id)]);
                }
                _ => {}
            }
        }
    }
    nlog("event_thread: 已安全退出");
}

// ---------- JNI 接口导出 (dev.gyro.engine.GyroNative) ----------

#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: *mut JavaVM, _reserved: *mut c_void) -> jint {
    if !vm.is_null() {
        let _ = G_VM.set(VmPtr(vm));
        if let Ok(vm_ref) = unsafe { JavaVM::from_raw(vm as *mut jni::sys::JavaVM) } {
            if let Ok(mut env) = vm_ref.get_env() {
                cache_class(&mut env);
            }
        }
    }
    JNI_VERSION_1_6
}

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_create(
    mut env: JNIEnv,
    _class: JClass,
    appctx: jobject,
) {
    cache_class(&mut env);
    ensure_render_thread();
    // A slot left over from a previous session must not survive into the new one, otherwise the
    // update callback would never queue another frame and the next playback would freeze.
    G_FRAME_GATE.release();

    let global_ctx_raw = if !appctx.is_null() {
        if G_APP_CTX.get().is_none() {
            let obj = unsafe { JObject::from_raw(appctx) };
            if let Ok(g) = env.new_global_ref(&obj) {
                let _ = G_APP_CTX.set(g);
            }
        }
        G_APP_CTX.get().map(|g| g.as_obj().as_raw().cast::<c_void>())
    } else {
        None
    };

    let _ = with_api(|api| unsafe {
        let old = G_MPVP.swap(std::ptr::null_mut(), Ordering::AcqRel);
        if !old.is_null() {
            safe_terminate_mpv(api, old);
        }

        let handle = (api.mpv_create)();
        if handle.is_null() { return; }
        G_EVENT_THREAD_EXIT.store(false, Ordering::Release);
        G_MPVP.store(handle, Ordering::Release);
        HANDLE_VALID.store(true, Ordering::Release);

        if let Some(VmPtr(vm_ptr)) = G_VM.get() {
            type JniSetter = unsafe extern "C" fn(*mut c_void, *mut c_void) -> c_int;
            let set_vm = resolve_avcodec_symbol("av_jni_set_java_vm");
            if !set_vm.is_null() {
                let f: JniSetter = std::mem::transmute(set_vm);
                f(vm_ptr.cast::<c_void>(), std::ptr::null_mut());
                nlog("JNI: av_jni_set_java_vm 注入成功");
            }
            let set_ctx = resolve_avcodec_symbol("av_jni_set_android_app_ctx");
            if !set_ctx.is_null() {
                if let Some(ctx_ptr) = global_ctx_raw {
                    let f: JniSetter = std::mem::transmute(set_ctx);
                    let res = f(ctx_ptr, std::ptr::null_mut());
                    nlog(&format!("JNI: av_jni_set_android_app_ctx 注入全局引用完成 res={res}"));
                }
            }
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_init(
    mut env: JNIEnv,
    _class: JClass,
) {
    cache_class(&mut env);
    let _ = with_api(|api| unsafe {
        let handle = match mpv_handle() {
            Ok(h) => h,
            Err(_) => return,
        };
        let rc = (api.mpv_initialize)(handle);
        if rc < 0 {
            nlog(&format!("init: mpv_initialize 失败 rc={rc}"));
            return;
        }
        let log_level = CString::new("info").unwrap_or_default();
        (api.mpv_request_log_messages)(handle, log_level.as_ptr());
        // Subscribe to mpv's own frame-loss counters. They are handled natively, see
        // `dispatch_property`, and they are the only honest signal this pipeline has for "frames
        // stopped keeping up": a render-duration threshold cannot tell a slow GPU from a frame that
        // simply arrived early relative to its deadline.
        for counter in FrameCounter::ALL {
            if let Ok(name) = CString::new(counter.property()) {
                (api.mpv_observe_property)(handle, 0, name.as_ptr(), MPV_FORMAT_INT64);
            }
        }
        nlog("init: 已订阅 mpv 帧丢失计数");
        G_EVENT_THREAD_EXIT.store(false, Ordering::Release);
        if let Ok(jh) = std::thread::Builder::new()
            .name("gyro-event".into())
            .spawn(event_thread)
        {
            if let Ok(mut lock) = EVENT_THREAD.lock() {
                *lock = Some(jh);
            }
        }
        nlog("init: mpv 初始化完成并已拉起事件泵");

        if let Some(lock) = RENDER_THREAD.get() {
            if let Ok(guard) = lock.lock() {
                if let Some(ref r_handle) = *guard {
                    let _ = r_handle.sender.send(RenderCommand::NotifyFrame);
                }
            }
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_destroy(
    _env: JNIEnv,
    _class: JClass,
) {
    let _ = with_api(|api| unsafe {
        let handle = G_MPVP.swap(std::ptr::null_mut(), Ordering::AcqRel);
        if !handle.is_null() {
            safe_terminate_mpv(api, handle);
        }
    });

    // Join outside the global lock: the render thread takes no locks itself, but mpv's update
    // callback does, and holding it here would stall frame production during teardown.
    let handle = RENDER_THREAD
        .get()
        .and_then(|lock| lock.lock().ok().and_then(|mut guard| guard.take()));
    if let Some(handle) = handle {
        let _ = handle.sender.send(RenderCommand::Shutdown);
        let _ = handle._thread.join();
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_attachSurface(
    _env: JNIEnv,
    _class: JClass,
    _surface: jobject,
) {
    #[cfg(target_os = "android")]
    unsafe {
        if _surface.is_null() { return; }
        let mut env = _env;
        let raw_env = env.get_native_interface();
        let win = ANativeWindow_fromSurface(raw_env, _surface);
        if win.is_null() {
            nlog("attachSurface: ANativeWindow_fromSurface 返回 null");
            return;
        }
        ANativeWindow_acquire(win);

        // This runs on the UI thread and blocks until the render thread has the surface current and
        // a render context built, so the wait is bounded by mpv's first-paint cost. Anything above
        // the budget is an ANR risk worth a log line rather than a silent stall.
        let attach_started = Instant::now();

        match render_sender() {
            Some(sender) => {
                let outcome = request_ack(
                    &sender,
                    |ack| RenderCommand::AttachSurface(NativeWindowPtr(win), ack),
                    std::time::Duration::from_millis(ATTACH_ACK_TIMEOUT_MS),
                );
                if outcome == AckOutcome::Disconnected {
                    // Render thread already exited: hand the reference back instead of leaking it.
                    nlog("attachSurface: 渲染线程已退出，归还窗口引用");
                    ANativeWindow_release(win);
                } else {
                    let waited = attach_started.elapsed();
                    if waited.as_millis() > ATTACH_WAIT_BUDGET_MS {
                        nlog(&format!(
                            "attachSurface: 主线程阻塞 {}ms（超出 {ATTACH_WAIT_BUDGET_MS}ms 预算，outcome={outcome:?}）",
                            waited.as_millis()
                        ));
                    }
                }
            }
            None => {
                nlog("attachSurface: 渲染线程不存在，归还窗口引用");
                ANativeWindow_release(win);
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_detachSurface(
    _env: JNIEnv,
    _class: JClass,
) {
    // Enqueue and return: surfaceDestroyed runs on the UI thread and has no reason to block.
    //
    // The strict reading of the framework contract is that the surface must not be touched after
    // this returns, which is why it used to wait. That reading is unnecessary here: attachSurface
    // takes its own ANativeWindow reference, and only the render thread releases it, so the buffer
    // queue outlives the Java Surface object.
    if let Some(sender) = render_sender() {
        if sender.send(RenderCommand::DetachSurface).is_err() {
            nlog("detachSurface: 渲染线程已退出，无需解绑");
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_redraw(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Some(sender) = render_sender() {
        let _ = sender.send(RenderCommand::Redraw);
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_command(
    mut env: JNIEnv,
    _class: JClass,
    args: JObjectArray,
) {
    let _ = with_api(|api| unsafe {
        let handle = match mpv_handle() {
            Ok(h) => h,
            Err(_) => return,
        };
        let count = env.get_array_length(&args).unwrap_or(0);
        let mut c_args: Vec<CString> = Vec::with_capacity(count as usize);
        for i in 0..count {
            let item = match env.get_object_array_element(&args, i) {
                Ok(o) => JString::from(o),
                Err(_) => return,
            };
            let s: String = match env.get_string(&item) {
                Ok(s) => s.to_string_lossy().into_owned(),
                Err(_) => return,
            };
            if let Ok(c) = CString::new(s) {
                c_args.push(c);
            }
        }
        let mut ptrs: Vec<*mut c_char> = c_args.iter().map(|s| s.as_ptr() as *mut c_char).collect();
        ptrs.push(std::ptr::null_mut());
        let rc = (api.mpv_command)(handle, ptrs.as_mut_ptr());
        nlog(&format!("mpv_command: {:?} -> rc={rc}", c_args));
    });
}

fn jstr_to_cstring(env: &mut JNIEnv, s: &JString) -> Result<CString, String> {
    let owned: String = env.get_string(s).map_err(|_| "get_string failed")?.to_string_lossy().into_owned();
    CString::new(owned).map_err(|_| "interior nul".into())
}

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_setOptionString(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    value: JString,
) -> jint {
    let result = (|| -> Result<i32, String> {
        let n = jstr_to_cstring(&mut env, &name)?;
        let v = jstr_to_cstring(&mut env, &value)?;
        with_api(|api| unsafe {
            let handle = mpv_handle()?;
            Ok((api.mpv_set_option_string)(handle, n.as_ptr(), v.as_ptr()))
        })?
    })();
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_getPropertyLong(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
) -> jlong {
    let result = (|| -> Result<i64, String> {
        let n = jstr_to_cstring(&mut env, &name)?;
        with_api(|api| unsafe {
            let handle = mpv_handle()?;
            let mut out: i64 = i64::MIN;
            let rc = (api.mpv_get_property)(handle, n.as_ptr(), MPV_FORMAT_INT64, std::ptr::from_mut(&mut out).cast::<c_void>());
            if rc != MPV_ERROR_SUCCESS { return Err("get failed".into()); }
            Ok(out)
        })?
    })();
    result.unwrap_or(i64::MIN)
}

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_getPropertyDouble(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
) -> jdouble {
    let result = (|| -> Result<f64, String> {
        let n = jstr_to_cstring(&mut env, &name)?;
        with_api(|api| unsafe {
            let handle = mpv_handle()?;
            let mut out: f64 = f64::NAN;
            let rc = (api.mpv_get_property)(handle, n.as_ptr(), MPV_FORMAT_DOUBLE, std::ptr::from_mut(&mut out).cast::<c_void>());
            if rc != MPV_ERROR_SUCCESS { return Err("get failed".into()); }
            Ok(out)
        })?
    })();
    result.unwrap_or(f64::NAN)
}

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_getPropertyBoolean(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
) -> jboolean {
    let result = (|| -> Result<bool, String> {
        let n = jstr_to_cstring(&mut env, &name)?;
        with_api(|api| unsafe {
            let handle = mpv_handle()?;
            let mut out: c_int = 0;
            let rc = (api.mpv_get_property)(handle, n.as_ptr(), MPV_FORMAT_FLAG, std::ptr::from_mut(&mut out).cast::<c_void>());
            if rc != MPV_ERROR_SUCCESS { return Err("get failed".into()); }
            Ok(out != 0)
        })?
    })();
    match result {
        Ok(v) => v as jboolean,
        Err(_) => 2u8,
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_getPropertyString(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
) -> jobject {
    let result = (|| -> Result<jobject, String> {
        let n = jstr_to_cstring(&mut env, &name)?;
        with_api(|api| unsafe {
            let handle = mpv_handle()?;
            let mut out: *mut c_char = std::ptr::null_mut();
            let rc = (api.mpv_get_property)(handle, n.as_ptr(), MPV_FORMAT_STRING, std::ptr::from_mut(&mut out).cast::<c_void>());
            if rc != MPV_ERROR_SUCCESS || out.is_null() { return Err("get failed".into()); }
            let s = CStr::from_ptr(out).to_string_lossy().into_owned();
            (api.mpv_free)(out as *mut c_void);
            let j = env.new_string(s).map_err(|_| "new_string failed")?;
            Ok(j.into_raw())
        })?
    })();
    result.unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_setPropertyLong(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    value: jlong,
) {
    let _ = (|| -> Result<(), String> {
        let n = jstr_to_cstring(&mut env, &name)?;
        let mut v: i64 = value;
        with_api(|api| unsafe {
            let handle = mpv_handle()?;
            (api.mpv_set_property)(handle, n.as_ptr(), MPV_FORMAT_INT64, std::ptr::from_mut(&mut v).cast::<c_void>());
            Ok(())
        })?
    })();
}

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_setPropertyDouble(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    value: jdouble,
) {
    let _ = (|| -> Result<(), String> {
        let n = jstr_to_cstring(&mut env, &name)?;
        let mut v: f64 = value;
        with_api(|api| unsafe {
            let handle = mpv_handle()?;
            (api.mpv_set_property)(handle, n.as_ptr(), MPV_FORMAT_DOUBLE, std::ptr::from_mut(&mut v).cast::<c_void>());
            Ok(())
        })?
    })();
}

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_setPropertyBoolean(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    value: jboolean,
) {
    let _ = (|| -> Result<(), String> {
        let n = jstr_to_cstring(&mut env, &name)?;
        let mut v: c_int = (value != 0) as c_int;
        with_api(|api| unsafe {
            let handle = mpv_handle()?;
            (api.mpv_set_property)(handle, n.as_ptr(), MPV_FORMAT_FLAG, std::ptr::from_mut(&mut v).cast::<c_void>());
            Ok(())
        })?
    })();
}

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_setPropertyString(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    value: JString,
) {
    let _ = (|| -> Result<(), String> {
        let n = jstr_to_cstring(&mut env, &name)?;
        let v = jstr_to_cstring(&mut env, &value)?;
        with_api(|api| unsafe {
            let handle = mpv_handle()?;
            let mut value_ptr: *const c_char = v.as_ptr();
            (api.mpv_set_property)(handle, n.as_ptr(), MPV_FORMAT_STRING, std::ptr::from_mut(&mut value_ptr).cast::<c_void>());
            Ok(())
        })?
    })();
}

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_observeProperty(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    format: jint,
) {
    let _ = (|| -> Result<(), String> {
        let n = jstr_to_cstring(&mut env, &name)?;
        with_api(|api| unsafe {
            let handle = mpv_handle()?;
            (api.mpv_observe_property)(handle, 0, n.as_ptr(), format);
            Ok(())
        })?
    })();
}

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_isZeroCopySafe(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    G_ZERO_COPY_SAFE.load(Ordering::Acquire) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_getGpuRenderer(
    mut env: JNIEnv,
    _class: JClass,
) -> jobject {
    let renderer = G_GPU_RENDERER.get().map(|s| s.as_str()).unwrap_or("unknown");
    match env.new_string(renderer) {
        Ok(j) => j.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
