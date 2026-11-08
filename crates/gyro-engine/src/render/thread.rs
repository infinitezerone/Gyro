//! 原生渲染命令、常驻渲染线程与策略调度循环

#![cfg_attr(not(target_os = "android"), allow(dead_code, unused_variables))]

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc::{Receiver, Sender};
use std::sync::{Mutex, OnceLock};
use std::thread::JoinHandle;

use super::types::*;
use super::egl::init_egl_strategy;
use super::G_SESSION;
use crate::logic::{AffinityVerdict, FrameCoalescer, ThreadAffinity};
use crate::strategy::{RenderBackend, RenderStrategy};
use crate::{nlog, reset_engine_lifecycle_state};

pub enum RenderCommand {
    AttachSurface(NativeWindowPtr),
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

pub static G_FRAME_GATE: FrameCoalescer = FrameCoalescer::new();
static G_AFFINITY: Mutex<ThreadAffinity> = Mutex::new(ThreadAffinity::new());
static G_AFFINITY_OWNER: AtomicU64 = AtomicU64::new(0);
static NEXT_LOGICAL_TID: AtomicU64 = AtomicU64::new(1);

pub(crate) fn logical_thread_id() -> u64 {
    thread_local! {
        static TID: u64 = NEXT_LOGICAL_TID.fetch_add(1, Ordering::Relaxed);
    }
    TID.with(|id| *id)
}

#[inline]
pub(crate) unsafe fn assert_render_thread(what: &str) {
    let tid = logical_thread_id();
    let owner = G_AFFINITY_OWNER.load(Ordering::Relaxed);
    if ThreadAffinity::verdict(owner, tid) == AffinityVerdict::Violation {
        nlog(&format!("FATAL: {what} 在非渲染线程被调用 (tid={tid})"));
        debug_assert!(false, "{what} called off the render thread");
    }
}

pub(crate) fn request_vo_check_wakeup() {
    unsafe {
        if let Ok((_guard, handle)) = crate::mpv::mpv_handle_guarded() {
            let _ = crate::mpv::with_api(|api| {
                (api.mpv_wakeup)(handle);
            });
        }
    }
}

pub(crate) fn request_video_rearm() {
    G_SESSION.rearm_video.store(true, Ordering::Release);
    request_vo_check_wakeup();
}

pub fn render_sender() -> Option<Sender<RenderCommand>> {
    RENDER_THREAD
        .get()
        .and_then(|lock| lock.lock().ok())
        .and_then(|guard| guard.as_ref().map(|h| h.sender.clone()))
}

pub fn ensure_render_thread() {
    let lock = RENDER_THREAD.get_or_init(|| Mutex::new(None));
    let mut guard = match lock.lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };
    if guard.is_none() {
        let (sender, receiver) = std::sync::mpsc::channel();
        let thread = std::thread::Builder::new()
            .name("gyro-render".into())
            .spawn(move || native_render_loop(receiver))
            .expect("spawn gyro-render thread");
        *guard = Some(RenderThreadHandle { sender, _thread: thread });
        nlog("ensure_render_thread: gyro-render 原生线程已启动");
    }
}

pub fn clear_render_thread() {
    if let Some(lock) = RENDER_THREAD.get() {
        if let Ok(mut guard) = lock.lock() {
            *guard = None;
            nlog("clear_render_thread: 渲染线程句柄已清空");
        }
    }
}

pub fn destroy_render_context_sync() {
    if let Some(sender) = render_sender() {
        let (ack_tx, ack_rx) = std::sync::mpsc::sync_channel(0);
        let _ = sender.send(RenderCommand::DestroyContext(ack_tx));
        let timeout = std::time::Duration::from_millis(DESTROY_ACK_TIMEOUT_MS);
        if ack_rx.recv_timeout(timeout).is_err() {
            nlog("destroy_render_context_sync: 等待渲染线程销毁上下文超时");
        }
    }
}

pub static G_PREFER_VULKAN: AtomicBool = AtomicBool::new(false);

#[cfg(target_os = "android")]
unsafe fn create_render_strategy() -> Result<Box<dyn RenderStrategy>, String> {
    if G_PREFER_VULKAN.load(Ordering::Acquire) {
        nlog("渲染策略: 配置优先请求 Vulkan 渲染后端");
        match crate::vulkan::VulkanRenderStrategy::new() {
            Ok(vk) => {
                nlog("渲染策略: 成功加载 Vulkan 策略");
                return Ok(Box::new(vk));
            }
            Err(e) => {
                nlog(&format!("渲染策略: Vulkan 不可用 ({e})，平滑降级至 EGL 策略"));
            }
        }
    }
    init_egl_strategy().map(|egl| Box::new(egl) as Box<dyn RenderStrategy>)
}

pub fn native_render_loop(receiver: Receiver<RenderCommand>) {
    let owner_tid = logical_thread_id();
    if let Ok(mut affinity) = G_AFFINITY.lock() {
        affinity.claim(owner_tid);
    }
    G_AFFINITY_OWNER.store(owner_tid, Ordering::Release);

    #[cfg(target_os = "android")]
    unsafe {
        let mut strategy: Box<dyn RenderStrategy> = match create_render_strategy() {
            Ok(s) => s,
            Err(e) => {
                super::egl::egl_failure(&format!("create_render_strategy: {e}"));
                return;
            }
        };

        nlog(&format!("渲染线程: 当前活跃渲染策略为 [{}]", strategy.name()));

        let mut mpv_render_ctx: *mut std::ffi::c_void = std::ptr::null_mut();

        while let Ok(cmd) = receiver.recv() {
            match cmd {
                RenderCommand::AttachSurface(NativeWindowPtr(window)) => {
                    nlog(&format!("{}: 收到 AttachSurface 命令", strategy.name()));
                    let _ = strategy.attach_surface(window, &mut mpv_render_ctx);
                    let ready = strategy.is_ready();
                    G_SESSION.render_slot_ready.store(ready, Ordering::Release);
                    if ready {
                        let ctx_ok = strategy.ensure_render_context(&mut mpv_render_ctx);
                        if !ctx_ok && strategy.backend_type() != RenderBackend::OpenGlEs {
                            nlog("Vulkan: mpv_render_context 创建不受支持，自动无缝降级至 EGL 策略");
                            strategy.destroy();
                            if let Ok(mut egl_strat) = init_egl_strategy() {
                                let _ = egl_strat.attach_surface(window, &mut mpv_render_ctx);
                                egl_strat.ensure_render_context(&mut mpv_render_ctx);
                                strategy = Box::new(egl_strat);
                            }
                        }
                    }
                    nlog(&format!(
                        "{}: attach 完成 ready={} ctx_null={}",
                        strategy.name(),
                        ready,
                        mpv_render_ctx.is_null()
                    ));
                    if ready && G_SESSION.file_loaded_reached.load(Ordering::Acquire) {
                        G_SESSION.pending_vo_check.store(true, Ordering::Release);
                        request_vo_check_wakeup();
                    }
                }
                RenderCommand::DetachSurface => {
                    strategy.detach_surface();
                    G_SESSION.render_slot_ready.store(false, Ordering::Release);
                    nlog(&format!("{}: Surface 已解绑", strategy.name()));
                }
                RenderCommand::Redraw => {
                    let awaiting = G_SESSION.awaiting_first_frame.load(Ordering::Acquire);
                    let file_loaded = G_SESSION.file_loaded_reached.load(Ordering::Acquire);
                    let ready = strategy.is_ready();
                    crate::tracelog(
                        "NATIVE_CMD_REDRAW",
                        &format!("slot_ready={ready}, awaiting_first_frame={awaiting}, file_loaded={file_loaded}"),
                    );
                    if awaiting {
                        crate::tracelog("NATIVE_REDRAW_SKIPPED_AWAITING_FIRST_FRAME", "");
                        continue;
                    }
                    nlog(&format!("{}: 收到 Redraw 命令", strategy.name()));
                    if ready && !mpv_render_ctx.is_null() {
                        strategy.draw_frame(mpv_render_ctx, true);
                    }
                }
                RenderCommand::NotifyFrame => {
                    G_FRAME_GATE.release();
                    if strategy.is_ready() {
                        strategy.ensure_render_context(&mut mpv_render_ctx);
                        strategy.draw_frame(mpv_render_ctx, false);
                    } else if !mpv_render_ctx.is_null() {
                        strategy.skip_frame(mpv_render_ctx);
                    }
                }
                RenderCommand::DestroyContext(ack) => {
                    nlog(&format!("{}: 收到 DestroyContext 请求，开始销毁 mpv_render_context", strategy.name()));
                    strategy.free_render_context(&mut mpv_render_ctx);
                    let _ = ack.send(());
                }
                RenderCommand::Shutdown => {
                    nlog(&format!("{}: 渲染线程正在退出并清理资源", strategy.name()));
                    strategy.free_render_context(&mut mpv_render_ctx);
                    strategy.destroy();
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
