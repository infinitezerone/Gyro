//! JNI 导出函数（供 Kotlin/Java 层 GyroNative 调用）

#![cfg_attr(not(target_os = "android"), allow(dead_code, unused_imports))]

use jni::objects::{JClass, JObject, JObjectArray, JString};
use jni::sys::{jboolean, jdouble, jint, jlong, jobject, JNI_VERSION_1_6};
use jni::{JNIEnv, JavaVM};
use std::ffi::{c_char, c_int, c_void, CStr, CString};
use std::sync::atomic::Ordering;

use crate::events::{
    cache_class, event_thread, EVENT_THREAD, G_APP_CTX, G_EVENT_THREAD_EXIT, G_VM,
};
use crate::logic::FrameCounter;
use crate::mpv::{
    mpv_handle_guarded, take_mpv_handle_for_teardown, with_api,
    G_MPVP, G_MPV_LIFECYCLE, HANDLE_VALID, MPV_ERROR_SUCCESS, MPV_FORMAT_DOUBLE,
    MPV_FORMAT_FLAG, MPV_FORMAT_INT64, MPV_FORMAT_STRING,
};
use crate::render::{
    clear_render_thread, ensure_render_thread, render_sender, window_ref, NativeWindowPtr,
    RenderCommand, G_GPU_RENDERER, G_ZERO_COPY_SAFE,
};
#[cfg(target_os = "android")]
use crate::render::ANativeWindow_fromSurface;
#[cfg(target_os = "android")]
use crate::render::ANativeWindow_acquire;
#[cfg(target_os = "android")]
use crate::render::ANativeWindow_release;
use crate::{log_error, nlog, reset_engine_lifecycle_state, safe_terminate_engine};

#[cfg(unix)]
extern "C" {
    fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
    fn dlopen(filename: *const c_char, flags: c_int) -> *mut c_void;
}
#[cfg(unix)]
const RTLD_LAZY: c_int = 0x0001;

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

pub(crate) fn match_static_property(name: &str) -> Option<&'static CStr> {
    match name {
        "time-pos" => Some(c"time-pos"),
        "duration" => Some(c"duration"),
        "pause" => Some(c"pause"),
        "paused-for-cache" => Some(c"paused-for-cache"),
        "demuxer-cache-time" => Some(c"demuxer-cache-time"),
        "speed" => Some(c"speed"),
        "dwidth" => Some(c"dwidth"),
        "dheight" => Some(c"dheight"),
        "width" => Some(c"width"),
        "height" => Some(c"height"),
        "video-out-params/dw" => Some(c"video-out-params/dw"),
        "video-out-params/dh" => Some(c"video-out-params/dh"),
        "video-out-params/rotate" => Some(c"video-out-params/rotate"),
        "video-params/rotate" => Some(c"video-params/rotate"),
        "container-fps" => Some(c"container-fps"),
        "estimated-vf-fps" => Some(c"estimated-vf-fps"),
        "frame-drop-count" => Some(c"frame-drop-count"),
        "vo-delayed-frame-count" => Some(c"vo-delayed-frame-count"),
        "mistimed-frame-count" => Some(c"mistimed-frame-count"),
        "hwdec-current" => Some(c"hwdec-current"),
        "media-title" => Some(c"media-title"),
        "display-fps" => Some(c"display-fps"),
        "display-fps-override" => Some(c"display-fps-override"),
        "sub-delay" => Some(c"sub-delay"),
        "audio-delay" => Some(c"audio-delay"),
        "sub-scale" => Some(c"sub-scale"),
        "sub-pos" => Some(c"sub-pos"),
        "chapter" => Some(c"chapter"),
        "deband" => Some(c"deband"),
        "brightness" => Some(c"brightness"),
        "contrast" => Some(c"contrast"),
        "saturation" => Some(c"saturation"),
        "volume" => Some(c"volume"),
        "af" => Some(c"af"),
        _ => None,
    }
}

fn with_property_cstr<R>(
    env: &mut JNIEnv,
    name: &JString,
    f: impl FnOnce(&CStr) -> Result<R, String>,
) -> Result<R, String> {
    let s = env.get_string(name).map_err(|_| "get_string failed")?;
    let s_str = s.to_str().unwrap_or("");
    if let Some(static_cstr) = match_static_property(s_str) {
        f(static_cstr)
    } else {
        let cstr = CString::new(s_str).map_err(|_| "interior nul")?;
        f(&cstr)
    }
}

fn jstr_to_cstring(env: &mut JNIEnv, s: &JString) -> Result<CString, String> {
    let jstr = env.get_string(s).map_err(|_| "get_string failed")?;
    CString::new(jstr.to_bytes()).map_err(|_| "interior nul".into())
}

#[inline]
fn catch_jni_panic<R, F>(func_name: &'static str, default: R, f: F) -> R
where
    F: FnOnce() -> R + std::panic::UnwindSafe,
{
    match std::panic::catch_unwind(f) {
        Ok(res) => res,
        Err(e) => {
            let msg = if let Some(s) = e.downcast_ref::<&str>() {
                *s
            } else if let Some(s) = e.downcast_ref::<String>() {
                s.as_str()
            } else {
                "unknown panic"
            };
            nlog(&format!("FATAL: JNI 边界拦截到未捕获的 Panic [{func_name}]: {msg}"));
            default
        }
    }
}

/// JNI 统一安全网关切面：自动注入异常拦截与日志屏障，杜绝任何裸奔入口
macro_rules! jni_fn {
    (
        $(#[$meta:meta])*
        fn $name:ident($($arg:ident : $arg_ty:ty),*) -> $ret:ty, default = $default:expr; $body:block
    ) => {
        $(#[$meta])*
        extern "system" fn $name($($arg : $arg_ty),*) -> $ret {
            catch_jni_panic(stringify!($name), $default, std::panic::AssertUnwindSafe(|| $body))
        }
    };
    (
        $(#[$meta:meta])*
        fn $name:ident($($arg:ident : $arg_ty:ty),*) $body:block
    ) => {
        $(#[$meta])*
        extern "system" fn $name($($arg : $arg_ty),*) {
            catch_jni_panic(stringify!($name), (), std::panic::AssertUnwindSafe(|| $body));
        }
    };
}

fn register_gyro_natives(env: &mut JNIEnv, class: &JClass) -> Result<(), jni::errors::Error> {
    let methods = [
        jni::NativeMethod {
            name: "create".into(),
            sig: "(Landroid/content/Context;)V".into(),
            fn_ptr: native_create as *mut c_void,
        },
        jni::NativeMethod {
            name: "init".into(),
            sig: "()V".into(),
            fn_ptr: native_init as *mut c_void,
        },
        jni::NativeMethod {
            name: "destroy".into(),
            sig: "()V".into(),
            fn_ptr: native_destroy as *mut c_void,
        },
        jni::NativeMethod {
            name: "attachSurface".into(),
            sig: "(Landroid/view/Surface;)V".into(),
            fn_ptr: native_attach_surface as *mut c_void,
        },
        jni::NativeMethod {
            name: "detachSurface".into(),
            sig: "()V".into(),
            fn_ptr: native_detach_surface as *mut c_void,
        },
        jni::NativeMethod {
            name: "redraw".into(),
            sig: "()V".into(),
            fn_ptr: native_redraw as *mut c_void,
        },
        jni::NativeMethod {
            name: "rebuildSurface".into(),
            sig: "()V".into(),
            fn_ptr: native_rebuild_surface as *mut c_void,
        },
        jni::NativeMethod {
            name: "command".into(),
            sig: "([Ljava/lang/String;)V".into(),
            fn_ptr: native_command as *mut c_void,
        },
        jni::NativeMethod {
            name: "setOptionString".into(),
            sig: "(Ljava/lang/String;Ljava/lang/String;)I".into(),
            fn_ptr: native_set_option_string as *mut c_void,
        },
        jni::NativeMethod {
            name: "getPropertyLong".into(),
            sig: "(Ljava/lang/String;)J".into(),
            fn_ptr: native_get_property_long as *mut c_void,
        },
        jni::NativeMethod {
            name: "getPropertyDouble".into(),
            sig: "(Ljava/lang/String;)D".into(),
            fn_ptr: native_get_property_double as *mut c_void,
        },
        jni::NativeMethod {
            name: "getPropertyBoolean".into(),
            sig: "(Ljava/lang/String;)B".into(),
            fn_ptr: native_get_property_boolean as *mut c_void,
        },
        jni::NativeMethod {
            name: "getPropertyString".into(),
            sig: "(Ljava/lang/String;)Ljava/lang/String;".into(),
            fn_ptr: native_get_property_string as *mut c_void,
        },
        jni::NativeMethod {
            name: "setPropertyLong".into(),
            sig: "(Ljava/lang/String;J)V".into(),
            fn_ptr: native_set_property_long as *mut c_void,
        },
        jni::NativeMethod {
            name: "setPropertyDouble".into(),
            sig: "(Ljava/lang/String;D)V".into(),
            fn_ptr: native_set_property_double as *mut c_void,
        },
        jni::NativeMethod {
            name: "setPropertyBoolean".into(),
            sig: "(Ljava/lang/String;Z)V".into(),
            fn_ptr: native_set_property_boolean as *mut c_void,
        },
        jni::NativeMethod {
            name: "setPropertyString".into(),
            sig: "(Ljava/lang/String;Ljava/lang/String;)V".into(),
            fn_ptr: native_set_property_string as *mut c_void,
        },
        jni::NativeMethod {
            name: "observeProperty".into(),
            sig: "(Ljava/lang/String;I)V".into(),
            fn_ptr: native_observe_property as *mut c_void,
        },
        jni::NativeMethod {
            name: "isZeroCopySafe".into(),
            sig: "()Z".into(),
            fn_ptr: native_is_zero_copy_safe as *mut c_void,
        },
        jni::NativeMethod {
            name: "getGpuRenderer".into(),
            sig: "()Ljava/lang/String;".into(),
            fn_ptr: native_get_gpu_renderer as *mut c_void,
        },
        jni::NativeMethod {
            name: "nativeNextEpoch".into(),
            sig: "()J".into(),
            fn_ptr: native_next_epoch as *mut c_void,
        },
        jni::NativeMethod {
            name: "nativeCurrentEpoch".into(),
            sig: "()J".into(),
            fn_ptr: native_current_epoch as *mut c_void,
        },
    ];
    env.register_native_methods(class, &methods)
}

#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: *mut JavaVM, _reserved: *mut c_void) -> jint {
    catch_jni_panic("JNI_OnLoad", jni::sys::JNI_ERR, std::panic::AssertUnwindSafe(|| {
        if !vm.is_null() {
            let _ = G_VM.set(crate::events::VmPtr(vm));
            if let Ok(vm_ref) = unsafe { JavaVM::from_raw(vm as *mut jni::sys::JavaVM) } {
                if let Ok(mut env) = vm_ref.get_env() {
                    cache_class(&mut env);
                    if let Ok(class) = env.find_class(crate::events::GYRO_NATIVE_CLASS) {
                        if let Err(e) = register_gyro_natives(&mut env, &class) {
                            nlog(&format!("FATAL: JNI 动态注册失败: {e}"));
                            return jni::sys::JNI_ERR;
                        }
                        nlog("JNI_OnLoad: 成功动态注册 GyroNative 全部原生方法 (RegisterNatives)");
                    }
                }
            }
        }
        JNI_VERSION_1_6
    }))
}

jni_fn! {
    fn native_create(
        env: JNIEnv,
        _class: JClass,
        appctx: jobject
    ) {
        let mut env = env;
        cache_class(&mut env);
        ensure_render_thread();

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
            let old = take_mpv_handle_for_teardown();
            if !old.is_null() {
                safe_terminate_engine(api, old);
            }
            reset_engine_lifecycle_state();

            let handle = (api.mpv_create)();
            if handle.is_null() { return; }
            {
                let mut guard = G_MPV_LIFECYCLE
                    .write()
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
                G_EVENT_THREAD_EXIT.store(false, Ordering::Release);
                G_MPVP.store(handle, Ordering::Release);
                HANDLE_VALID.store(true, Ordering::Release);
                *guard = handle as usize;
            }

            if let Some(crate::events::VmPtr(vm_ptr)) = G_VM.get() {
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
}

jni_fn! {
    fn native_init(
        env: JNIEnv,
        _class: JClass
    ) {
        let mut env = env;
        cache_class(&mut env);
        let _ = with_api(|api| unsafe {
            let (_guard, handle) = match mpv_handle_guarded() {
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

            if let Some(sender) = render_sender() {
                let _ = sender.send(RenderCommand::NotifyFrame);
            }
        });
    }
}

jni_fn! {
    fn native_destroy(
        _env: JNIEnv,
        _class: JClass
    ) {
        let _ = with_api(|api| unsafe {
            let handle = take_mpv_handle_for_teardown();
            if !handle.is_null() {
                safe_terminate_engine(api, handle);
            }
        });

        if let Some(sender) = render_sender() {
            let _ = sender.send(RenderCommand::Shutdown);
        }
        clear_render_thread();
        reset_engine_lifecycle_state();
    }
}

jni_fn! {
    fn native_attach_surface(
        _env: JNIEnv,
        _class: JClass,
        _surface: jobject
    ) {
        #[cfg(target_os = "android")]
        unsafe {
            if _surface.is_null() { return; }
            let env = _env;
            let raw_env = env.get_native_interface();
            let win = ANativeWindow_fromSurface(raw_env, _surface);
            if win.is_null() {
                nlog("attachSurface: ANativeWindow_fromSurface 返回 null");
                return;
            }
            ANativeWindow_acquire(win);
            window_ref(2, "attachSurface handoff (fromSurface + acquire)");

            ensure_render_thread();
            match render_sender() {
                Some(sender) => {
                    if sender.send(RenderCommand::AttachSurface(NativeWindowPtr(win))).is_err() {
                        nlog("attachSurface: 渲染线程已退出，归还窗口引用");
                        window_ref(-2, "attachSurface(send failed) release handoff");
                        ANativeWindow_release(win);
                        ANativeWindow_release(win);
                    }
                }
                None => {
                    nlog("attachSurface: 渲染线程不存在，归还窗口引用");
                    window_ref(-2, "attachSurface(no render thread) release handoff");
                    ANativeWindow_release(win);
                    ANativeWindow_release(win);
                }
            }
        }
    }
}

jni_fn! {
    fn native_detach_surface(
        _env: JNIEnv,
        _class: JClass
    ) {
        crate::render::detach_surface_sync();
    }
}

jni_fn! {
    fn native_redraw(
        _env: JNIEnv,
        _class: JClass
    ) {
        if let Some(sender) = render_sender() {
            let _ = sender.send(RenderCommand::Redraw);
        }
    }
}

jni_fn! {
    /// Recreates the EGL window surface on the current window.
    ///
    /// Called after the view changed the window's buffer format (8-bit <-> 10-bit) so the surface is
    /// rebuilt with the HDR colourspace matching it. No-op when nothing is attached.
    fn native_rebuild_surface(
        _env: JNIEnv,
        _class: JClass
    ) {
        if let Some(sender) = render_sender() {
            let _ = sender.send(RenderCommand::RebuildSurface);
        }
    }
}

jni_fn! {
    fn native_command(
        env: JNIEnv,
        _class: JClass,
        args: JObjectArray
    ) {
        let mut env = env;
        let _ = with_api(|api| unsafe {
            let count = env.get_array_length(&args).unwrap_or(0);
            if count <= 0 {
                return;
            }

            let (_guard, handle) = match mpv_handle_guarded() {
                Ok(h) => h,
                Err(_) => return,
            };

            const STACK_MAX: usize = 8;
            if (count as usize) <= STACK_MAX {
                // 栈快速路径：针对绝大多数 mpv 控制命令（参数 <= 8）实现零堆内存申请
                let mut c_args_buf: [Option<CString>; STACK_MAX] = Default::default();
                let mut ptrs_buf: [*mut c_char; STACK_MAX + 1] = [std::ptr::null_mut(); STACK_MAX + 1];

                for i in 0..count {
                    let item = match env.get_object_array_element(&args, i) {
                        Ok(o) => JString::from(o),
                        Err(_) => return,
                    };
                    let s: String = match env.get_string(&item) {
                        Ok(s) => s.to_string_lossy().into_owned(),
                        Err(_) => return,
                    };
                    let _ = env.delete_local_ref(item);

                    match CString::new(s) {
                        Ok(c) => {
                            ptrs_buf[i as usize] = c.as_ptr() as *mut c_char;
                            c_args_buf[i as usize] = Some(c);
                        }
                        Err(e) => {
                            log_error!("native_command: 参数 [{i}] 含有空字节导致 CString 构造失败: {e}");
                            return;
                        }
                    }
                }

                let rc = (api.mpv_command)(handle, ptrs_buf.as_mut_ptr());
                if rc < 0 {
                    nlog(&format!("mpv_command 失败: rc={rc}"));
                }
            } else {
                // 兜底路径：超长参数回退到动态 Vec 分配
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
                    let _ = env.delete_local_ref(item);

                    match CString::new(s) {
                        Ok(c) => {
                            c_args.push(c);
                        }
                        Err(e) => {
                            log_error!("native_command (long): 参数 [{i}] 含有空字节导致 CString 构造失败: {e}");
                            return;
                        }
                    }
                }
                let mut ptrs: Vec<*mut c_char> = c_args.iter().map(|s| s.as_ptr() as *mut c_char).collect();
                ptrs.push(std::ptr::null_mut());
                let rc = (api.mpv_command)(handle, ptrs.as_mut_ptr());
                if rc < 0 {
                    nlog(&format!("mpv_command (long) 失败: rc={rc}"));
                }
            }
        });
    }
}

jni_fn! {
    fn native_set_option_string(
        env: JNIEnv,
        _class: JClass,
        name: JString,
        value: JString
    ) -> jint, default = -1; {
        let mut env = env;
        let result = (|| -> Result<i32, String> {
            let n = jstr_to_cstring(&mut env, &name)?;
            let v = jstr_to_cstring(&mut env, &value)?;
            let n_str = n.to_str().unwrap_or("");
            let v_str = v.to_str().unwrap_or("");
            if n_str == "hdr-output" {
                let enable_hdr = v_str == "yes" || v_str == "auto" || v_str == "true" || v_str == "1";
                crate::render::set_hdr_output(enable_hdr);
                return Ok(0);
            }
            if n_str == "hdr-capable" {
                // Not an mpv option: decides whether the EGL config may be 10-bit, which has to be
                // known before the render surface (and therefore before any file) exists.
                let capable = v_str == "yes" || v_str == "auto" || v_str == "true" || v_str == "1";
                crate::render::set_hdr_capable(capable);
                return Ok(0);
            }
            with_api(|api| unsafe {
                let (_guard, handle) = mpv_handle_guarded()?;
                Ok((api.mpv_set_option_string)(handle, n.as_ptr(), v.as_ptr()))
            })?
        })();
        result.unwrap_or(-1)
    }
}

#[inline]
fn with_mpv_property<R: Copy>(
    env: &mut JNIEnv,
    name: &JString,
    default: R,
    op: impl FnOnce(&crate::mpv::MpvApi, *mut c_void, &CStr) -> Result<R, String>,
) -> R {
    let res = with_property_cstr(env, name, |n| {
        with_api(|api| unsafe {
            let (_guard, handle) = mpv_handle_guarded()?;
            op(api, handle, n)
        })?
    });
    res.unwrap_or(default)
}

jni_fn! {
    fn native_get_property_long(
        env: JNIEnv,
        _class: JClass,
        name: JString
    ) -> jlong, default = i64::MIN; {
        let mut env = env;
        with_mpv_property(&mut env, &name, i64::MIN, |api, handle, n| unsafe {
            let mut out: i64 = i64::MIN;
            let rc = (api.mpv_get_property)(handle, n.as_ptr(), MPV_FORMAT_INT64, std::ptr::from_mut(&mut out).cast::<c_void>());
            if rc == MPV_ERROR_SUCCESS { Ok(out) } else { Err("get failed".into()) }
        })
    }
}

jni_fn! {
    fn native_get_property_double(
        env: JNIEnv,
        _class: JClass,
        name: JString
    ) -> jdouble, default = f64::NAN; {
        let mut env = env;
        with_mpv_property(&mut env, &name, f64::NAN, |api, handle, n| unsafe {
            let mut out: f64 = f64::NAN;
            let rc = (api.mpv_get_property)(handle, n.as_ptr(), MPV_FORMAT_DOUBLE, std::ptr::from_mut(&mut out).cast::<c_void>());
            if rc == MPV_ERROR_SUCCESS { Ok(out) } else { Err("get failed".into()) }
        })
    }
}

jni_fn! {
    fn native_get_property_boolean(
        env: JNIEnv,
        _class: JClass,
        name: JString
    ) -> jboolean, default = 2u8; {
        let mut env = env;
        with_mpv_property(&mut env, &name, 2u8, |api, handle, n| unsafe {
            let mut out: c_int = 0;
            let rc = (api.mpv_get_property)(handle, n.as_ptr(), MPV_FORMAT_FLAG, std::ptr::from_mut(&mut out).cast::<c_void>());
            if rc == MPV_ERROR_SUCCESS { Ok((out != 0) as u8) } else { Err("get failed".into()) }
        })
    }
}

jni_fn! {
    fn native_get_property_string(
        env: JNIEnv,
        _class: JClass,
        name: JString
    ) -> jobject, default = std::ptr::null_mut(); {
        let mut env = env;
        let result = (|| -> Result<jobject, String> {
            let s: String = with_property_cstr(&mut env, &name, |n| {
                with_api(|api| -> Result<String, String> {
                    unsafe {
                        let (_guard, handle) = mpv_handle_guarded()?;
                        let mut out: *mut c_char = std::ptr::null_mut();
                        let rc = (api.mpv_get_property)(handle, n.as_ptr(), MPV_FORMAT_STRING, std::ptr::from_mut(&mut out).cast::<c_void>());
                        if rc != MPV_ERROR_SUCCESS || out.is_null() { return Err("get failed".into()); }
                        let s = CStr::from_ptr(out).to_string_lossy().into_owned();
                        (api.mpv_free)(out as *mut c_void);
                        Ok(s)
                    }
                })?
            })?;
            let j = env.new_string(s).map_err(|_| "new_string failed")?;
            Ok(j.into_raw())
        })();
        result.unwrap_or(std::ptr::null_mut())
    }
}

jni_fn! {
    fn native_set_property_long(
        env: JNIEnv,
        _class: JClass,
        name: JString,
        value: jlong
    ) {
        let mut env = env;
        with_mpv_property(&mut env, &name, (), |api, handle, n| unsafe {
            let mut v: i64 = value;
            (api.mpv_set_property)(handle, n.as_ptr(), MPV_FORMAT_INT64, std::ptr::from_mut(&mut v).cast::<c_void>());
            Ok(())
        });
    }
}

jni_fn! {
    fn native_set_property_double(
        env: JNIEnv,
        _class: JClass,
        name: JString,
        value: jdouble
    ) {
        let mut env = env;
        with_mpv_property(&mut env, &name, (), |api, handle, n| unsafe {
            let mut v: f64 = value;
            (api.mpv_set_property)(handle, n.as_ptr(), MPV_FORMAT_DOUBLE, std::ptr::from_mut(&mut v).cast::<c_void>());
            Ok(())
        });
    }
}

jni_fn! {
    fn native_set_property_boolean(
        env: JNIEnv,
        _class: JClass,
        name: JString,
        value: jboolean
    ) {
        let mut env = env;
        with_mpv_property(&mut env, &name, (), |api, handle, n| unsafe {
            let mut v: c_int = (value != 0) as c_int;
            (api.mpv_set_property)(handle, n.as_ptr(), MPV_FORMAT_FLAG, std::ptr::from_mut(&mut v).cast::<c_void>());
            Ok(())
        });
    }
}

jni_fn! {
    fn native_set_property_string(
        env: JNIEnv,
        _class: JClass,
        name: JString,
        value: JString
    ) {
        let mut env = env;
        let _ = (|| -> Result<(), String> {
            let n_str_res = (|| -> Result<String, String> {
                let n = jstr_to_cstring(&mut env, &name)?;
                Ok(n.to_str().unwrap_or("").to_string())
            })();
            let v = jstr_to_cstring(&mut env, &value)?;
            if let Ok(ref n_str) = n_str_res {
                if n_str == "hdr-output" {
                    let v_str = v.to_str().unwrap_or("");
                    let enable_hdr = v_str == "yes" || v_str == "auto" || v_str == "true" || v_str == "1";
                    crate::render::set_hdr_output(enable_hdr);
                }
            }
            with_property_cstr(&mut env, &name, |n| {
                with_api(|api| unsafe {
                    let (_guard, handle) = mpv_handle_guarded()?;
                    let mut value_ptr: *const c_char = v.as_ptr();
                    (api.mpv_set_property)(handle, n.as_ptr(), MPV_FORMAT_STRING, std::ptr::from_mut(&mut value_ptr).cast::<c_void>());
                    Ok(())
                })?
            })
        })();
    }
}

jni_fn! {
    fn native_observe_property(
        env: JNIEnv,
        _class: JClass,
        name: JString,
        format: jint
    ) {
        let mut env = env;
        with_mpv_property(&mut env, &name, (), |api, handle, n| unsafe {
            (api.mpv_observe_property)(handle, 0, n.as_ptr(), format);
            Ok(())
        });
    }
}

jni_fn! {
    fn native_is_zero_copy_safe(
        _env: JNIEnv,
        _class: JClass
    ) -> jboolean, default = 0; {
        G_ZERO_COPY_SAFE.load(Ordering::Acquire) as jboolean
    }
}

jni_fn! {
    fn native_get_gpu_renderer(
        env: JNIEnv,
        _class: JClass
    ) -> jobject, default = std::ptr::null_mut(); {
        let renderer = G_GPU_RENDERER.get().map(|s| s.as_str()).unwrap_or("unknown");
        match env.new_string(renderer) {
            Ok(j) => j.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }
}

jni_fn! {
    fn native_next_epoch(
        _env: JNIEnv,
        _class: JClass
    ) -> jlong, default = 0; {
        crate::events::next_epoch() as jlong
    }
}

jni_fn! {
    fn native_current_epoch(
        _env: JNIEnv,
        _class: JClass
    ) -> jlong, default = 0; {
        crate::events::current_epoch() as jlong
    }
}
