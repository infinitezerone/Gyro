//! 防御层 1：冷启动离屏环境探针 (Static Env Probe)

#![cfg_attr(not(target_os = "android"), allow(dead_code, unused_variables, unused_imports))]

use std::ffi::{c_char, CStr};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::OnceLock;

use super::types::*;
#[cfg(target_os = "android")]
use super::frame::bind_probe_target;
use crate::nlog;

pub static G_ZERO_COPY_SAFE: AtomicBool = AtomicBool::new(true);
pub static G_GPU_RENDERER: OnceLock<String> = OnceLock::new();
pub static G_GPU_VENDOR: OnceLock<String> = OnceLock::new();

#[cfg(target_os = "android")]
pub(crate) unsafe fn probe_graphics_environment(display: EGLDisplay, ctx: EGLContext) {
    let mut renderer_str = String::from("unknown");
    let mut vendor_str = String::from("unknown");
    let mut has_image_base = false;
    let mut has_android_native = false;
    let mut has_oes_external = false;

    let egl_exts_ptr = eglQueryString(display, EGL_EXTENSIONS);
    if !egl_exts_ptr.is_null() {
        let egl_exts = CStr::from_ptr(egl_exts_ptr).to_string_lossy();
        has_image_base = egl_exts.contains("EGL_KHR_image_base");
        has_android_native = egl_exts.contains("EGL_ANDROID_image_native_buffer");
    }

    if let Some(target) = bind_probe_target(display, ctx) {
        let gl_get_string: Option<unsafe extern "C" fn(u32) -> *const c_char> = {
            let proc = eglGetProcAddress(b"glGetString\0".as_ptr().cast());
            if proc.is_null() { None } else { Some(std::mem::transmute(proc)) }
        };

        if let Some(get_str) = gl_get_string {
            let r_ptr = get_str(GL_RENDERER);
            if !r_ptr.is_null() {
                renderer_str = CStr::from_ptr(r_ptr).to_string_lossy().into_owned();
            }
            let v_ptr = get_str(GL_VENDOR);
            if !v_ptr.is_null() {
                vendor_str = CStr::from_ptr(v_ptr).to_string_lossy().into_owned();
            }
            let ext_ptr = get_str(GL_EXTENSIONS);
            if !ext_ptr.is_null() {
                let gl_exts = CStr::from_ptr(ext_ptr).to_string_lossy();
                has_oes_external = gl_exts.contains("GL_OES_EGL_image_external");
            }
        }
        target.release(display);
    }

    let is_zero_copy = has_image_base && has_android_native && has_oes_external;
    G_ZERO_COPY_SAFE.store(is_zero_copy, Ordering::Release);

    let _ = G_GPU_RENDERER.set(renderer_str.clone());
    let _ = G_GPU_VENDOR.set(vendor_str.clone());

    nlog(&format!(
        "PROBE: GPU='{renderer_str}' Vendor='{vendor_str}' ZeroCopySafe={is_zero_copy} (img_base={has_image_base}, native_buf={has_android_native}, oes_ext={has_oes_external})"
    ));
}

#[cfg(not(target_os = "android"))]
pub(crate) unsafe fn probe_graphics_environment(_display: EGLDisplay, _ctx: EGLContext) {}
