//! libmpv FFI 绑定、动态加载与安全生命周期封装

#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use jni::sys::jint;
use libloading::{Library, Symbol};
use std::ffi::{c_char, c_double, c_int, c_void};
use std::sync::atomic::{AtomicBool, AtomicPtr, Ordering};
use std::sync::{Mutex, OnceLock, RwLock, RwLockReadGuard};

use crate::nlog;

// ---------- libmpv 常量与类型 ----------

pub const MPV_FORMAT_NONE: c_int = 0;
pub const MPV_FORMAT_STRING: c_int = 1;
pub const MPV_FORMAT_FLAG: c_int = 3;
pub const MPV_FORMAT_INT64: c_int = 4;
pub const MPV_FORMAT_DOUBLE: c_int = 5;

pub const MPV_EVENT_SHUTDOWN: c_int = 1;
pub const MPV_EVENT_LOG_MESSAGE: c_int = 2;
pub const MPV_EVENT_START_FILE: c_int = 6;
pub const MPV_EVENT_FILE_LOADED: c_int = 8;
pub const MPV_EVENT_END_FILE: c_int = 7;
pub const MPV_END_FILE_REASON_EOF: c_int = 0;
pub const MPV_END_FILE_REASON_STOP: c_int = 2;
pub const MPV_END_FILE_REASON_QUIT: c_int = 3;
pub const MPV_END_FILE_REASON_ERROR: c_int = 4;
pub const MPV_END_FILE_REASON_REDIRECT: c_int = 5;
pub const MPV_EVENT_PLAYBACK_RESTART: c_int = 21;
pub const MPV_EVENT_PROPERTY_CHANGE: c_int = 22;

/// Java-side event id for "the first frame of the current file is on screen".
///
/// mpv's own ids stop well below 1000, so the app-defined range is free above it; the Kotlin side
/// declares the same constant.
pub const JAVA_EVENT_FIRST_FRAME: jint = 1002;

pub const MPV_ERROR_SUCCESS: c_int = 0;
pub const MPV_RENDER_PARAM_API_TYPE: c_int = 1;
pub const MPV_RENDER_PARAM_OPENGL_INIT_PARAMS: c_int = 2;
pub const MPV_RENDER_PARAM_OPENGL_FBO: c_int = 3;
pub const MPV_RENDER_PARAM_FLIP_Y: c_int = 4;
pub const MPV_RENDER_PARAM_VULKAN_INIT_PARAMS: c_int = 6;
pub const MPV_RENDER_PARAM_VULKAN_IMAGE: c_int = 7;
pub const MPV_RENDER_PARAM_NEXT_FRAME_INFO: c_int = 11;
pub const MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME: c_int = 12;
pub const MPV_RENDER_PARAM_SKIP_RENDERING: c_int = 13;
pub const MPV_RENDER_PARAM_BACKEND: c_int = 21;

/// `mpv_render_frame_info.flags` bit: the queued "frame" is a redraw, not a new picture.
///
/// render.h: "If set, the frame is not an actual new video frame, but a redraw request. For example
/// if the video is paused, and an option that affects video rendering was changed (or any other
/// reason)". Loading a file is such a reason: mpv redraws the previous file's last frame.
pub const MPV_RENDER_FRAME_INFO_REDRAW: u64 = 1 << 1;
pub const MPV_RENDER_UPDATE_FRAME: u64 = 1;

#[repr(C)]
pub struct MpvRenderParam {
    pub param_type: c_int,
    pub data: *mut c_void,
}

/// Layout of `mpv_render_frame_info` (render.h): `flags`, then the target display time.
#[repr(C)]
pub struct MpvRenderFrameInfo {
    pub flags: u64,
    pub target_time: i64,
}

#[repr(C)]
pub struct MpvOpenGLInitParams {
    pub get_proc_address: unsafe extern "C" fn(*mut c_void, *const c_char) -> *mut c_void,
    pub get_proc_address_ctx: *mut c_void,
}

#[repr(C)]
pub struct MpvOpenGLFbo {
    pub fbo: c_int,
    pub w: c_int,
    pub h: c_int,
    pub internal_format: c_int,
}

#[repr(C)]
pub struct MpvVulkanInitParams {
    pub instance: *mut c_void,
    pub physical_device: *mut c_void,
    pub device: *mut c_void,
    pub queue: *mut c_void,
    pub queue_family_index: u32,
    pub get_instance_proc_addr: unsafe extern "C" fn(*mut c_void, *const c_char) -> *mut c_void,
}

#[repr(C)]
pub struct MpvVulkanImage {
    pub image: u64,
    pub image_layout: u32,
    pub format: u32,
    pub width: c_int,
    pub height: c_int,
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

pub struct MpvApi {
    pub mpv_create: unsafe extern "C" fn() -> *mut c_void,
    pub mpv_initialize: unsafe extern "C" fn(*mut c_void) -> c_int,
    pub mpv_terminate_destroy: unsafe extern "C" fn(*mut c_void),
    pub mpv_set_option_string: unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char) -> c_int,
    pub mpv_command: unsafe extern "C" fn(*mut c_void, *mut *mut c_char) -> c_int,
    pub mpv_get_property: unsafe extern "C" fn(*mut c_void, *const c_char, c_int, *mut c_void) -> c_int,
    pub mpv_set_property: unsafe extern "C" fn(*mut c_void, *const c_char, c_int, *mut c_void) -> c_int,
    pub mpv_observe_property: unsafe extern "C" fn(*mut c_void, u64, *const c_char, c_int) -> c_int,
    pub mpv_request_log_messages: unsafe extern "C" fn(*mut c_void, *const c_char) -> c_int,
    pub mpv_wait_event: unsafe extern "C" fn(*mut c_void, c_double) -> *mut c_void,
    pub mpv_wakeup: unsafe extern "C" fn(*mut c_void),
    pub mpv_get_time_us: unsafe extern "C" fn(*mut c_void) -> i64,
    pub mpv_render_context_create:
        unsafe extern "C" fn(*mut *mut c_void, *mut c_void, *mut MpvRenderParam) -> c_int,
    pub mpv_render_context_render: unsafe extern "C" fn(*mut c_void, *mut MpvRenderParam) -> c_int,
    pub mpv_render_context_report_swap: unsafe extern "C" fn(*mut c_void),
    pub mpv_render_context_update: unsafe extern "C" fn(*mut c_void) -> u64,
    pub mpv_render_context_get_info: unsafe extern "C" fn(*mut c_void, MpvRenderParam) -> c_int,
    pub mpv_render_context_set_update_callback:
        unsafe extern "C" fn(*mut c_void, Option<unsafe extern "C" fn(*mut c_void)>, *mut c_void),
    pub mpv_render_context_free: unsafe extern "C" fn(*mut c_void),
    pub mpv_free: unsafe extern "C" fn(*mut c_void),
}

unsafe impl Send for MpvApi {}
unsafe impl Sync for MpvApi {}

// ---------- 全局状态与符号解析 ----------

pub static G_MPVP: AtomicPtr<c_void> = AtomicPtr::new(std::ptr::null_mut());
/// Protects synchronous JNI operations (`command`, `setOptionString`, `getProperty*`,
/// `setProperty*`, `observeProperty`) against racing with `destroy()` / `create()`'s
/// `safe_terminate_mpv`.
///
/// Short synchronous JNI calls acquire a read lock around their `mpv_*` invocation, while
/// `create()` and `destroy()` take a write lock to atomically swap `G_MPVP` to null and wait for
/// all in-flight synchronous JNI calls to finish before invoking `safe_terminate_mpv`.
/// Note: `event_thread` and `render_thread` do NOT hold this read lock across blocking waits
/// (`mpv_wait_event` / `mpv_render_context_render`), because `safe_terminate_mpv` synchronizes
/// with those two threads explicitly via `mpv_wakeup` + `join` and `RenderCommand::DestroyContext`.
pub static G_MPV_LIFECYCLE: RwLock<usize> = RwLock::new(0);
pub static HANDLE_VALID: AtomicBool = AtomicBool::new(false);

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

pub fn cached_api() -> Result<&'static MpvApi, String> {
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
        mpv_get_time_us: sym!(lib, mpv_get_time_us, unsafe extern "C" fn(*mut c_void) -> i64),
        mpv_render_context_create: sym!(lib, mpv_render_context_create, unsafe extern "C" fn(*mut *mut c_void, *mut c_void, *mut MpvRenderParam) -> c_int),
        mpv_render_context_render: sym!(lib, mpv_render_context_render, unsafe extern "C" fn(*mut c_void, *mut MpvRenderParam) -> c_int),
        mpv_render_context_report_swap: sym!(lib, mpv_render_context_report_swap, unsafe extern "C" fn(*mut c_void)),
        mpv_render_context_update: sym!(lib, mpv_render_context_update, unsafe extern "C" fn(*mut c_void) -> u64),
        mpv_render_context_get_info: sym!(lib, mpv_render_context_get_info, unsafe extern "C" fn(*mut c_void, MpvRenderParam) -> c_int),
        mpv_render_context_set_update_callback: sym!(lib, mpv_render_context_set_update_callback, unsafe extern "C" fn(*mut c_void, Option<unsafe extern "C" fn(*mut c_void)>, *mut c_void)),
        mpv_render_context_free: sym!(lib, mpv_render_context_free, unsafe extern "C" fn(*mut c_void)),
        mpv_free: sym!(lib, mpv_free, unsafe extern "C" fn(*mut c_void)),
    };
    let _ = API.set(api);
    API.get().ok_or_else(|| "api cache init failed".to_string())
}

pub fn with_api<T>(f: impl FnOnce(&MpvApi) -> T) -> Result<T, String> {
    Ok(f(cached_api()?))
}

/// Runs an mpv call whose result cannot be acted on, but whose failure must not vanish.
///
/// The only way these fail is a missing symbol in libmpv, which used to surface as a silently
/// dead code path: the pipeline kept running with no render context and no explanation.
pub fn with_api_logged(what: &str, f: impl FnOnce(&MpvApi)) {
    if let Err(err) = with_api(f) {
        nlog(&format!("mpv: {what} 无法执行 ({err})"));
    }
}

pub unsafe fn mpv_handle() -> Result<*mut c_void, String> {
    let p = G_MPVP.load(Ordering::Acquire);
    if p.is_null() {
        Err("mpv handle is null".into())
    } else {
        Ok(p)
    }
}

/// Acquires the lifecycle read guard and returns the active mpv handle if valid.
///
/// Holding the returned [`RwLockReadGuard`] guarantees that `destroy()` / `create()` cannot reach
/// `safe_terminate_mpv` (`mpv_terminate_destroy`) until the caller finishes its `mpv_*` FFI call.
pub unsafe fn mpv_handle_guarded() -> Result<(RwLockReadGuard<'static, usize>, *mut c_void), String> {
    let guard = G_MPV_LIFECYCLE
        .read()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let p = G_MPVP.load(Ordering::Acquire);
    if p.is_null() || !HANDLE_VALID.load(Ordering::Acquire) {
        Err("mpv handle is null or invalid".into())
    } else {
        Ok((guard, p))
    }
}

/// Atomically detaches the current mpv handle under the lifecycle write lock so no new guarded
/// JNI call can start and all in-flight guarded JNI calls have completed.
pub fn take_mpv_handle_for_teardown() -> *mut c_void {
    let mut guard = G_MPV_LIFECYCLE
        .write()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    HANDLE_VALID.store(false, Ordering::Release);
    let old = G_MPVP.swap(std::ptr::null_mut(), Ordering::AcqRel);
    *guard = 0;
    old
}

pub unsafe fn safe_terminate_mpv(api: &MpvApi, handle: *mut c_void) {
    if handle.is_null() { return; }
    nlog("safe_terminate_mpv: 开始安全释放 mpv 实例");

    // 1. 停止并等待事件泵退出
    HANDLE_VALID.store(false, Ordering::Release);
    crate::events::stop_event_thread(api, handle);

    // 2. 必须在 mpv_destroy 前释放 render context
    crate::render::destroy_render_context_sync();

    // 3. 安全销毁 mpv 句柄
    (api.mpv_terminate_destroy)(handle);
    nlog("safe_terminate_mpv: mpv_terminate_destroy 顺利完成");
}
