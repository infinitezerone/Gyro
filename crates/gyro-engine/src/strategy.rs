//! 渲染策略模式抽象（OpenGL ES / Vulkan 渲染后端统一解耦接口）

use std::ffi::c_void;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DrawOutcome {
    Presented,
    Skipped,
    ContextLost,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RenderBackend {
    OpenGlEs,
    Vulkan,
}

pub trait RenderStrategy: Send {
    fn name(&self) -> &'static str;
    fn backend_type(&self) -> RenderBackend;
    fn attach_surface(&mut self, window: *mut c_void, mpv_render_ctx: &mut *mut c_void) -> Result<(), i32>;
    fn detach_surface(&mut self);
    fn is_ready(&self) -> bool;
    fn ensure_render_context(&mut self, mpv_render_ctx: &mut *mut c_void) -> bool;
    fn free_render_context(&mut self, mpv_render_ctx: &mut *mut c_void);
    fn draw_frame(&mut self, mpv_render_ctx: *mut c_void, force: bool) -> DrawOutcome;
    fn skip_frame(&mut self, mpv_render_ctx: *mut c_void);
    fn destroy(&mut self);
}

#[cfg(test)]
mod tests {
    use super::*;

    struct MockStrategy {
        ready: bool,
        attached: bool,
        ctx_created: bool,
        drawn_count: usize,
    }

    impl RenderStrategy for MockStrategy {
        fn name(&self) -> &'static str {
            "MockStrategy"
        }

        fn backend_type(&self) -> RenderBackend {
            RenderBackend::OpenGlEs
        }

        fn attach_surface(&mut self, window: *mut c_void, _mpv_render_ctx: &mut *mut c_void) -> Result<(), i32> {
            if window.is_null() {
                Err(-1)
            } else {
                self.attached = true;
                self.ready = true;
                Ok(())
            }
        }

        fn detach_surface(&mut self) {
            self.attached = false;
            self.ready = false;
        }

        fn is_ready(&self) -> bool {
            self.ready
        }

        fn ensure_render_context(&mut self, mpv_render_ctx: &mut *mut c_void) -> bool {
            *mpv_render_ctx = 0x1234 as *mut c_void;
            self.ctx_created = true;
            true
        }

        fn free_render_context(&mut self, mpv_render_ctx: &mut *mut c_void) {
            *mpv_render_ctx = std::ptr::null_mut();
            self.ctx_created = false;
        }

        fn draw_frame(&mut self, _mpv_render_ctx: *mut c_void, _force: bool) -> DrawOutcome {
            self.drawn_count += 1;
            DrawOutcome::Presented
        }

        fn skip_frame(&mut self, _mpv_render_ctx: *mut c_void) {}

        fn destroy(&mut self) {
            self.detach_surface();
        }
    }

    #[test]
    fn test_render_strategy_lifecycle() {
        let mut strategy = MockStrategy {
            ready: false,
            attached: false,
            ctx_created: false,
            drawn_count: 0,
        };

        assert_eq!(strategy.backend_type(), RenderBackend::OpenGlEs);
        assert!(!strategy.is_ready());

        let fake_window = 0xABCD as *mut c_void;
        let mut dummy_ctx: *mut c_void = std::ptr::null_mut();
        assert!(strategy.attach_surface(fake_window, &mut dummy_ctx).is_ok());
        assert!(strategy.is_ready());

        assert!(strategy.ensure_render_context(&mut dummy_ctx));
        assert!(!dummy_ctx.is_null());

        let outcome = strategy.draw_frame(dummy_ctx, false);
        assert_eq!(outcome, DrawOutcome::Presented);
        assert_eq!(strategy.drawn_count, 1);

        strategy.free_render_context(&mut dummy_ctx);
        assert!(dummy_ctx.is_null());

        strategy.detach_surface();
        assert!(!strategy.is_ready());
    }
}
