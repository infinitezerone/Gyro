//! EGL / NDK 底层类型、常量与 FFI 外部链接声明

#![cfg_attr(not(target_os = "android"), allow(dead_code, unused_imports))]

use std::ffi::{c_char, c_void};

pub type EGLDisplay = *mut c_void;
pub type EGLConfig = *mut c_void;
pub type EGLContext = *mut c_void;
pub type EGLSurface = *mut c_void;
pub type EGLint = i32;
pub type ANativeWindow = c_void;

#[derive(Clone, Copy)]
pub struct NativeWindowPtr(pub *mut ANativeWindow);
unsafe impl Send for NativeWindowPtr {}
unsafe impl Sync for NativeWindowPtr {}

pub const EGL_NO_DISPLAY: EGLDisplay = std::ptr::null_mut();
pub const EGL_NO_CONTEXT: EGLContext = std::ptr::null_mut();
pub const EGL_NO_SURFACE: EGLSurface = std::ptr::null_mut();
pub const EGL_DEFAULT_DISPLAY: *mut c_void = std::ptr::null_mut();

pub const EGL_TRUE: EGLint = 1;
pub const EGL_WIDTH: EGLint = 0x3057;
pub const EGL_HEIGHT: EGLint = 0x3056;
pub const EGL_RENDERABLE_TYPE: EGLint = 0x3040;
pub const EGL_OPENGL_ES2_BIT: EGLint = 0x00000004;
pub const EGL_OPENGL_ES3_BIT: EGLint = 0x00000040;
pub const EGL_SURFACE_TYPE: EGLint = 0x3033;
pub const EGL_WINDOW_BIT: EGLint = 0x0004;
pub const EGL_PBUFFER_BIT: EGLint = 0x0001;
pub const EGL_RED_SIZE: EGLint = 0x3024;
pub const EGL_GREEN_SIZE: EGLint = 0x3023;
pub const EGL_BLUE_SIZE: EGLint = 0x3022;
pub const EGL_ALPHA_SIZE: EGLint = 0x3021;
pub const EGL_DEPTH_SIZE: EGLint = 0x3025;
pub const EGL_STENCIL_SIZE: EGLint = 0x3026;
pub const EGL_NONE: EGLint = 0x3038;
pub const EGL_CONTEXT_CLIENT_VERSION: EGLint = 0x3098;
pub const EGL_NATIVE_VISUAL_ID: EGLint = 0x302E;
pub const EGL_EXTENSIONS: EGLint = 0x3055;
pub const EGL_CONFIG_CAVEAT: EGLint = 0x3027;
pub const EGL_CONFIG_ID: EGLint = 0x3028;
pub const EGL_CONTEXT_LOST: EGLint = 0x300E;
pub const EGL_SLOW_CONFIG: EGLint = 0x3050;
pub const EGL_NON_CONFORMANT_CONFIG: EGLint = 0x3051;

pub const GL_VENDOR: u32 = 0x1F00;
pub const GL_RENDERER: u32 = 0x1F01;
pub const GL_EXTENSIONS: u32 = 0x1F03;

/// Upper bound on how long the DestroyContext *caller* waits for the render thread's ack.
pub const DESTROY_ACK_TIMEOUT_MS: u64 = 2000;

#[cfg(target_os = "android")]
#[link(name = "EGL")]
extern "C" {
    pub fn eglGetDisplay(display_id: *mut c_void) -> EGLDisplay;
    pub fn eglInitialize(dpy: EGLDisplay, major: *mut EGLint, minor: *mut EGLint) -> EGLint;
    pub fn eglChooseConfig(
        dpy: EGLDisplay,
        attrib_list: *const EGLint,
        configs: *mut EGLConfig,
        config_size: EGLint,
        num_config: *mut EGLint,
    ) -> EGLint;
    pub fn eglGetConfigAttrib(
        dpy: EGLDisplay,
        config: EGLConfig,
        attribute: EGLint,
        value: *mut EGLint,
    ) -> EGLint;
    pub fn eglCreateContext(
        dpy: EGLDisplay,
        config: EGLConfig,
        share_context: EGLContext,
        attrib_list: *const EGLint,
    ) -> EGLContext;
    pub fn eglCreateWindowSurface(
        dpy: EGLDisplay,
        config: EGLConfig,
        win: *mut ANativeWindow,
        attrib_list: *const EGLint,
    ) -> EGLSurface;
    pub fn eglCreatePbufferSurface(
        dpy: EGLDisplay,
        config: EGLConfig,
        attrib_list: *const EGLint,
    ) -> EGLSurface;
    pub fn eglMakeCurrent(
        dpy: EGLDisplay,
        draw: EGLSurface,
        read: EGLSurface,
        ctx: EGLContext,
    ) -> EGLint;
    pub fn eglQuerySurface(
        dpy: EGLDisplay,
        surface: EGLSurface,
        attribute: EGLint,
        value: *mut EGLint,
    ) -> EGLint;
    pub fn eglQueryString(dpy: EGLDisplay, name: EGLint) -> *const c_char;
    pub fn eglGetError() -> EGLint;
    pub fn eglSwapBuffers(dpy: EGLDisplay, surface: EGLSurface) -> EGLint;
    pub fn eglSwapInterval(dpy: EGLDisplay, interval: EGLint) -> EGLint;
    pub fn eglDestroySurface(dpy: EGLDisplay, surface: EGLSurface) -> EGLint;
    pub fn eglDestroyContext(dpy: EGLDisplay, ctx: EGLContext) -> EGLint;
    pub fn eglReleaseThread() -> EGLint;
    pub fn eglTerminate(dpy: EGLDisplay) -> EGLint;
    pub fn eglGetProcAddress(procname: *const c_char) -> *mut c_void;
}

#[cfg(target_os = "android")]
#[link(name = "android")]
extern "C" {
    pub fn ANativeWindow_fromSurface(env: *mut jni::sys::JNIEnv, surface: jni::sys::jobject) -> *mut ANativeWindow;
    pub fn ANativeWindow_acquire(window: *mut ANativeWindow);
    pub fn ANativeWindow_release(window: *mut ANativeWindow);
}
