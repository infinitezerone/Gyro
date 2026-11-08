//! EGL / OpenGL ES 原生上下文、Surface 槽位与渲染策略实现

#![cfg_attr(not(target_os = "android"), allow(dead_code, unused_variables, unused_imports))]

use std::ffi::c_void;
use std::sync::atomic::{AtomicI64, Ordering};

use super::types::*;
use super::frame::{
    draw_frame_native, ensure_render_context, free_render_context, recover_gl_context,
    revive_dead_pipeline, skip_frame_native,
};
use super::probe::probe_graphics_environment;
use super::thread::assert_render_thread;
use super::G_SESSION;
use crate::logic::{egl_config_attempts, EglConfigRequest};
use crate::strategy::{DrawOutcome, RenderBackend, RenderStrategy};
use crate::nlog;

pub const EGL_BAD_SURFACE: EGLint = 0x300D;
pub const CONTEXT_CLIENT_VERSIONS: [EGLint; 2] = [3, 2];

pub fn egl_error_name(code: EGLint) -> &'static str {
    match code {
        0x3000 => "EGL_SUCCESS",
        0x3001 => "EGL_NOT_INITIALIZED",
        0x3002 => "EGL_BAD_ACCESS",
        0x3003 => "EGL_BAD_ALLOC",
        0x3004 => "EGL_BAD_ATTRIBUTE",
        0x3005 => "EGL_BAD_CONFIG",
        0x3006 => "EGL_BAD_CONTEXT",
        0x3007 => "EGL_BAD_CURRENT_SURFACE",
        0x3008 => "EGL_BAD_DISPLAY",
        0x3009 => "EGL_BAD_MATCH",
        0x300A => "EGL_BAD_NATIVE_PIXMAP",
        0x300B => "EGL_BAD_NATIVE_WINDOW",
        0x300C => "EGL_BAD_PARAMETER",
        0x300D => "EGL_BAD_SURFACE",
        0x300E => "EGL_CONTEXT_LOST",
        _ => "EGL_UNKNOWN",
    }
}

pub(crate) fn egl_failure(what: &str) -> EGLint {
    #[cfg(target_os = "android")]
    {
        let err = unsafe { eglGetError() };
        nlog(&format!(
            "EGL: {what} 失败 (error={} / 0x{err:X})",
            egl_error_name(err)
        ));
        err
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = what;
        0
    }
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

pub struct ChosenConfig {
    pub config: EGLConfig,
    pub visual_id: EGLint,
}

pub unsafe fn choose_egl_config(display: EGLDisplay, prefer_10bit: bool) -> Option<ChosenConfig> {
    #[cfg(target_os = "android")]
    {
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
    #[cfg(not(target_os = "android"))]
    {
        let _ = (display, prefer_10bit);
        None
    }
}

// ---------- ANativeWindow 引用台账 ----------

pub static WINDOW_REF_BALANCE: AtomicI64 = AtomicI64::new(0);

pub fn window_ref(delta: i64, site: &str) {
    let balance = WINDOW_REF_BALANCE.fetch_add(delta, Ordering::AcqRel) + delta;
    nlog(&format!("WINREF: {site} {delta:+} balance={balance}"));
}

pub struct EglCore {
    pub display: EGLDisplay,
    pub config: EGLConfig,
    pub context: EGLContext,
    pub context_major: EGLint,
    pub visual_id: EGLint,
}

impl EglCore {
    #[cfg(target_os = "android")]
    pub unsafe fn create_context_with_major(
        display: EGLDisplay,
        config: EGLConfig,
        major: EGLint,
    ) -> EGLContext {
        let attribs = [EGL_CONTEXT_CLIENT_VERSION, major, EGL_NONE];
        eglCreateContext(display, config, EGL_NO_CONTEXT, attribs.as_ptr())
    }

    #[cfg(not(target_os = "android"))]
    pub unsafe fn create_context_with_major(
        _display: EGLDisplay,
        _config: EGLConfig,
        _major: EGLint,
    ) -> EGLContext {
        std::ptr::null_mut()
    }

    #[cfg(target_os = "android")]
    pub unsafe fn recreate_context(&mut self) -> bool {
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

    #[cfg(not(target_os = "android"))]
    pub unsafe fn recreate_context(&mut self) -> bool {
        false
    }

    pub unsafe fn destroy(&mut self) {
        #[cfg(target_os = "android")]
        {
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

pub struct SurfaceSlot {
    pub display: EGLDisplay,
    pub config: EGLConfig,
    pub surface: EGLSurface,
    pub window: *mut ANativeWindow,
}

impl SurfaceSlot {
    pub fn new(display: EGLDisplay, config: EGLConfig) -> Self {
        Self {
            display,
            config,
            surface: EGL_NO_SURFACE,
            window: std::ptr::null_mut(),
        }
    }

    pub fn is_ready(&self) -> bool {
        self.surface != EGL_NO_SURFACE && !self.window.is_null()
    }

    pub fn surface(&self) -> EGLSurface {
        self.surface
    }

    pub unsafe fn attach(
        &mut self,
        new_window: *mut ANativeWindow,
        context: EGLContext,
        context_major: EGLint,
        _visual_id: EGLint,
    ) -> Result<(), EGLint> {
        #[cfg(target_os = "android")]
        {
            assert_render_thread("SurfaceSlot::attach");
            if new_window.is_null() {
                return Err(EGL_BAD_SURFACE);
            }

            if !self.window.is_null() && self.window == new_window && self.surface != EGL_NO_SURFACE {
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
            window_ref(-1, "attach(success) release handoff, slot keeps 1");
            ANativeWindow_release(new_window);
            Ok(())
        }
        #[cfg(not(target_os = "android"))]
        {
            let _ = (new_window, context, context_major, _visual_id);
            Ok(())
        }
    }

    pub unsafe fn detach(&mut self) {
        #[cfg(target_os = "android")]
        {
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
    }

    pub unsafe fn drop_surface(&mut self) {
        #[cfg(target_os = "android")]
        {
            assert_render_thread("SurfaceSlot::drop_surface");
            if self.surface != EGL_NO_SURFACE {
                eglMakeCurrent(self.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
                let _ = eglDestroySurface(self.display, self.surface);
                self.surface = EGL_NO_SURFACE;
            }
        }
    }

    pub unsafe fn rebuild_surface(
        &mut self,
        context: EGLContext,
        context_major: EGLint,
    ) -> Result<(), EGLint> {
        #[cfg(target_os = "android")]
        {
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
        #[cfg(not(target_os = "android"))]
        {
            let _ = (context, context_major);
            Ok(())
        }
    }
}

// ---------- 策略模式：EGL / OpenGL ES 渲染策略实现 ----------

pub struct EglRenderStrategy {
    pub(crate) core: EglCore,
    pub(crate) slot: SurfaceSlot,
}

unsafe impl Send for EglRenderStrategy {}
unsafe impl Sync for EglRenderStrategy {}

impl EglRenderStrategy {
    pub(crate) fn new(core: EglCore, slot: SurfaceSlot) -> Self {
        Self { core, slot }
    }
}

impl RenderStrategy for EglRenderStrategy {
    fn name(&self) -> &'static str {
        "OpenGL ES (EGL)"
    }

    fn backend_type(&self) -> RenderBackend {
        RenderBackend::OpenGlEs
    }

    fn attach_surface(&mut self, window: *mut c_void, mpv_render_ctx: &mut *mut c_void) -> Result<(), i32> {
        let _ = unsafe { revive_dead_pipeline(&mut self.core, mpv_render_ctx) };
        unsafe { self.slot.attach(window, self.core.context, self.core.context_major, self.core.visual_id) }
    }

    fn detach_surface(&mut self) {
        unsafe { self.slot.detach() };
    }

    fn is_ready(&self) -> bool {
        self.slot.is_ready()
    }

    fn ensure_render_context(&mut self, mpv_render_ctx: &mut *mut c_void) -> bool {
        unsafe { ensure_render_context(mpv_render_ctx) };
        !mpv_render_ctx.is_null()
    }

    fn free_render_context(&mut self, mpv_render_ctx: &mut *mut c_void) {
        unsafe { free_render_context(self.core.display, self.core.context, mpv_render_ctx) };
    }

    fn draw_frame(&mut self, mpv_render_ctx: *mut c_void, force: bool) -> DrawOutcome {
        if !self.slot.is_ready() || mpv_render_ctx.is_null() {
            return DrawOutcome::Skipped;
        }
        if G_SESSION.render_dead.load(Ordering::Acquire) {
            return DrawOutcome::Skipped;
        }
        unsafe {
            let outcome = draw_frame_native(self.core.display, self.slot.surface(), mpv_render_ctx, force);
            if outcome == DrawOutcome::ContextLost {
                nlog("EGL: eglSwapBuffers 报告上下文丢失，开始重建管线");
                let mut ctx_box = mpv_render_ctx;
                if recover_gl_context(&mut self.core, &mut self.slot, &mut ctx_box) {
                    return draw_frame_native(self.core.display, self.slot.surface(), ctx_box, true);
                }
            }
            outcome
        }
    }

    fn skip_frame(&mut self, mpv_render_ctx: *mut c_void) {
        if !mpv_render_ctx.is_null() {
            unsafe { skip_frame_native(mpv_render_ctx) };
        }
    }

    fn destroy(&mut self) {
        unsafe {
            self.slot.detach();
            self.core.destroy();
        }
    }
}

pub(crate) unsafe fn init_egl_strategy() -> Result<EglRenderStrategy, String> {
    #[cfg(target_os = "android")]
    {
        let display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if display == EGL_NO_DISPLAY {
            return Err("eglGetDisplay".into());
        }

        let mut major: EGLint = 0;
        let mut minor: EGLint = 0;
        if eglInitialize(display, &mut major, &mut minor) != EGL_TRUE {
            return Err("eglInitialize".into());
        }
        nlog(&format!("EGL: 初始化完成 version={major}.{minor}"));

        let chosen = match choose_egl_config(display, false) {
            Some(c) => c,
            None => return Err("choose_egl_config".into()),
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
            return Err("eglCreateContext(all-versions)".into());
        }

        probe_graphics_environment(display, context);

        let core = EglCore {
            display,
            config: chosen.config,
            context,
            context_major,
            visual_id: chosen.visual_id,
        };

        let slot = SurfaceSlot::new(display, chosen.config);
        Ok(EglRenderStrategy::new(core, slot))
    }
    #[cfg(not(target_os = "android"))]
    {
        let core = EglCore {
            display: std::ptr::null_mut(),
            config: std::ptr::null_mut(),
            context: std::ptr::null_mut(),
            context_major: 0,
            visual_id: 0,
        };
        let slot = SurfaceSlot::new(std::ptr::null_mut(), std::ptr::null_mut());
        Ok(EglRenderStrategy::new(core, slot))
    }
}
