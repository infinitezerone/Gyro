//! JNI 导出函数（供 Kotlin/Java 层 GyroNative 调用）

#![cfg_attr(not(target_os = "android"), allow(dead_code, unused_imports))]

use jni::objects::{JClass, JObject, JObjectArray, JString};
use jni::sys::{jboolean, jdouble, jint, jlong, jobject, JNI_VERSION_1_6};
use jni::{JNIEnv, JavaVM};
use std::ffi::{c_char, c_int, c_void, CStr, CString};
use std::sync::atomic::Ordering;
use std::time::Instant;

use crate::events::{
    cache_class, event_thread, EVENT_THREAD, G_APP_CTX, G_EVENT_THREAD_EXIT, G_VM,
};
use crate::logic::{AckOutcome, FrameCounter, request_ack};
use crate::mpv::{
    mpv_handle_guarded, safe_terminate_mpv, take_mpv_handle_for_teardown, with_api,
    G_MPVP, G_MPV_LIFECYCLE, HANDLE_VALID, MPV_ERROR_SUCCESS, MPV_FORMAT_DOUBLE,
    MPV_FORMAT_FLAG, MPV_FORMAT_INT64, MPV_FORMAT_STRING,
};
use crate::render::{
    clear_render_thread, ensure_render_thread, render_sender, NativeWindowPtr, RenderCommand,
    ATTACH_ACK_TIMEOUT_MS, ATTACH_WAIT_BUDGET_MS, G_GPU_RENDERER, G_ZERO_COPY_SAFE,
};
#[cfg(target_os = "android")]
use crate::render::ANativeWindow_fromSurface;
#[cfg(target_os = "android")]
use crate::render::ANativeWindow_acquire;
#[cfg(target_os = "android")]
use crate::render::ANativeWindow_release;
use crate::{nlog, reset_engine_lifecycle_state};

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
    let owned: String = env.get_string(s).map_err(|_| "get_string failed")?.to_string_lossy().into_owned();
    CString::new(owned).map_err(|_| "interior nul".into())
}

#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: *mut JavaVM, _reserved: *mut c_void) -> jint {
    if !vm.is_null() {
        let _ = G_VM.set(crate::events::VmPtr(vm));
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
            safe_terminate_mpv(api, old);
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

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_init(
    mut env: JNIEnv,
    _class: JClass,
) {
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

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_destroy(
    _env: JNIEnv,
    _class: JClass,
) {
    let _ = with_api(|api| unsafe {
        let handle = take_mpv_handle_for_teardown();
        if !handle.is_null() {
            safe_terminate_mpv(api, handle);
        }
    });

    if let Some(sender) = render_sender() {
        let _ = sender.send(RenderCommand::Shutdown);
    }
    clear_render_thread();
    reset_engine_lifecycle_state();
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
        let env = _env;
        let raw_env = env.get_native_interface();
        let win = ANativeWindow_fromSurface(raw_env, _surface);
        if win.is_null() {
            nlog("attachSurface: ANativeWindow_fromSurface 返回 null");
            return;
        }
        ANativeWindow_acquire(win);

        ensure_render_thread();
        let attach_started = Instant::now();
        match render_sender() {
            Some(sender) => {
                let outcome = request_ack(
                    &sender,
                    |ack| RenderCommand::AttachSurface(NativeWindowPtr(win), ack),
                    std::time::Duration::from_millis(ATTACH_ACK_TIMEOUT_MS),
                );
                if outcome == AckOutcome::Disconnected {
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
        let (_guard, handle) = match mpv_handle_guarded() {
            Ok(h) => h,
            Err(_) => return,
        };
        let rc = (api.mpv_command)(handle, ptrs.as_mut_ptr());
        nlog(&format!("mpv_command: {:?} -> rc={rc}", c_args));
    });
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
            let (_guard, handle) = mpv_handle_guarded()?;
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
    let result = with_property_cstr(&mut env, &name, |n| {
        with_api(|api| unsafe {
            let (_guard, handle) = mpv_handle_guarded()?;
            let mut out: i64 = i64::MIN;
            let rc = (api.mpv_get_property)(handle, n.as_ptr(), MPV_FORMAT_INT64, std::ptr::from_mut(&mut out).cast::<c_void>());
            if rc != MPV_ERROR_SUCCESS { return Err("get failed".into()); }
            Ok(out)
        })?
    });
    result.unwrap_or(i64::MIN)
}

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_getPropertyDouble(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
) -> jdouble {
    let result = with_property_cstr(&mut env, &name, |n| {
        with_api(|api| unsafe {
            let (_guard, handle) = mpv_handle_guarded()?;
            let mut out: f64 = f64::NAN;
            let rc = (api.mpv_get_property)(handle, n.as_ptr(), MPV_FORMAT_DOUBLE, std::ptr::from_mut(&mut out).cast::<c_void>());
            if rc != MPV_ERROR_SUCCESS { return Err("get failed".into()); }
            Ok(out)
        })?
    });
    result.unwrap_or(f64::NAN)
}

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_getPropertyBoolean(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
) -> jboolean {
    let result = with_property_cstr(&mut env, &name, |n| {
        with_api(|api| unsafe {
            let (_guard, handle) = mpv_handle_guarded()?;
            let mut out: c_int = 0;
            let rc = (api.mpv_get_property)(handle, n.as_ptr(), MPV_FORMAT_FLAG, std::ptr::from_mut(&mut out).cast::<c_void>());
            if rc != MPV_ERROR_SUCCESS { return Err("get failed".into()); }
            Ok(out != 0)
        })?
    });
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

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_setPropertyLong(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    value: jlong,
) {
    let _ = with_property_cstr(&mut env, &name, |n| {
        let mut v: i64 = value;
        with_api(|api| unsafe {
            let (_guard, handle) = mpv_handle_guarded()?;
            (api.mpv_set_property)(handle, n.as_ptr(), MPV_FORMAT_INT64, std::ptr::from_mut(&mut v).cast::<c_void>());
            Ok(())
        })?
    });
}

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_setPropertyDouble(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    value: jdouble,
) {
    let _ = with_property_cstr(&mut env, &name, |n| {
        let mut v: f64 = value;
        with_api(|api| unsafe {
            let (_guard, handle) = mpv_handle_guarded()?;
            (api.mpv_set_property)(handle, n.as_ptr(), MPV_FORMAT_DOUBLE, std::ptr::from_mut(&mut v).cast::<c_void>());
            Ok(())
        })?
    });
}

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_setPropertyBoolean(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    value: jboolean,
) {
    let _ = with_property_cstr(&mut env, &name, |n| {
        let mut v: c_int = (value != 0) as c_int;
        with_api(|api| unsafe {
            let (_guard, handle) = mpv_handle_guarded()?;
            (api.mpv_set_property)(handle, n.as_ptr(), MPV_FORMAT_FLAG, std::ptr::from_mut(&mut v).cast::<c_void>());
            Ok(())
        })?
    });
}

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_setPropertyString(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    value: JString,
) {
    let _ = (|| -> Result<(), String> {
        let v = jstr_to_cstring(&mut env, &value)?;
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

#[no_mangle]
pub extern "system" fn Java_dev_gyro_engine_GyroNative_observeProperty(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    format: jint,
) {
    let _ = with_property_cstr(&mut env, &name, |n| {
        with_api(|api| unsafe {
            let (_guard, handle) = mpv_handle_guarded()?;
            (api.mpv_observe_property)(handle, 0, n.as_ptr(), format);
            Ok(())
        })?
    });
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
    env: JNIEnv,
    _class: JClass,
) -> jobject {
    let renderer = G_GPU_RENDERER.get().map(|s| s.as_str()).unwrap_or("unknown");
    match env.new_string(renderer) {
        Ok(j) => j.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
