//! Kotlin 回调桥与 mpv 事件泵线程

#![cfg_attr(not(target_os = "android"), allow(dead_code, unused_imports))]

use jni::objects::{GlobalRef, JClass, JStaticMethodID, JValue};
use jni::signature::{Primitive, ReturnType};
use jni::sys::jvalue;
use jni::{JNIEnv, JavaVM};
use std::ffi::{c_char, c_int, c_void, CStr, CString};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
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
use crate::render::{G_SESSION, G_RENDER_EPOCH};
use crate::{monotonic_ms, nlog};

pub const GYRO_NATIVE_CLASS: &str = "dev/gyro/engine/GyroNative";
static G_CLASS: OnceLock<GlobalRef> = OnceLock::new();

pub struct CachedCallbacks {
    pub on_event: JStaticMethodID,
    pub on_end_file: JStaticMethodID,
    pub on_property_none: JStaticMethodID,
    pub on_property_flag: JStaticMethodID,
    pub on_property_long: JStaticMethodID,
    pub on_property_double: JStaticMethodID,
    pub on_property_string: JStaticMethodID,
}

static G_CALLBACKS: OnceLock<CachedCallbacks> = OnceLock::new();

pub static G_SESSION_EPOCH: AtomicU64 = AtomicU64::new(0);

pub fn current_epoch() -> u64 {
    G_SESSION_EPOCH.load(Ordering::Acquire)
}

pub fn next_epoch() -> u64 {
    G_SESSION_EPOCH.fetch_add(1, Ordering::SeqCst) + 1
}

pub struct VmPtr(pub *mut JavaVM);
unsafe impl Send for VmPtr {}
unsafe impl Sync for VmPtr {}

pub static G_VM: OnceLock<VmPtr> = OnceLock::new();
pub static G_APP_CTX: OnceLock<GlobalRef> = OnceLock::new();

pub static EVENT_THREAD: Mutex<Option<JoinHandle<()>>> = Mutex::new(None);
pub static G_EVENT_THREAD_EXIT: AtomicBool = AtomicBool::new(false);

// ---------- 帧健康监测 (Frame Health) 与事件节流 ----------
/// Event-pump-side per-session state. Grouped so `reset_engine_lifecycle_state` can clear the
/// whole set with one `reset()` call instead of hand-enumerating each mutex: a newly added field
/// sits directly above `reset()` in the same `impl` block, and the round-trip test in `lib.rs`
/// asserts every field returns to its session-start value.
pub struct EventPumpState {
    pub frame_health: Mutex<FrameHealthMonitor>,
    pub time_pos_throttle: Mutex<SampleThrottle>,
    pub cache_time_throttle: Mutex<SampleThrottle>,
}

impl Default for EventPumpState {
    fn default() -> Self {
        Self::new()
    }
}

impl EventPumpState {
    pub const fn new() -> Self {
        Self {
            frame_health: Mutex::new(FrameHealthMonitor::with_defaults()),
            time_pos_throttle: Mutex::new(SampleThrottle::with_defaults()),
            cache_time_throttle: Mutex::new(SampleThrottle::with_defaults()),
        }
    }

    pub fn reset(&self) {
        if let Ok(mut health) = self.frame_health.lock() {
            *health = FrameHealthMonitor::with_defaults();
        }
        if let Ok(mut tp) = self.time_pos_throttle.lock() {
            *tp = SampleThrottle::with_defaults();
        }
        if let Ok(mut ct) = self.cache_time_throttle.lock() {
            *ct = SampleThrottle::with_defaults();
        }
    }
}

pub static G_EVENT_PUMP: EventPumpState = EventPumpState::new();

pub fn cache_class(env: &mut JNIEnv) {
    if G_CLASS.get().is_some() {
        return;
    }
    if let Ok(class) = env.find_class(GYRO_NATIVE_CLASS) {
        if G_CALLBACKS.get().is_none() {
            if let (Ok(on_event), Ok(on_end_file), Ok(on_none), Ok(on_flag), Ok(on_long), Ok(on_double), Ok(on_str)) = (
                env.get_static_method_id(&class, "onEvent", "(IJ)V"),
                env.get_static_method_id(&class, "onEndFile", "(IJ)V"),
                env.get_static_method_id(&class, "onPropertyNone", "(Ljava/lang/String;J)V"),
                env.get_static_method_id(&class, "onPropertyFlag", "(Ljava/lang/String;ZJ)V"),
                env.get_static_method_id(&class, "onPropertyLong", "(Ljava/lang/String;JJ)V"),
                env.get_static_method_id(&class, "onPropertyDouble", "(Ljava/lang/String;DJ)V"),
                env.get_static_method_id(&class, "onPropertyString", "(Ljava/lang/String;Ljava/lang/String;J)V"),
            ) {
                let _ = G_CALLBACKS.set(CachedCallbacks {
                    on_event,
                    on_end_file,
                    on_property_none: on_none,
                    on_property_flag: on_flag,
                    on_property_long: on_long,
                    on_property_double: on_double,
                    on_property_string: on_str,
                });
                nlog("cache_class: 成功预缓存 7 个核心 JNI 静态回调方法 ID");
            }
        }
        if let Ok(global) = env.new_global_ref(class) {
            let _ = G_CLASS.set(global);
        }
    }
}

#[inline]
pub fn call_cached(env: &mut JNIEnv, method_id: JStaticMethodID, args: &[jvalue]) {
    let class = match G_CLASS.get() {
        Some(global) => unsafe { JClass::from_raw(global.as_obj().as_raw().cast()) },
        None => match env.find_class(GYRO_NATIVE_CLASS) {
            Ok(c) => c,
            Err(_) => return,
        },
    };
    let res = unsafe {
        env.call_static_method_unchecked(
            class,
            method_id,
            ReturnType::Primitive(Primitive::Void),
            args,
        )
    };
    if res.is_err() {
        let _ = env.exception_clear();
    }
}

#[allow(dead_code)]
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
        "time-pos" => &G_EVENT_PUMP.time_pos_throttle,
        _ => &G_EVENT_PUMP.cache_time_throttle,
    };
    let now_ms = monotonic_ms();
    match throttle.lock() {
        Ok(mut throttle) => throttle.should_emit(now_ms),
        Err(_) => true,
    }
}

fn record_frame_counter(counter: FrameCounter, total: i64) {
    let now_ms = monotonic_ms();
    let Ok(mut monitor) = G_EVENT_PUMP.frame_health.lock() else { return };
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

pub fn update_cached_video_geometry(name: &str, value: i64) {
    match name {
        "dwidth" | "video-out-params/dw" => {
            if value > 0 {
                G_SESSION.cached_dwidth.store(value, Ordering::Release);
            }
        }
        "width" => {
            if value > 0 && G_SESSION.cached_dwidth.load(Ordering::Acquire) <= 0 {
                G_SESSION.cached_dwidth.store(value, Ordering::Release);
            }
        }
        "dheight" | "video-out-params/dh" => {
            if value > 0 {
                G_SESSION.cached_dheight.store(value, Ordering::Release);
            }
        }
        "height" => {
            if value > 0 && G_SESSION.cached_dheight.load(Ordering::Acquire) <= 0 {
                G_SESSION.cached_dheight.store(value, Ordering::Release);
            }
        }
        "video-params/rotate" | "video-out-params/rotate" => {
            G_SESSION.cached_rotate.store(value, Ordering::Release);
        }
        _ => {}
    }
}

#[inline]
pub fn emit_event(env: &mut JNIEnv, event_id: i32, epoch: i64) {
    if let Some(cb) = G_CALLBACKS.get() {
        let args = [jvalue { i: event_id }, jvalue { j: epoch }];
        call_cached(env, cb.on_event, &args);
    }
}

#[inline]
pub fn emit_end_file(env: &mut JNIEnv, reason: i32, epoch: i64) {
    if let Some(cb) = G_CALLBACKS.get() {
        let args = [jvalue { i: reason }, jvalue { j: epoch }];
        call_cached(env, cb.on_end_file, &args);
    }
}

/// 拦截并消费 Native 内部关心的属性变更（几何尺寸、监控计数器等）
#[inline]
fn intercept_internal_properties(name: &str, format: c_int, data: *mut c_void) {
    if format == MPV_FORMAT_INT64 && !data.is_null() {
        let v = unsafe { *(data as *const i64) };
        update_cached_video_geometry(name, v);
        if let Some(counter) = FrameCounter::from_property(name) {
            record_frame_counter(counter, v);
        }
    }
}

/// 纯粹的 JNI 属性回调派发（单向序列化与事件传递，严格走编译期/初始化缓存的 MethodID）
fn dispatch_property_to_jni(
    env: &mut JNIEnv,
    name: &str,
    format: c_int,
    data: *mut c_void,
    epoch: i64,
) {
    let Some(cb) = G_CALLBACKS.get() else { return };
    let Ok(jname) = env.new_string(name) else { return };
    if data.is_null() {
        let args = [jvalue { l: jname.as_raw() }, jvalue { j: epoch }];
        call_cached(env, cb.on_property_none, &args);
        let _ = env.delete_local_ref(jname);
        return;
    }

    unsafe {
        match format {
            MPV_FORMAT_FLAG => {
                let v = *(data as *const c_int) != 0;
                let args = [jvalue { l: jname.as_raw() }, jvalue { z: v as u8 }, jvalue { j: epoch }];
                call_cached(env, cb.on_property_flag, &args);
            }
            MPV_FORMAT_INT64 => {
                let v = *(data as *const i64);
                let args = [jvalue { l: jname.as_raw() }, jvalue { j: v }, jvalue { j: epoch }];
                call_cached(env, cb.on_property_long, &args);
            }
            MPV_FORMAT_DOUBLE => {
                let v = *(data as *const f64);
                let args = [jvalue { l: jname.as_raw() }, jvalue { d: v }, jvalue { j: epoch }];
                call_cached(env, cb.on_property_double, &args);
            }
            MPV_FORMAT_STRING => {
                let str_ptr = *(data as *const *const c_char);
                if !str_ptr.is_null() {
                    let s = CStr::from_ptr(str_ptr).to_string_lossy();
                    if let Ok(jv) = env.new_string(s.as_ref()) {
                        let args = [jvalue { l: jname.as_raw() }, jvalue { l: jv.as_raw() }, jvalue { j: epoch }];
                        call_cached(env, cb.on_property_string, &args);
                        let _ = env.delete_local_ref(jv);
                    }
                }
            }
            _ => {
                let args = [jvalue { l: jname.as_raw() }, jvalue { j: epoch }];
                call_cached(env, cb.on_property_none, &args);
            }
        }
    }
    let _ = env.delete_local_ref(jname);
}

fn dispatch_property(env: &mut JNIEnv, name: &str, format: c_int, data: *mut c_void) {
    intercept_internal_properties(name, format, data);

    if is_throttled_property(name) && !throttle_allows(name) {
        return;
    }

    let epoch = current_epoch() as i64;
    dispatch_property_to_jni(env, name, format, data, epoch);
}

pub fn signal_first_frame() {
    let current = current_epoch();
    let frame_epoch = G_RENDER_EPOCH.load(Ordering::Acquire);
    if frame_epoch < current {
        nlog(&format!("RENDER: 丢弃过期首帧信号 (frame_epoch={frame_epoch} < current={current})"));
        return;
    }
    if !G_SESSION.file_loaded_reached.load(Ordering::Acquire) {
        return;
    }
    if !G_SESSION.awaiting_first_frame.swap(false, Ordering::AcqRel) {
        return;
    }
    let waited_ms =
        (monotonic_ms() as u64).saturating_sub(G_SESSION.load_started_ms.load(Ordering::Relaxed));
    crate::tracelog(
        "NATIVE_FIRST_FRAME_SIGNAL",
        &format!("waited_from_start={waited_ms}ms, epoch={current}"),
    );
    nlog(&format!("RENDER: 首帧已上屏（start-file 之后 {waited_ms}ms, epoch={current}）"));

    let Some(VmPtr(vm_ptr)) = G_VM.get() else { return };
    let Ok(vm) = (unsafe { JavaVM::from_raw(vm_ptr.cast()) }) else { return };
    let Ok(mut guard) = vm.attach_current_thread_as_daemon() else { return };
    emit_event(&mut guard, JAVA_EVENT_FIRST_FRAME, current as i64);
}

pub fn run_mpv_command(args: &[&str]) -> Option<c_int> {
    with_api(|api| {
        let (_guard, handle) = unsafe { mpv_handle_guarded() }.ok()?;
        let owned: Vec<CString> = args.iter().map(|a| CString::new(*a).unwrap_or_default()).collect();
        let total = owned.len() + 1;
        if total <= 8 {
            let mut stack_ptrs: [*mut c_char; 8] = [std::ptr::null_mut(); 8];
            for (i, c) in owned.iter().enumerate() {
                stack_ptrs[i] = c.as_ptr() as *mut c_char;
            }
            Some(unsafe { (api.mpv_command)(handle, stack_ptrs.as_mut_ptr()) })
        } else {
            let mut arg_ptrs: Vec<*mut c_char> = owned.iter().map(|c| c.as_ptr() as *mut c_char).collect();
            arg_ptrs.push(std::ptr::null_mut());
            Some(unsafe { (api.mpv_command)(handle, arg_ptrs.as_mut_ptr()) })
        }
    })
    .ok()
    .flatten()
}

pub fn rearm_video_output() -> bool {
    let run_cmd = |mut args: [*mut c_char; 4]| -> Option<c_int> {
        with_api(|api| {
            let (_guard, handle) = unsafe { mpv_handle_guarded() }.ok()?;
            Some(unsafe { (api.mpv_command)(handle, args.as_mut_ptr()) })
        })
        .ok()
        .flatten()
    };

    let set = c"set".as_ptr() as *mut c_char;
    let vid = c"vid".as_ptr() as *mut c_char;
    let no = c"no".as_ptr() as *mut c_char;
    let auto = c"auto".as_ptr() as *mut c_char;

    run_cmd([set, vid, no, std::ptr::null_mut()]);
    let restored = run_cmd([set, vid, auto, std::ptr::null_mut()]);
    if restored != Some(MPV_ERROR_SUCCESS) {
        nlog(&format!("EGL: 重新启用视频轨失败 rc={restored:?}"));
    }
    restored == Some(MPV_ERROR_SUCCESS)
}

fn get_mpv_string(name: &str) -> Option<String> {
    with_api(|api| {
        let (_guard, handle) = unsafe { mpv_handle_guarded() }.ok()?;
        let name = CString::new(name).ok()?;
        let mut out: *mut c_char = std::ptr::null_mut();
        unsafe {
            let rc = (api.mpv_get_property)(handle, name.as_ptr(), MPV_FORMAT_STRING, std::ptr::from_mut(&mut out).cast::<c_void>());
            if rc != MPV_ERROR_SUCCESS || out.is_null() {
                return None;
            }
            let s = CStr::from_ptr(out).to_string_lossy().into_owned();
            (api.mpv_free)(out as *mut c_void);
            Some(s)
        }
    })
    .ok()
    .flatten()
}

#[allow(dead_code)]
fn get_mpv_flag(name: &str) -> Option<bool> {
    with_api(|api| {
        let (_guard, handle) = unsafe { mpv_handle_guarded() }.ok()?;
        let name = CString::new(name).ok()?;
        let mut out: c_int = 0;
        unsafe {
            let rc = (api.mpv_get_property)(handle, name.as_ptr(), MPV_FORMAT_FLAG, std::ptr::from_mut(&mut out).cast::<c_void>());
            if rc != MPV_ERROR_SUCCESS {
                return None;
            }
            Some(out != 0)
        }
    })
    .ok()
    .flatten()
}

/// Whether the current file carries at least one video track. Distinguishes a VO failure
/// (`video == "no"` despite a video track existing) from a genuine audio-only file.
fn mpv_has_video_track() -> bool {
    let count = with_api(|api| {
        let (_guard, handle) = unsafe { mpv_handle_guarded() }.ok()?;
        let name = CString::new("track-list/count").ok()?;
        let mut out: i64 = 0;
        unsafe {
            let rc = (api.mpv_get_property)(handle, name.as_ptr(), MPV_FORMAT_INT64, std::ptr::from_mut(&mut out).cast::<c_void>());
            if rc != MPV_ERROR_SUCCESS {
                return None;
            }
            Some(out)
        }
    })
    .ok()
    .flatten()
    .unwrap_or(0);
    (0..count).any(|i| get_mpv_string(&format!("track-list/{i}/type")).as_deref() == Some("video"))
}

/// Evaluates [`VideoOutputRecoveryGate`] against live mpv state and re-arms the video track when
/// a loadfile raced ahead of the render context. Called from the event thread only.
fn maybe_rearm_video_output() {
    if !G_SESSION.file_loaded_reached.load(Ordering::Acquire) {
        return;
    }
    let render_ready = G_SESSION.render_slot_ready.load(Ordering::Acquire);
    let video_disabled = get_mpv_string("video").as_deref() == Some("no");
    let has_video_track = mpv_has_video_track();
    if crate::logic::VideoOutputRecoveryGate::should_rearm(
        true,
        render_ready,
        video_disabled,
        has_video_track,
    ) {
        nlog("RECOVERY: mpv 在渲染上下文就绪前放弃了视频输出（Video: no video），重挂视频轨");
        rearm_video_output();
    } else {
        nlog(&format!(
            "RECOVERY: VO 检查无需重挂 (render_ready={render_ready}, video_disabled={video_disabled}, has_video_track={has_video_track})"
        ));
    }
}

pub fn event_thread() {
    nlog("event_thread: 启动");
    let VmPtr(vm_ptr) = match G_VM.get() {
        Some(p) => p,
        None => return,
    };
    let Ok(vm) = (unsafe { JavaVM::from_raw(vm_ptr.cast()) }) else { return };
    let Ok(mut guard) = vm.attach_current_thread_as_daemon() else { return };
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
        if G_SESSION.rearm_video.swap(false, Ordering::AcqRel) {
            nlog("event_thread: 渲染线程请求重新启用视频输出");
            rearm_video_output();
        }
        if G_SESSION.pending_vo_check.swap(false, Ordering::AcqRel) {
            maybe_rearm_video_output();
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
                    let epoch = current_epoch() as i64;
                    emit_event(env, MPV_EVENT_SHUTDOWN, epoch);
                    break;
                }
                MPV_EVENT_START_FILE => {
                    crate::tracelog("NATIVE_MPV_START_FILE", "");
                    let epoch = current_epoch();
                    G_SESSION.load_started_ms.store(monotonic_ms() as u64, Ordering::Relaxed);
                    G_SESSION.cached_dwidth.store(0, Ordering::Release);
                    G_SESSION.cached_dheight.store(0, Ordering::Release);
                    G_SESSION.cached_rotate.store(0, Ordering::Release);
                    G_SESSION.file_loaded_reached.store(false, Ordering::Release);
                    G_SESSION.awaiting_first_frame.store(true, Ordering::Release);
                    G_SESSION.hdr_output.store(false, Ordering::Release);
                    crate::render::G_RENDER_EPOCH.store(epoch, Ordering::Release);
                }
                MPV_EVENT_FILE_LOADED => {
                    crate::tracelog("NATIVE_MPV_FILE_LOADED", "");
                    G_SESSION.file_loaded_reached.store(true, Ordering::Release);
                    nlog("event_thread: 收到 MPV_EVENT_FILE_LOADED 事件");
                    // The loadfile may have raced ahead of the render context: mpv initializes
                    // its VO during load and permanently disables video when none exists yet.
                    // If the render context landed after that failure, nothing else will retry.
                    maybe_rearm_video_output();
                    let epoch = current_epoch() as i64;
                    emit_event(env, event_id, epoch);
                }
                MPV_EVENT_END_FILE => {
                    let reason = if !(*ev).data.is_null() {
                        let end_data = &*((*ev).data as *const MpvEventEndFile);
                        end_data.reason
                    } else {
                        MPV_END_FILE_REASON_EOF
                    };
                    nlog(&format!("event_thread: 收到 MPV_EVENT_END_FILE, reason={reason}"));
                    let epoch = current_epoch() as i64;
                    // Reason-bearing delivery for every END_FILE (EOF, STOP, ERROR, QUIT).
                    emit_end_file(env, reason, epoch);
                }
                MPV_EVENT_PLAYBACK_RESTART => {
                    nlog(&format!("event_thread: 收到事件 event_id={event_id}"));
                    let epoch = current_epoch() as i64;
                    emit_event(env, event_id, epoch);
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
