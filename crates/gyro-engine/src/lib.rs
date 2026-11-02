//! Gyro Engine: libmpv 核心 JNI 桥与完全下沉的纯原生 EGL 渲染管线

mod logic;

use logic::FrameCoalescer;
// Render timing only exists in the Android render loop.
#[cfg(target_os = "android")]
use logic::{RenderWatchdog, WatchdogVerdict};
use jni::objects::{GlobalRef, JClass, JObject, JObjectArray, JString, JValue};
use jni::sys::{jboolean, jdouble, jint, jlong, jobject, JNI_VERSION_1_6};
use jni::{JNIEnv, JavaVM};
use libloading::{Library, Symbol};
use std::ffi::{c_char, c_double, c_int, c_void, CStr, CString};
use std::sync::atomic::{AtomicBool, AtomicPtr, Ordering};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Mutex, OnceLock};
use std::thread::JoinHandle;
// Only the Android render loop measures frame timing.
#[cfg(target_os = "android")]
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
const EGL_BAD_SURFACE: EGLint = 0x300D;
const EGL_CONTEXT_LOST: EGLint = 0x300E;

const GL_VENDOR: u32 = 0x1F00;
const GL_RENDERER: u32 = 0x1F01;
const GL_EXTENSIONS: u32 = 0x1F03;

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
    fn eglTerminate(dpy: EGLDisplay) -> EGLint;
    fn eglGetProcAddress(procname: *const c_char) -> *mut c_void;
}

#[cfg(target_os = "android")]
#[link(name = "android")]
extern "C" {
    fn ANativeWindow_fromSurface(env: *mut jni::sys::JNIEnv, surface: jobject) -> *mut ANativeWindow;
    fn ANativeWindow_acquire(window: *mut ANativeWindow);
    fn ANativeWindow_release(window: *mut ANativeWindow);
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
            unsafe { __android_log_write(3, tag.as_ptr(), text.as_ptr()) };
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

unsafe fn mpv_handle() -> Result<*mut c_void, String> {
    let p = G_MPVP.load(Ordering::Acquire);
    if p.is_null() {
        Err("mpv handle is null".into())
    } else {
        Ok(p)
    }
}

extern "C" {
    fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
    fn dlopen(filename: *const c_char, flags: c_int) -> *mut c_void;
}
const RTLD_LAZY: c_int = 0x0001;

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

// ---------- 原生渲染命令与常驻线程 ----------

enum RenderCommand {
    AttachSurface(NativeWindowPtr, std::sync::mpsc::SyncSender<()>),
    DetachSurface(std::sync::mpsc::SyncSender<()>),
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

// ---------- 防御层 1：冷启动离屏环境探针 (Static Env Probe) ----------
static G_ZERO_COPY_SAFE: AtomicBool = AtomicBool::new(true);
static G_GPU_RENDERER: OnceLock<String> = OnceLock::new();
static G_GPU_VENDOR: OnceLock<String> = OnceLock::new();

// ---------- 防御层 2：运行时帧看门狗 (Dynamic Render Watchdog) ----------
// 判定逻辑见 logic::RenderWatchdog（宿主机可单测），此处只保留跨会话的降级闩锁
static G_WATCHDOG_FALLBACK_TRIGGERED: AtomicBool = AtomicBool::new(false);
const MPV_EVENT_WATCHDOG_FALLBACK: jint = 1001;

#[cfg(target_os = "android")]
unsafe fn probe_graphics_environment(display: EGLDisplay, config: EGLConfig, ctx: EGLContext) {
    let egl_ext_ptr = eglQueryString(display, EGL_EXTENSIONS);
    let egl_ext = if !egl_ext_ptr.is_null() {
        CStr::from_ptr(egl_ext_ptr).to_string_lossy().into_owned()
    } else {
        String::new()
    };

    let has_image_base = egl_ext.contains("EGL_KHR_image_base") || egl_ext.contains("EGL_KHR_image");
    let has_android_native = egl_ext.contains("EGL_ANDROID_image_native_buffer");

    // 创建 16x16 离屏 Pbuffer Surface 探查 GLES 扩展
    let pbuf_attribs = [EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE];
    let pbuf = eglCreatePbufferSurface(display, config, pbuf_attribs.as_ptr());
    let mut renderer_str = String::from("unknown");
    let mut vendor_str = String::from("unknown");
    let mut has_oes_external = false;

    if !pbuf.is_null() && pbuf != EGL_NO_SURFACE {
        if eglMakeCurrent(display, pbuf, pbuf, ctx) == EGL_TRUE {
            let p = eglGetProcAddress(b"glGetString\0".as_ptr().cast());
            if !p.is_null() {
                let gl_get_string: unsafe extern "C" fn(u32) -> *const c_char = std::mem::transmute(p);
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
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }
        eglDestroySurface(display, pbuf);
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
unsafe fn probe_graphics_environment(_display: EGLDisplay, _config: EGLConfig, _ctx: EGLContext) {}

/// Degrades hardware decoding to the copy path once the render watchdog decides the GPU is stuck.
///
/// `hwdec` is runtime-changeable, but `mpv_set_option_string` is only valid before
/// `mpv_initialize` returns; afterwards the property API is the supported entry point. The return
/// code is checked and the fallback event is only emitted when the change actually applied, so the
/// UI can never announce a degradation that did not happen. `video-reload` is issued because a
/// decoder change only takes effect when the video stream is reinitialised; without it the switch
/// would silently wait for the next file to be opened.
unsafe fn trigger_hwdec_fallback(api: &MpvApi) {
    // Single attempt per process: retrying on every following slow frame cannot help and would
    // flood the log while the user is already looking at a frozen picture.
    if G_WATCHDOG_FALLBACK_TRIGGERED.swap(true, Ordering::SeqCst) {
        return;
    }

    let Ok(handle) = mpv_handle() else {
        // Nothing was attempted, so keep the latch open for a later frame.
        G_WATCHDOG_FALLBACK_TRIGGERED.store(false, Ordering::Release);
        return;
    };

    let name = CString::new("hwdec").unwrap_or_default();
    let value = CString::new("mediacodec-copy").unwrap_or_default();
    let mut value_ptr: *const c_char = value.as_ptr();
    let rc = (api.mpv_set_property)(
        handle,
        name.as_ptr(),
        MPV_FORMAT_STRING,
        std::ptr::from_mut(&mut value_ptr).cast::<c_void>(),
    );
    if rc != MPV_ERROR_SUCCESS {
        nlog(&format!(
            "WATCHDOG: hwdec degrade rejected rc={rc}, keeping the current decoder and reporting nothing"
        ));
        return;
    }

    let args = vec![CString::new("video-reload").unwrap_or_default()];
    let mut arg_ptrs: Vec<*mut c_char> = args.iter().map(|a| a.as_ptr() as *mut c_char).collect();
    arg_ptrs.push(std::ptr::null_mut());
    let reload_rc = (api.mpv_command)(handle, arg_ptrs.as_mut_ptr());
    if reload_rc == MPV_ERROR_SUCCESS {
        nlog("WATCHDOG: hwdec degraded to mediacodec-copy, video stream reloaded");
    } else {
        nlog(&format!(
            "WATCHDOG: hwdec set but video-reload failed rc={reload_rc}, takes effect on next file"
        ));
    }

    if let Some(VmPtr(vm_ptr)) = G_VM.get() {
        if let Ok(vm) = JavaVM::from_raw(vm_ptr.cast()) {
            if let Ok(mut env) = vm.attach_current_thread() {
                call_static(&mut env, "onEvent", "(I)V", &[JValue::Int(MPV_EVENT_WATCHDOG_FALLBACK)]);
            }
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
struct SyncHandle(*mut c_void);
unsafe impl Send for SyncHandle {}
unsafe impl Sync for SyncHandle {}

static REAL_GL_FENCE_SYNC: AtomicPtr<c_void> = AtomicPtr::new(std::ptr::null_mut());
static REAL_GL_DELETE_SYNC: AtomicPtr<c_void> = AtomicPtr::new(std::ptr::null_mut());
static ACTIVE_FENCES: Mutex<Vec<SyncHandle>> = Mutex::new(Vec::new());

unsafe extern "C" fn wrapped_gl_fence_sync(condition: u32, flags: u32) -> *mut c_void {
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

// 渲染线程主循环（纯原生 EGL 闭环）
fn native_render_loop(receiver: Receiver<RenderCommand>) {
    nlog("native_render_loop: 渲染线程已就绪");

    #[cfg(target_os = "android")]
    unsafe {
        let display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if display == EGL_NO_DISPLAY {
            nlog("EGL: eglGetDisplay 失败");
            return;
        }

        let mut major = 0;
        let mut minor = 0;
        if eglInitialize(display, &mut major, &mut minor) != EGL_TRUE {
            nlog("EGL: eglInitialize 失败");
            return;
        }

        let attribs = [
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_DEPTH_SIZE, 0,
            EGL_STENCIL_SIZE, 0,
            EGL_NONE,
        ];

        let mut config: EGLConfig = std::ptr::null_mut();
        let mut num_config = 0;
        if eglChooseConfig(display, attribs.as_ptr(), &mut config, 1, &mut num_config) != EGL_TRUE || num_config == 0 {
            nlog("EGL: eglChooseConfig 失败");
            return;
        }

        let mut visual_id = 0;
        eglGetConfigAttrib(display, config, EGL_NATIVE_VISUAL_ID, &mut visual_id);

        let ctx_attribs = [
            EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL_NONE,
        ];
        let egl_context = eglCreateContext(display, config, EGL_NO_CONTEXT, ctx_attribs.as_ptr());
        if egl_context == EGL_NO_CONTEXT {
            nlog("EGL: eglCreateContext 失败");
            return;
        }

        nlog("EGL: 常驻 EGLContext 创建成功");
        probe_graphics_environment(display, config, egl_context);

        let mut current_window: *mut ANativeWindow = std::ptr::null_mut();
        let mut current_surface: EGLSurface = EGL_NO_SURFACE;
        let mut mpv_render_ctx: *mut c_void = std::ptr::null_mut();

        // Render-thread-owned state: both types are plain data, so no locking is involved.
        let mut watchdog = RenderWatchdog::with_defaults();
        let mut last_frame_at: Option<Instant> = None;

        while let Ok(cmd) = receiver.recv() {
            match cmd {
                RenderCommand::AttachSurface(NativeWindowPtr(window), ack) => {
                    nlog("EGL: 收到 AttachSurface 命令");
                    if !current_surface.is_null() && current_surface != EGL_NO_SURFACE {
                        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
                        eglDestroySurface(display, current_surface);
                        current_surface = EGL_NO_SURFACE;
                    }
                    if !current_window.is_null() {
                        ANativeWindow_release(current_window);
                        current_window = std::ptr::null_mut();
                    }

                    if !window.is_null() {
                        current_window = window;
                        ANativeWindow_setBuffersGeometry(window, 0, 0, visual_id);

                        current_surface = eglCreateWindowSurface(display, config, window, std::ptr::null());
                        if current_surface == EGL_NO_SURFACE {
                            nlog("EGL: eglCreateWindowSurface 失败");
                            let _ = ack.send(());
                            continue;
                        }

                        if eglMakeCurrent(display, current_surface, current_surface, egl_context) != EGL_TRUE {
                            nlog("EGL: eglMakeCurrent 失败");
                            let _ = ack.send(());
                            continue;
                        }

                        // 开启垂直同步，防止画面撕裂与过度消耗
                        eglSwapInterval(display, 1);
                        // Surface 和 EGL 上下文已就绪，立即唤醒 UI 线程避免等待首帧解码/绘制引起界面掉帧
                        let _ = ack.send(());

                        if mpv_render_ctx.is_null() {
                            if let Ok(handle) = mpv_handle() {
                                let _ = with_api(|api| {
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
                                    let rc = (api.mpv_render_context_create)(&mut mpv_render_ctx, handle, params.as_mut_ptr());
                                    if rc == 0 {
                                        (api.mpv_render_context_set_update_callback)(
                                             mpv_render_ctx,
                                             Some(mpv_update_callback),
                                             std::ptr::null_mut(),
                                        );
                                        nlog("EGL: mpv_render_context 首次挂载就绪");
                                    } else {
                                        nlog(&format!("EGL: mpv_render_context_create 失败 rc={rc}"));
                                    }
                                });
                            }
                        }

                        draw_frame_native(display, current_surface, mpv_render_ctx, true, &mut watchdog, &mut last_frame_at);
                    } else {
                        let _ = ack.send(());
                    }
                }
                RenderCommand::DetachSurface(ack) => {
                    nlog("EGL: 收到 DetachSurface 命令 (保持 EGLContext 常驻)");
                    if !current_surface.is_null() && current_surface != EGL_NO_SURFACE {
                        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
                        eglDestroySurface(display, current_surface);
                        current_surface = EGL_NO_SURFACE;
                    }
                    if !current_window.is_null() {
                        ANativeWindow_release(current_window);
                        current_window = std::ptr::null_mut();
                    }
                    let _ = ack.send(());
                }
                RenderCommand::Redraw => {
                    if current_surface != EGL_NO_SURFACE && !current_surface.is_null() && !mpv_render_ctx.is_null() {
                        draw_frame_native(display, current_surface, mpv_render_ctx, true, &mut watchdog, &mut last_frame_at);
                    }
                }
                RenderCommand::NotifyFrame => {
                    G_FRAME_GATE.release();
                    if current_surface != EGL_NO_SURFACE && !current_surface.is_null() {
                        if mpv_render_ctx.is_null() {
                            if let Ok(handle) = mpv_handle() {
                                let _ = with_api(|api| {
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
                                    let rc = (api.mpv_render_context_create)(&mut mpv_render_ctx, handle, params.as_mut_ptr());
                                    if rc == 0 {
                                        (api.mpv_render_context_set_update_callback)(
                                            mpv_render_ctx,
                                            Some(mpv_update_callback),
                                            std::ptr::null_mut(),
                                        );
                                        nlog("EGL: mpv_render_context 延迟挂载成功");
                                    }
                                });
                            }
                        }
                        draw_frame_native(display, current_surface, mpv_render_ctx, false, &mut watchdog, &mut last_frame_at);
                    }
                }
                RenderCommand::DestroyContext(ack) => {
                    nlog("EGL: 收到 DestroyContext 请求，开始销毁 mpv_render_context");
                    if !mpv_render_ctx.is_null() {
                        let _ = with_api(|api| (api.mpv_render_context_free)(mpv_render_ctx));
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
                        let _ = with_api(|api| (api.mpv_render_context_free)(mpv_render_ctx));
                        mpv_render_ctx = std::ptr::null_mut();
                    }
                    if !current_surface.is_null() && current_surface != EGL_NO_SURFACE {
                        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
                        eglDestroySurface(display, current_surface);
                    }
                    if !current_window.is_null() {
                        ANativeWindow_release(current_window);
                    }
                    if egl_context != EGL_NO_CONTEXT {
                        eglDestroyContext(display, egl_context);
                    }
                    eglTerminate(display);
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

// 原生帧绘制（在渲染线程内部执行，0 JNI 开销）
#[cfg(target_os = "android")]
unsafe fn draw_frame_native(
    display: EGLDisplay,
    surface: EGLSurface,
    ctx: *mut c_void,
    force: bool,
    watchdog: &mut RenderWatchdog,
    last_frame_at: &mut Option<Instant>,
) {
    if ctx.is_null() || surface == EGL_NO_SURFACE {
        return;
    }
    let _ = with_api(|api| {
        if !force {
            let flags = (api.mpv_render_context_update)(ctx);
            if flags & MPV_RENDER_UPDATE_FRAME == 0 {
                return;
            }
        }

        let mut w: EGLint = 0;
        let mut h: EGLint = 0;
        eglQuerySurface(display, surface, EGL_WIDTH, &mut w);
        eglQuerySurface(display, surface, EGL_HEIGHT, &mut h);
        if w <= 0 || h <= 0 {
            return;
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

        // A frame that follows a long silence is a resume, a seek or the first frame of a load, so
        // its cost must not be judged as steady-state playback.
        let now = Instant::now();
        let gap_ms = last_frame_at
            .map(|prev| now.duration_since(prev).as_millis())
            .unwrap_or(u128::MAX);
        *last_frame_at = Some(now);

        let render_start = Instant::now();
        (api.mpv_render_context_render)(ctx, params.as_mut_ptr());
        (api.mpv_render_context_report_swap)(ctx);
        let swap_rc = eglSwapBuffers(display, surface);
        let elapsed = render_start.elapsed();

        if swap_rc != EGL_TRUE {
            let err = eglGetError();
            nlog(&format!("WATCHDOG: eglSwapBuffers 失败 rc={swap_rc} error=0x{err:X}"));
            if err == EGL_CONTEXT_LOST {
                trigger_hwdec_fallback(api);
            }
            return;
        }

        // Verdicts and thresholds live in logic::RenderWatchdog so they can be tested on the host.
        if let WatchdogVerdict::Trip = watchdog.on_frame(elapsed.as_millis(), gap_ms) {
            nlog(&format!(
                "WATCHDOG: consecutive slow frames ({}ms), degrading hardware decoding",
                elapsed.as_millis()
            ));
            trigger_hwdec_fallback(api);
        }
    });
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
    if let Some(lock) = RENDER_THREAD.get() {
        if let Ok(guard) = lock.lock() {
            if let Some(ref r_handle) = *guard {
                let (ack_tx, ack_rx) = std::sync::mpsc::sync_channel(1);
                let _ = r_handle.sender.send(RenderCommand::DestroyContext(ack_tx));
                let _ = ack_rx.recv_timeout(std::time::Duration::from_millis(500));
            }
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

    if let Some(lock) = RENDER_THREAD.get() {
        if let Ok(mut guard) = lock.lock() {
            if let Some(handle) = guard.take() {
                let _ = handle.sender.send(RenderCommand::Shutdown);
                let _ = handle._thread.join();
            }
        }
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

        if let Some(lock) = RENDER_THREAD.get() {
            if let Ok(guard) = lock.lock() {
                if let Some(ref handle) = *guard {
                    let (ack_tx, ack_rx) = std::sync::mpsc::sync_channel(1);
                    let _ = handle.sender.send(RenderCommand::AttachSurface(NativeWindowPtr(win), ack_tx));
                    let _ = ack_rx.recv_timeout(std::time::Duration::from_millis(1000));
                }
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_detachSurface(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Some(lock) = RENDER_THREAD.get() {
        if let Ok(guard) = lock.lock() {
            if let Some(ref handle) = *guard {
                let (ack_tx, ack_rx) = std::sync::mpsc::sync_channel(1);
                let _ = handle.sender.send(RenderCommand::DetachSurface(ack_tx));
                // 严格遵循 Android surfaceDestroyed 协议：必须同步等待渲染线程销毁当前 EGLSurface
                let _ = ack_rx.recv_timeout(std::time::Duration::from_millis(500));
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_redraw(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Some(lock) = RENDER_THREAD.get() {
        if let Ok(guard) = lock.lock() {
            if let Some(ref handle) = *guard {
                let _ = handle.sender.send(RenderCommand::Redraw);
            }
        }
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
