//! Kotlin 回调桥与 mpv 事件泵线程

#![cfg_attr(not(target_os = "android"), allow(dead_code, unused_imports))]

use jni::objects::{GlobalRef, JClass, JValue};
use jni::{JNIEnv, JavaVM};
use std::ffi::{c_char, c_int, c_void, CStr, CString};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, OnceLock};
use std::thread::JoinHandle;

use crate::logic::{FrameCounter, FrameHealthMonitor, SampleThrottle};
use crate::mpv::{
    mpv_handle, mpv_handle_guarded, with_api, HANDLE_VALID, JAVA_EVENT_FIRST_FRAME,
    MPV_END_FILE_REASON_EOF, MPV_ERROR_SUCCESS, MPV_EVENT_END_FILE, MPV_EVENT_FILE_LOADED,
    MPV_EVENT_LOG_MESSAGE, MPV_EVENT_PLAYBACK_RESTART, MPV_EVENT_PROPERTY_CHANGE,
    MPV_EVENT_SHUTDOWN, MPV_EVENT_START_FILE, MPV_FORMAT_DOUBLE, MPV_FORMAT_FLAG,
    MPV_FORMAT_INT64, MPV_FORMAT_STRING, MpvApi,
};
use crate::render::{
    G_AWAITING_FIRST_FRAME, G_FILE_LOADED_REACHED, G_LOAD_STARTED_MS, G_REARM_VIDEO,
};
use crate::{monotonic_ms, nlog};

const GYRO_NATIVE_CLASS: &str = "dev/gyro/engine/GyroNative";
static G_CLASS: OnceLock<GlobalRef> = OnceLock::new();

pub struct VmPtr(pub *mut JavaVM);
unsafe impl Send for VmPtr {}
unsafe impl Sync for VmPtr {}

pub static G_VM: OnceLock<VmPtr> = OnceLock::new();
pub static G_APP_CTX: OnceLock<GlobalRef> = OnceLock::new();

pub static EVENT_THREAD: Mutex<Option<JoinHandle<()>>> = Mutex::new(None);
pub static G_EVENT_THREAD_EXIT: AtomicBool = AtomicBool::new(false);

// ---------- 帧健康监测 (Frame Health) 与事件节流 ----------
pub static G_FRAME_HEALTH: Mutex<FrameHealthMonitor> = Mutex::new(FrameHealthMonitor::with_defaults());
pub static G_TIME_POS_THROTTLE: Mutex<SampleThrottle> = Mutex::new(SampleThrottle::with_defaults());
pub static G_CACHE_TIME_THROTTLE: Mutex<SampleThrottle> = Mutex::new(SampleThrottle::with_defaults());

pub fn cache_class(env: &mut JNIEnv) {
    if G_CLASS.get().is_some() {
        return;
    }
    if let Ok(class) = env.find_class(GYRO_NATIVE_CLASS) {
        if let Ok(global) = env.new_global_ref(class) {
            let _ = G_CLASS.set(global);
        }
    }
}

pub fn call_static(env: &mut JNIEnv, name: &str, sig: &str, args: &[JValue]) {
    let class = match G_CLASS.get() {
        Some(global) => unsafe { JClass::from_raw(global.as_obj().as_raw().cast()) },
        None => match env.find_class(GYRO_NATIVE_CLASS) {
            Ok(c) => c,
            Err(_) => return,
        },
    };
    // A failed call — e.g. NoSuchMethodError for a callback the Kotlin side has not grown yet —
    // leaves the exception pending on this thread, and the very next JNI call here aborts the
    // process ("JNI NewStringUTF called with pending exception"). Clear it so a missing callback
    // really is the silent no-op the callers assume it to be.
    if env.call_static_method(class, name, sig, args).is_err() {
        let _ = env.exception_clear();
    }
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

#[repr(C)]
struct MpvEventEndFile {
    reason: c_int,
    error: c_int,
    playlist_entry_id: i64,
    playlist_insert_id: i64,
    playlist_insert_num_entries: c_int,
}

fn is_throttled_property(name: &str) -> bool {
    matches!(name, "time-pos" | "demuxer-cache-time")
}

fn throttle_allows(name: &str) -> bool {
    let throttle = match name {
        "time-pos" => &G_TIME_POS_THROTTLE,
        _ => &G_CACHE_TIME_THROTTLE,
    };
    let now_ms = monotonic_ms();
    match throttle.lock() {
        Ok(mut throttle) => throttle.should_emit(now_ms),
        Err(_) => true,
    }
}

fn record_frame_counter(counter: FrameCounter, total: i64) {
    let now_ms = monotonic_ms();
    let Ok(mut monitor) = G_FRAME_HEALTH.lock() else { return };
    let Some(report) = monitor.on_counter(counter, total, now_ms) else { return };
    nlog(&format!(
        "HEALTH: 过去 {}s 迟到 {} 帧、错拍 {} 帧、解码器丢弃 {} 帧；显示跳过 {} 帧。累计 迟到={} 错拍={} 解码丢帧={} 显示跳过={}",
        report.window_ms / 1000,
        report.late_frames,
        report.mistimed_frames,
        report.dropped_frames,
        report.skipped_frames,
        monitor.late_frames(),
        monitor.mistimed_frames(),
        monitor.dropped_frames(),
        monitor.skipped_frames()
    ));
}

fn dispatch_property(env: &mut JNIEnv, name: &str, format: c_int, data: *mut c_void) {
    if let Some(counter) = FrameCounter::from_property(name) {
        if format == MPV_FORMAT_INT64 && !data.is_null() {
            record_frame_counter(counter, unsafe { *(data as *const i64) });
        }
    }
    if is_throttled_property(name) && !throttle_allows(name) {
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

pub fn signal_first_frame() {
    if !G_FILE_LOADED_REACHED.load(Ordering::Acquire) {
        return;
    }
    if !G_AWAITING_FIRST_FRAME.swap(false, Ordering::AcqRel) {
        return;
    }
    let waited_ms =
        (monotonic_ms() as u64).saturating_sub(G_LOAD_STARTED_MS.load(Ordering::Relaxed));
    crate::tracelog(
        "NATIVE_FIRST_FRAME_SIGNAL",
        &format!("waited_from_start={waited_ms}ms"),
    );
    nlog(&format!("RENDER: 首帧已上屏（start-file 之后 {waited_ms}ms）"));

    let Some(VmPtr(vm_ptr)) = G_VM.get() else { return };
    let Ok(vm) = (unsafe { JavaVM::from_raw(vm_ptr.cast()) }) else { return };
    let Ok(mut guard) = vm.attach_current_thread() else { return };
    call_static(&mut guard, "onEvent", "(I)V", &[JValue::Int(JAVA_EVENT_FIRST_FRAME)]);
}

pub fn run_mpv_command(args: &[&str]) -> Option<c_int> {
    with_api(|api| {
        let (_guard, handle) = unsafe { mpv_handle_guarded() }.ok()?;
        let owned: Vec<CString> = args.iter().map(|a| CString::new(*a).unwrap_or_default()).collect();
        let mut arg_ptrs: Vec<*mut c_char> = owned.iter().map(|c| c.as_ptr() as *mut c_char).collect();
        arg_ptrs.push(std::ptr::null_mut());
        Some(unsafe { (api.mpv_command)(handle, arg_ptrs.as_mut_ptr()) })
    })
    .ok()
    .flatten()
}

pub fn rearm_video_output() -> bool {
    run_mpv_command(&["set", "vid", "no"]);
    let restored = run_mpv_command(&["set", "vid", "auto"]);
    if restored != Some(MPV_ERROR_SUCCESS) {
        nlog(&format!("EGL: 重新启用视频轨失败 rc={restored:?}"));
    }
    restored == Some(MPV_ERROR_SUCCESS)
}

pub fn event_thread() {
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
        if G_REARM_VIDEO.swap(false, Ordering::AcqRel) {
            nlog("event_thread: 渲染线程请求重新启用视频输出");
            rearm_video_output();
        }
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
                MPV_EVENT_START_FILE => {
                    crate::tracelog("NATIVE_MPV_START_FILE", "");
                    G_LOAD_STARTED_MS.store(monotonic_ms() as u64, Ordering::Relaxed);
                    G_FILE_LOADED_REACHED.store(false, Ordering::Release);
                    G_AWAITING_FIRST_FRAME.store(true, Ordering::Release);
                }
                MPV_EVENT_FILE_LOADED => {
                    crate::tracelog("NATIVE_MPV_FILE_LOADED", "");
                    G_FILE_LOADED_REACHED.store(true, Ordering::Release);
                    nlog("event_thread: 收到 MPV_EVENT_FILE_LOADED 事件");
                    call_static(env, "onEvent", "(I)V", &[JValue::Int(event_id)]);
                }
                MPV_EVENT_END_FILE => {
                    let reason = if !(*ev).data.is_null() {
                        let end_data = &*((*ev).data as *const MpvEventEndFile);
                        end_data.reason
                    } else {
                        MPV_END_FILE_REASON_EOF
                    };
                    nlog(&format!("event_thread: 收到 MPV_EVENT_END_FILE, reason={reason}"));
                    // Legacy path kept while Kotlin migrates: Lifecycle(END_FILE) drives the
                    // ENDED playback state, and only EOF may produce it — STOP is the engine's
                    // own `replace` load tearing down the previous entry.
                    if reason == MPV_END_FILE_REASON_EOF {
                        call_static(env, "onEvent", "(I)V", &[JValue::Int(MPV_EVENT_END_FILE)]);
                    }
                    // Reason-bearing delivery for every END_FILE. mpv rejecting a file outright
                    // (corrupt container, no playable stream) arrives as reason=ERROR and used to
                    // be swallowed here, which left the Kotlin session phase in Submitting with no
                    // terminal state to show. GyroNative only answers once the Kotlin side grows
                    // `onEndFile`; until then call_static no-ops, so this is additive.
                    call_static(env, "onEndFile", "(I)V", &[JValue::Int(reason)]);
                }
                MPV_EVENT_PLAYBACK_RESTART => {
                    nlog(&format!("event_thread: 收到事件 event_id={event_id}"));
                    call_static(env, "onEvent", "(I)V", &[JValue::Int(event_id)]);
                }
                _ => {}
            }
        }
    }
    nlog("event_thread: 已安全退出");
}

pub fn stop_event_thread(api: &MpvApi, handle: *mut c_void) {
    G_EVENT_THREAD_EXIT.store(true, Ordering::Release);
    unsafe { (api.mpv_wakeup)(handle) };

    if let Ok(mut lock) = EVENT_THREAD.lock() {
        if let Some(jh) = lock.take() {
            let _ = jh.join();
            nlog("stop_event_thread: gyro-event 线程已安全 join 退出");
        }
    }
}
