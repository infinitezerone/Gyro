//! 帧绘制、时间戳锁相、GL Fence 管理与渲染上下文生命周期

#![cfg_attr(not(target_os = "android"), allow(dead_code, unused_variables, unused_imports))]

use std::ffi::{c_char, c_int, c_void, CStr};
use std::sync::atomic::Ordering;
use std::sync::Mutex;
use std::time::Instant;

use super::types::*;
use super::egl::{egl_error_name, egl_failure, EglCore, SurfaceSlot};
use super::thread::assert_render_thread;
use super::{get_requested_vo, G_MPVP, G_RENDER_EPOCH, G_SESSION};
use crate::logic::{ContextRecovery, FirstFrameGate, RecoveryVerdict};
use crate::mpv::{
    mpv_handle_guarded, with_api, with_api_logged, MpvOpenGLFbo, MpvOpenGLInitParams,
    MpvRenderFrameInfo, MpvRenderParam, MPV_ERROR_SUCCESS, MPV_FORMAT_INT64,
    MPV_RENDER_FRAME_INFO_REDRAW, MPV_RENDER_PARAM_API_TYPE, MPV_RENDER_PARAM_BACKEND,
    MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME, MPV_RENDER_PARAM_FLIP_Y,
    MPV_RENDER_PARAM_NEXT_FRAME_INFO, MPV_RENDER_PARAM_OPENGL_FBO,
    MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, MPV_RENDER_PARAM_SKIP_RENDERING, MPV_RENDER_UPDATE_FRAME,
};
use crate::strategy::DrawOutcome;
use crate::{monotonic_ms, monotonic_ns, nlog};

#[derive(Clone, Copy, PartialEq, Eq)]
pub struct SyncHandle(pub *mut c_void);
unsafe impl Send for SyncHandle {}
unsafe impl Sync for SyncHandle {}

pub const MAX_TRACKED_FENCES: usize = 64;

#[cfg(target_os = "android")]
unsafe extern "C" fn wrapped_gl_fence_sync(condition: u32, flags: u32) -> *mut c_void {
    assert_render_thread("glFenceSync");
    let real_fn = G_SESSION.real_gl_fence_sync.load(Ordering::Relaxed);
    if real_fn.is_null() {
        return std::ptr::null_mut();
    }
    let f: unsafe extern "C" fn(u32, u32) -> *mut c_void = std::mem::transmute(real_fn);
    let sync = f(condition, flags);
    if !sync.is_null() {
        if let Ok(mut lock) = G_SESSION.active_fences.lock() {
            lock.push(SyncHandle(sync));
            if lock.len() > MAX_TRACKED_FENCES {
                let old_sync = lock.remove(0);
                let del_fn = G_SESSION.real_gl_delete_sync.load(Ordering::Relaxed);
                if !del_fn.is_null() {
                    let del: unsafe extern "C" fn(*mut c_void) = std::mem::transmute(del_fn);
                    del(old_sync.0);
                }
            }
        }
    }
    sync
}

#[cfg(target_os = "android")]
unsafe extern "C" fn wrapped_gl_delete_sync(sync: *mut c_void) {
    assert_render_thread("glDeleteSync");
    if !sync.is_null() {
        let was_tracked = if let Ok(mut lock) = G_SESSION.active_fences.lock() {
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
            let del_fn = G_SESSION.real_gl_delete_sync.load(Ordering::Relaxed);
            if !del_fn.is_null() {
                let del: unsafe extern "C" fn(*mut c_void) = std::mem::transmute(del_fn);
                del(sync);
            }
        }
    }
}

pub unsafe fn drain_active_fences() {
    assert_render_thread("drain_active_fences");
    let Ok(mut fences) = G_SESSION.active_fences.lock() else { return };
    if fences.is_empty() {
        return;
    }
    let del_fn = G_SESSION.real_gl_delete_sync.load(Ordering::Relaxed);
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

pub(crate) unsafe extern "C" fn gl_proc_address(_ctx: *mut c_void, name: *const c_char) -> *mut c_void {
    #[cfg(target_os = "android")]
    {
        if !name.is_null() {
            let s = CStr::from_ptr(name).to_bytes();
            if s == b"glFenceSync" || s == b"glFenceSyncAPPLE" {
                let real = eglGetProcAddress(name);
                if !real.is_null() {
                    G_SESSION.real_gl_fence_sync.store(real, Ordering::Relaxed);
                    if G_SESSION.real_gl_delete_sync.load(Ordering::Relaxed).is_null() {
                        let del = eglGetProcAddress(b"glDeleteSync\0".as_ptr().cast());
                        if !del.is_null() {
                            G_SESSION.real_gl_delete_sync.store(del, Ordering::Relaxed);
                        }
                    }
                    return wrapped_gl_fence_sync as *mut c_void;
                }
            } else if s == b"glDeleteSync" || s == b"glDeleteSyncAPPLE" {
                let real = eglGetProcAddress(name);
                if !real.is_null() {
                    G_SESSION.real_gl_delete_sync.store(real, Ordering::Relaxed);
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

#[cfg(target_os = "android")]
pub fn get_egl_presentation_time_fn() -> Option<EglPresentationTimeAndroidFn> {
    if !G_SESSION.egl_presentation_resolved.load(Ordering::Acquire) {
        unsafe {
            let proc = eglGetProcAddress(b"eglPresentationTimeANDROID\0".as_ptr().cast());
            if !proc.is_null() {
                G_SESSION.real_egl_presentation_time.store(proc, Ordering::Release);
                nlog("EGL: 成功动态解析 EGL_ANDROID_presentation_time 扩展");
            } else {
                nlog("EGL: 当前驱动未导出 eglPresentationTimeANDROID 扩展");
            }
        }
        G_SESSION.egl_presentation_resolved.store(true, Ordering::Release);
    }
    let p = G_SESSION.real_egl_presentation_time.load(Ordering::Acquire);
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

pub fn compute_monotonic_presentation_ns(
    target_time_mpv_us: i64,
    now_mpv_us: i64,
    now_mono_ns: i64,
) -> Option<i64> {
    if target_time_mpv_us <= 0 || now_mono_ns <= 0 {
        return None;
    }
    let delta_us = target_time_mpv_us.saturating_sub(now_mpv_us);
    if delta_us < -50_000 {
        return None;
    }
    let delta_ns = delta_us.saturating_mul(1_000);
    Some(now_mono_ns.saturating_add(delta_ns))
}

// ---------- 探针与 Pbuffer 目标 ----------

pub(crate) enum ProbeTarget {
    Surfaceless,
    Pbuffer(EGLSurface),
}

#[cfg(target_os = "android")]
impl ProbeTarget {
    pub(crate) unsafe fn release(self, display: EGLDisplay) {
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if let ProbeTarget::Pbuffer(surface) = self {
            if eglDestroySurface(display, surface) != EGL_TRUE {
                egl_failure("eglDestroySurface(probe)");
            }
        }
    }
}

#[cfg(target_os = "android")]
pub(crate) unsafe fn bind_probe_target(display: EGLDisplay, ctx: EGLContext) -> Option<ProbeTarget> {
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

// ---------- 防御层 3：GL 上下文丢失重建 (Context Recovery) ----------

pub static G_RECOVERY: Mutex<ContextRecovery> = Mutex::new(ContextRecovery::with_defaults());

#[cfg(target_os = "android")]
unsafe fn forget_active_fences() {
    if let Ok(mut fences) = G_SESSION.active_fences.lock() {
        if !fences.is_empty() {
            nlog(&format!("EGL: 上下文已丢失，丢弃 {} 个 fence 句柄", fences.len()));
            fences.clear();
        }
    }
}

#[cfg(target_os = "android")]
pub unsafe fn revive_dead_pipeline(core: &mut EglCore, mpv_render_ctx: &mut *mut c_void) -> bool {
    if !G_SESSION.render_dead.load(Ordering::Acquire) && core.context != EGL_NO_CONTEXT {
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
    G_SESSION.render_dead.store(false, Ordering::Release);

    super::thread::request_video_rearm();
    nlog("EGL: 管线已恢复，渲染重新启用");
    true
}

#[cfg(target_os = "android")]
pub unsafe fn recover_gl_context(
    core: &mut EglCore,
    slot: &mut SurfaceSlot,
    mpv_render_ctx: &mut *mut c_void,
) -> bool {
    let verdict = match G_RECOVERY.lock() {
        Ok(mut recovery) => recovery.on_context_lost(),
        Err(_) => RecoveryVerdict::Exhausted,
    };
    if verdict == RecoveryVerdict::Exhausted {
        nlog("EGL: 上下文恢复重试次数耗尽，停用渲染管线");
        G_SESSION.render_dead.store(true, Ordering::Release);
        return false;
    }

    nlog("EGL: 上下文丢失，开始执行原地恢复流程");
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
        nlog("EGL: 重建 EGLContext 失败");
        G_SESSION.render_dead.store(true, Ordering::Release);
        return false;
    }

    if slot.rebuild_surface(core.context, core.context_major).is_err() {
        nlog("EGL: 为新上下文重建 EGLSurface 失败");
        G_SESSION.render_dead.store(true, Ordering::Release);
        return false;
    }

    ensure_render_context(mpv_render_ctx);
    if mpv_render_ctx.is_null() {
        nlog("EGL: 上下文重建后重新初始化 mpv_render_context 失败");
        G_SESSION.render_dead.store(true, Ordering::Release);
        return false;
    }

    super::thread::request_video_rearm();
    nlog("EGL: GL 上下文原地恢复成功");
    true
}

// ---------- 渲染上下文生命周期 ----------

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
pub unsafe fn ensure_render_context(mpv_render_ctx: &mut *mut c_void) {
    if !mpv_render_ctx.is_null() {
        return;
    }
    assert_render_thread("ensure_render_context");
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

        let mut backend_name = [
            b'g' as c_char, b'p' as c_char, b'u' as c_char, b'-' as c_char,
            b'n' as c_char, b'e' as c_char, b'x' as c_char, b't' as c_char, 0,
        ];

        let requested_vo = get_requested_vo();
        let use_gpu_next = requested_vo == "gpu-next";
        let mut rc = -1;

        if use_gpu_next {
            let mut params_gpu_next = [
                MpvRenderParam {
                    param_type: MPV_RENDER_PARAM_API_TYPE,
                    data: api_type.as_mut_ptr() as *mut c_void,
                },
                MpvRenderParam {
                    param_type: MPV_RENDER_PARAM_BACKEND,
                    data: backend_name.as_mut_ptr() as *mut c_void,
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
            rc = (api.mpv_render_context_create)(mpv_render_ctx, handle, params_gpu_next.as_mut_ptr());
            if rc == MPV_ERROR_SUCCESS && !mpv_render_ctx.is_null() {
                nlog("EGL: mpv_render_context (gpu-next/libplacebo) 创建成功");
            } else {
                nlog(&format!("EGL: gpu-next backend create failed rc={rc}, falling back to legacy OpenGL"));
            }
        }

        if rc != MPV_ERROR_SUCCESS {
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
            rc = (api.mpv_render_context_create)(mpv_render_ctx, handle, params.as_mut_ptr());
            if rc == MPV_ERROR_SUCCESS && !mpv_render_ctx.is_null() {
                nlog("EGL: mpv_render_context (legacy OpenGL) 创建成功");
            } else {
                nlog(&format!("EGL: mpv_render_context 创建失败 rc={rc}"));
            }
        }

        if rc == MPV_ERROR_SUCCESS && !mpv_render_ctx.is_null() {
            (api.mpv_render_context_set_update_callback)(
                *mpv_render_ctx,
                Some(mpv_update_callback),
                std::ptr::null_mut(),
            );
            nlog("EGL: 注册 mpv_render_context update callback 完成");
        }
    });
}

#[cfg(target_os = "android")]
pub unsafe fn free_render_context(
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

pub unsafe extern "C" fn mpv_update_callback(_ctx: *mut c_void) {
    if !super::thread::G_FRAME_GATE.try_acquire() {
        return;
    }
    if let Some(sender) = super::thread::render_sender() {
        let _ = sender.send(super::thread::RenderCommand::NotifyFrame);
    }
}

pub unsafe fn skip_frame_native(ctx: *mut c_void) {
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

#[cfg(target_os = "android")]
pub unsafe fn draw_frame_native(
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
            let starved = G_SESSION.frames_starved.fetch_add(1, Ordering::Relaxed) + 1;
            if starved % 120 == 0 {
                nlog(&format!(
                    "RENDER: presented={} starved={}",
                    G_SESSION.frames_drawn.load(Ordering::Relaxed),
                    starved
                ));
            }
            return DrawOutcome::Skipped;
        }

        let awaiting_first_frame = G_SESSION.awaiting_first_frame.load(Ordering::Acquire);
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

        let file_loaded = G_SESSION.file_loaded_reached.load(Ordering::Acquire);
        let (final_w, final_h) = if awaiting_first_frame && file_loaded {
            let mut dwidth = G_SESSION.cached_dwidth.load(Ordering::Acquire);
            let mut dheight = G_SESSION.cached_dheight.load(Ordering::Acquire);
            let mut rotate = G_SESSION.cached_rotate.load(Ordering::Acquire);
            if dwidth <= 0 || dheight <= 0 {
                let mpv_h = G_MPVP.load(Ordering::Acquire);
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
                    if dwidth > 0 && dheight > 0 {
                        G_SESSION.cached_dwidth.store(dwidth, Ordering::Release);
                        G_SESSION.cached_dheight.store(dheight, Ordering::Release);
                        G_SESSION.cached_rotate.store(rotate, Ordering::Release);
                    }
                }
            }

            if rotate % 180 != 0 {
                (dheight, dwidth)
            } else {
                (dwidth, dheight)
            }
        } else {
            (0, 0)
        };

        let aspect_mismatch = if final_w > 0 && final_h > 0 && w > 0 && h > 0 {
            let vid_aspect = final_w as f64 / final_h as f64;
            let buf_aspect = w as f64 / h as f64;
            (buf_aspect / vid_aspect - 1.0).abs() > 0.08
        } else {
            true
        };
        let load_start = G_SESSION.load_started_ms.load(Ordering::Relaxed);
        let now_ms = monotonic_ms() as u64;
        let waited_ms = now_ms.saturating_sub(load_start);

        if render_rc == MPV_ERROR_SUCCESS && swap_rc == EGL_TRUE {
            (api.mpv_render_context_report_swap)(ctx);
            if awaiting_first_frame {
                crate::tracelog(
                    "NATIVE_SWAP_WHILE_AWAITING",
                    &format!(
                        "force={force}, is_redraw={is_redraw}, file_loaded={file_loaded}, buf={w}x{h}, vid={final_w}x{final_h}, mismatch={aspect_mismatch}, waited={waited_ms}ms"
                    ),
                );
                if FirstFrameGate::should_signal(file_loaded, force, is_redraw, aspect_mismatch, waited_ms) {
                    let cur_epoch = crate::events::current_epoch();
                    let frame_epoch = G_RENDER_EPOCH.load(Ordering::Acquire);
                    if frame_epoch >= cur_epoch {
                        crate::events::signal_first_frame();
                    } else {
                        crate::tracelog(
                            "NATIVE_SUPPRESS_FIRST_FRAME_EXPIRED_EPOCH",
                            &format!(
                                "frame_epoch={frame_epoch}, current_epoch={cur_epoch}"
                            ),
                        );
                    }
                } else {
                    crate::tracelog(
                        "NATIVE_SUPPRESS_FIRST_FRAME",
                        &format!(
                            "file_loaded={file_loaded}, force={force}, is_redraw={is_redraw}, mismatch={aspect_mismatch}, waited={waited_ms}ms"
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
        let previous_ms = G_SESSION.last_draw_ms.swap(now_ms, Ordering::Relaxed);
        let gap_ms = if previous_ms == 0 { 0 } else { now_ms.saturating_sub(previous_ms) as u128 };
        if gap_ms > 100 {
            nlog(&format!("RENDER: GAP {gap_ms}ms buf={w}x{h}"));
        }

        let presented = G_SESSION.frames_drawn.fetch_add(1, Ordering::Relaxed) + 1;
        if presented <= 3 || presented % 300 == 0 {
            let (delayed, mistimed, vo_drop, dec_drop) = match crate::events::G_EVENT_PUMP.frame_health.lock() {
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
                G_SESSION.frames_starved.load(Ordering::Relaxed),
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

#[cfg(not(target_os = "android"))]
pub unsafe fn revive_dead_pipeline(_core: &mut EglCore, _mpv_render_ctx: &mut *mut c_void) -> bool {
    false
}

#[cfg(not(target_os = "android"))]
pub unsafe fn recover_gl_context(
    _core: &mut EglCore,
    _slot: &mut SurfaceSlot,
    _mpv_render_ctx: &mut *mut c_void,
) -> bool {
    false
}

#[cfg(not(target_os = "android"))]
pub unsafe fn free_render_context(
    _display: EGLDisplay,
    _context: EGLContext,
    mpv_render_ctx: &mut *mut c_void,
) {
    *mpv_render_ctx = std::ptr::null_mut();
}

#[cfg(not(target_os = "android"))]
pub unsafe fn draw_frame_native(
    _display: EGLDisplay,
    _surface: EGLSurface,
    _ctx: *mut c_void,
    _force: bool,
) -> DrawOutcome {
    DrawOutcome::Skipped
}
