//! Vulkan 原生渲染后端与 10-bit HDR Swapchain 管线
//!
//! 提供基于 Vulkan / libplacebo (gpu-next) 的高动态范围渲染管线：
//! - 动态解析 Android 原生 `libvulkan.so`
//! - 通过 `VK_KHR_android_surface` 创建原生 Android 呈现表面
//! - 协商 10-bit HDR 色彩空间 (`VK_COLOR_SPACE_HDR10_ST2084_EXT` / `VK_COLOR_SPACE_BT2020_HLG_EXT`)
//! - 对接 libmpv Vulkan Render API (`MPV_RENDER_PARAM_API_TYPE = "vulkan"`)

#![cfg_attr(not(target_os = "android"), allow(dead_code, unused_imports, unused_variables))]
#![allow(non_camel_case_types)]

use std::ffi::{c_char, c_int, c_void, CString};
use std::sync::atomic::Ordering;
use std::sync::OnceLock;

use libloading::{Library, Symbol};

use crate::mpv::{
    mpv_handle_guarded, with_api_logged, MpvRenderParam, MpvVulkanImage, MpvVulkanInitParams,
    MPV_ERROR_SUCCESS, MPV_RENDER_PARAM_API_TYPE, MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME,
    MPV_RENDER_PARAM_VULKAN_IMAGE, MPV_RENDER_PARAM_VULKAN_INIT_PARAMS,
};
use crate::strategy::{DrawOutcome, RenderBackend, RenderStrategy};
use crate::nlog;

// ---------- Vulkan 常量与类型定义 ----------

pub type VkFlags = u32;
pub type VkResult = i32;

pub const VK_SUCCESS: VkResult = 0;
pub const VK_NOT_READY: VkResult = 1;
pub const VK_TIMEOUT: VkResult = 2;
pub const VK_SUBOPTIMAL_KHR: VkResult = 1000001003;
pub const VK_ERROR_OUT_OF_DATE_KHR: VkResult = -1000001004;

pub const VK_STRUCTURE_TYPE_APPLICATION_INFO: u32 = 0;
pub const VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO: u32 = 1;
pub const VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO: u32 = 2;
pub const VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO: u32 = 3;
pub const VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO: u32 = 9;
pub const VK_STRUCTURE_TYPE_FENCE_CREATE_INFO: u32 = 8;
pub const VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR: u32 = 1000001000;
pub const VK_STRUCTURE_TYPE_PRESENT_INFO_KHR: u32 = 1000001001;
pub const VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR: u32 = 1000008000;

pub const VK_QUEUE_GRAPHICS_BIT: VkFlags = 0x00000001;
pub const VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT: VkFlags = 0x00000010;
pub const VK_IMAGE_LAYOUT_UNDEFINED: u32 = 0;
pub const VK_IMAGE_LAYOUT_PRESENT_SRC_KHR: u32 = 1000001002;

// 像素格式
pub const VK_FORMAT_R8G8B8A8_UNORM: u32 = 37;
pub const VK_FORMAT_A2B10G10R10_UNORM_PACK32: u32 = 64; // 10-bit HDR

// 色彩空间
pub const VK_COLOR_SPACE_SRGB_NONLINEAR_KHR: u32 = 0;
pub const VK_COLOR_SPACE_DISPLAY_P3_NONLINEAR_EXT: u32 = 1000104001;
pub const VK_COLOR_SPACE_HDR10_ST2084_EXT: u32 = 1000104008; // PQ HDR10
pub const VK_COLOR_SPACE_BT2020_HLG_EXT: u32 = 1000104010;   // HLG

pub const VK_PRESENT_MODE_IMMEDIATE_KHR: u32 = 0;
pub const VK_PRESENT_MODE_MAILBOX_KHR: u32 = 1;
pub const VK_PRESENT_MODE_FIFO_KHR: u32 = 2;

pub const VK_FENCE_CREATE_SIGNALED_BIT: VkFlags = 0x00000001;

// ---------- Vulkan 结构体布局 ----------

#[repr(C)]
pub struct VkApplicationInfo {
    pub s_type: u32,
    pub p_next: *const c_void,
    pub p_application_name: *const c_char,
    pub application_version: u32,
    pub p_engine_name: *const c_char,
    pub engine_version: u32,
    pub api_version: u32,
}

#[repr(C)]
pub struct VkInstanceCreateInfo {
    pub s_type: u32,
    pub p_next: *const c_void,
    pub flags: VkFlags,
    pub p_application_info: *const VkApplicationInfo,
    pub enabled_layer_count: u32,
    pub pp_enabled_layer_names: *const *const c_char,
    pub enabled_extension_count: u32,
    pub pp_enabled_extension_names: *const *const c_char,
}

#[repr(C)]
pub struct VkDeviceQueueCreateInfo {
    pub s_type: u32,
    pub p_next: *const c_void,
    pub flags: VkFlags,
    pub queue_family_index: u32,
    pub queue_count: u32,
    pub p_queue_priorities: *const f32,
}

#[repr(C)]
pub struct VkDeviceCreateInfo {
    pub s_type: u32,
    pub p_next: *const c_void,
    pub flags: VkFlags,
    pub queue_create_info_count: u32,
    pub p_queue_create_infos: *const VkDeviceQueueCreateInfo,
    pub enabled_layer_count: u32,
    pub pp_enabled_layer_names: *const *const c_char,
    pub enabled_extension_count: u32,
    pub pp_enabled_extension_names: *const *const c_char,
    pub p_enabled_features: *const c_void,
}

#[repr(C)]
pub struct VkAndroidSurfaceCreateInfoKHR {
    pub s_type: u32,
    pub p_next: *const c_void,
    pub flags: VkFlags,
    pub window: *mut c_void, // ANativeWindow
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct VkExtent2D {
    pub width: u32,
    pub height: u32,
}

#[repr(C)]
pub struct VkSurfaceCapabilitiesKHR {
    pub min_image_count: u32,
    pub max_image_count: u32,
    pub current_extent: VkExtent2D,
    pub min_image_extent: VkExtent2D,
    pub max_image_extent: VkExtent2D,
    pub max_image_array_layers: u32,
    pub supported_transforms: VkFlags,
    pub current_transform: VkFlags,
    pub supported_composite_alpha: VkFlags,
    pub supported_usage_flags: VkFlags,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct VkSurfaceFormatKHR {
    pub format: u32,
    pub color_space: u32,
}

#[repr(C)]
pub struct VkSwapchainCreateInfoKHR {
    pub s_type: u32,
    pub p_next: *const c_void,
    pub flags: VkFlags,
    pub surface: u64,
    pub min_image_count: u32,
    pub image_format: u32,
    pub image_color_space: u32,
    pub image_extent: VkExtent2D,
    pub image_array_layers: u32,
    pub image_usage: VkFlags,
    pub image_sharing_mode: u32,
    pub queue_family_index_count: u32,
    pub p_queue_family_indices: *const u32,
    pub pre_transform: u32,
    pub composite_alpha: u32,
    pub present_mode: u32,
    pub clipped: u32,
    pub old_swapchain: u64,
}

#[repr(C)]
pub struct VkPresentInfoKHR {
    pub s_type: u32,
    pub p_next: *const c_void,
    pub wait_semaphore_count: u32,
    pub p_wait_semaphores: *const u64,
    pub swapchain_count: u32,
    pub p_swapchains: *const u64,
    pub p_image_indices: *const u32,
    pub p_results: *mut VkResult,
}

#[repr(C)]
pub struct VkSemaphoreCreateInfo {
    pub s_type: u32,
    pub p_next: *const c_void,
    pub flags: VkFlags,
}

#[repr(C)]
pub struct VkFenceCreateInfo {
    pub s_type: u32,
    pub p_next: *const c_void,
    pub flags: VkFlags,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct VkQueueFamilyProperties {
    pub queue_flags: VkFlags,
    pub queue_count: u32,
    pub timestamp_valid_bits: u32,
    pub min_image_transfer_granularity: [u32; 3],
}

// ---------- 动态符号解析表 ----------

pub type PFN_vkGetInstanceProcAddr = unsafe extern "C" fn(*mut c_void, *const c_char) -> *mut c_void;

pub struct VulkanDispatch {
    pub get_instance_proc_addr: PFN_vkGetInstanceProcAddr,
    pub create_instance: unsafe extern "C" fn(*const VkInstanceCreateInfo, *const c_void, *mut *mut c_void) -> VkResult,
    pub destroy_instance: unsafe extern "C" fn(*mut c_void, *const c_void),
    pub enumerate_physical_devices: unsafe extern "C" fn(*mut c_void, *mut u32, *mut *mut c_void) -> VkResult,
    pub get_physical_device_queue_family_properties: unsafe extern "C" fn(*mut c_void, *mut u32, *mut VkQueueFamilyProperties),
    pub create_device: unsafe extern "C" fn(*mut c_void, *const VkDeviceCreateInfo, *const c_void, *mut *mut c_void) -> VkResult,
    pub destroy_device: unsafe extern "C" fn(*mut c_void, *const c_void),
    pub get_device_queue: unsafe extern "C" fn(*mut c_void, u32, u32, *mut *mut c_void),
    pub create_android_surface_khr: unsafe extern "C" fn(*mut c_void, *const VkAndroidSurfaceCreateInfoKHR, *const c_void, *mut u64) -> VkResult,
    pub destroy_surface_khr: unsafe extern "C" fn(*mut c_void, u64, *const c_void),
    pub get_physical_device_surface_capabilities_khr: unsafe extern "C" fn(*mut c_void, u64, *mut VkSurfaceCapabilitiesKHR) -> VkResult,
    pub get_physical_device_surface_formats_khr: unsafe extern "C" fn(*mut c_void, u64, *mut u32, *mut VkSurfaceFormatKHR) -> VkResult,
    pub create_swapchain_khr: unsafe extern "C" fn(*mut c_void, *const VkSwapchainCreateInfoKHR, *const c_void, *mut u64) -> VkResult,
    pub destroy_swapchain_khr: unsafe extern "C" fn(*mut c_void, u64, *const c_void),
    pub get_swapchain_images_khr: unsafe extern "C" fn(*mut c_void, u64, *mut u32, *mut u64) -> VkResult,
    pub acquire_next_image_khr: unsafe extern "C" fn(*mut c_void, u64, u64, u64, u64, *mut u32) -> VkResult,
    pub queue_present_khr: unsafe extern "C" fn(*mut c_void, *const VkPresentInfoKHR) -> VkResult,
    pub create_semaphore: unsafe extern "C" fn(*mut c_void, *const VkSemaphoreCreateInfo, *const c_void, *mut u64) -> VkResult,
    pub destroy_semaphore: unsafe extern "C" fn(*mut c_void, u64, *const c_void),
    pub create_fence: unsafe extern "C" fn(*mut c_void, *const VkFenceCreateInfo, *const c_void, *mut u64) -> VkResult,
    pub destroy_fence: unsafe extern "C" fn(*mut c_void, u64, *const c_void),
    pub wait_for_fences: unsafe extern "C" fn(*mut c_void, u32, *const u64, u32, u64) -> VkResult,
    pub reset_fences: unsafe extern "C" fn(*mut c_void, u32, *const u64) -> VkResult,
    pub device_wait_idle: unsafe extern "C" fn(*mut c_void) -> VkResult,
}

static VULKAN_LIB: OnceLock<Option<Library>> = OnceLock::new();

fn get_vulkan_library() -> Option<&'static Library> {
    VULKAN_LIB
        .get_or_init(|| {
            #[cfg(target_os = "android")]
            {
                unsafe { Library::new("libvulkan.so").ok() }
            }
            #[cfg(not(target_os = "android"))]
            None
        })
        .as_ref()
}

pub unsafe fn resolve_dispatch() -> Result<VulkanDispatch, String> {
    let lib = get_vulkan_library().ok_or("Failed to load libvulkan.so")?;
    let gpa: Symbol<PFN_vkGetInstanceProcAddr> = lib
        .get(b"vkGetInstanceProcAddr\0")
        .map_err(|e| format!("Failed to find vkGetInstanceProcAddr: {e}"))?;
    let get_instance_proc_addr = *gpa;

    macro_rules! load_global {
        ($name:ident, $type:ty) => {{
            let cname = concat!(stringify!($name), "\0");
            let p = get_instance_proc_addr(std::ptr::null_mut(), cname.as_ptr() as *const c_char);
            if p.is_null() {
                return Err(format!("Vulkan: missing symbol {}", stringify!($name)));
            }
            std::mem::transmute::<*mut c_void, $type>(p)
        }};
    }

    let create_instance = load_global!(vkCreateInstance, _);
    let destroy_instance = load_global!(vkDestroyInstance, _);
    let enumerate_physical_devices = load_global!(vkEnumeratePhysicalDevices, _);
    let get_physical_device_queue_family_properties = load_global!(vkGetPhysicalDeviceQueueFamilyProperties, _);
    let create_device = load_global!(vkCreateDevice, _);
    let destroy_device = load_global!(vkDestroyDevice, _);
    let get_device_queue = load_global!(vkGetDeviceQueue, _);
    let create_android_surface_khr = load_global!(vkCreateAndroidSurfaceKHR, _);
    let destroy_surface_khr = load_global!(vkDestroySurfaceKHR, _);
    let get_physical_device_surface_capabilities_khr = load_global!(vkGetPhysicalDeviceSurfaceCapabilitiesKHR, _);
    let get_physical_device_surface_formats_khr = load_global!(vkGetPhysicalDeviceSurfaceFormatsKHR, _);
    let create_swapchain_khr = load_global!(vkCreateSwapchainKHR, _);
    let destroy_swapchain_khr = load_global!(vkDestroySwapchainKHR, _);
    let get_swapchain_images_khr = load_global!(vkGetSwapchainImagesKHR, _);
    let acquire_next_image_khr = load_global!(vkAcquireNextImageKHR, _);
    let queue_present_khr = load_global!(vkQueuePresentKHR, _);
    let create_semaphore = load_global!(vkCreateSemaphore, _);
    let destroy_semaphore = load_global!(vkDestroySemaphore, _);
    let create_fence = load_global!(vkCreateFence, _);
    let destroy_fence = load_global!(vkDestroyFence, _);
    let wait_for_fences = load_global!(vkWaitForFences, _);
    let reset_fences = load_global!(vkResetFences, _);
    let device_wait_idle = load_global!(vkDeviceWaitIdle, _);

    Ok(VulkanDispatch {
        get_instance_proc_addr,
        create_instance,
        destroy_instance,
        enumerate_physical_devices,
        get_physical_device_queue_family_properties,
        create_device,
        destroy_device,
        get_device_queue,
        create_android_surface_khr,
        destroy_surface_khr,
        get_physical_device_surface_capabilities_khr,
        get_physical_device_surface_formats_khr,
        create_swapchain_khr,
        destroy_swapchain_khr,
        get_swapchain_images_khr,
        acquire_next_image_khr,
        queue_present_khr,
        create_semaphore,
        destroy_semaphore,
        create_fence,
        destroy_fence,
        wait_for_fences,
        reset_fences,
        device_wait_idle,
    })
}

// ---------- Vulkan 核心上下文与管线状态 ----------

pub struct VulkanCore {
    pub dispatch: VulkanDispatch,
    pub instance: *mut c_void,
    pub physical_device: *mut c_void,
    pub device: *mut c_void,
    pub queue: *mut c_void,
    pub queue_family_index: u32,
}

unsafe impl Send for VulkanCore {}
unsafe impl Sync for VulkanCore {}

impl Drop for VulkanCore {
    fn drop(&mut self) {
        unsafe {
            if !self.device.is_null() {
                (self.dispatch.device_wait_idle)(self.device);
                (self.dispatch.destroy_device)(self.device, std::ptr::null());
            }
            if !self.instance.is_null() {
                (self.dispatch.destroy_instance)(self.instance, std::ptr::null());
            }
        }
    }
}

pub struct VulkanSwapchain {
    pub surface: u64,
    pub swapchain: u64,
    pub images: Vec<u64>,
    pub width: u32,
    pub height: u32,
    pub format: u32,
    pub color_space: u32,
    pub is_hdr: bool,
}

impl VulkanCore {
    pub fn init() -> Result<Self, String> {
        unsafe {
            let dispatch = resolve_dispatch()?;

            let app_info = VkApplicationInfo {
                s_type: VK_STRUCTURE_TYPE_APPLICATION_INFO,
                p_next: std::ptr::null(),
                p_application_name: b"Gyro Player\0".as_ptr() as *const c_char,
                application_version: 1,
                p_engine_name: b"GyroEngine\0".as_ptr() as *const c_char,
                engine_version: 1,
                api_version: (1 << 22) | (1 << 12), // Vulkan 1.1+
            };

            let instance_extensions = [
                b"VK_KHR_surface\0".as_ptr() as *const c_char,
                b"VK_KHR_android_surface\0".as_ptr() as *const c_char,
            ];

            let instance_ci = VkInstanceCreateInfo {
                s_type: VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
                p_next: std::ptr::null(),
                flags: 0,
                p_application_info: &app_info,
                enabled_layer_count: 0,
                pp_enabled_layer_names: std::ptr::null(),
                enabled_extension_count: instance_extensions.len() as u32,
                pp_enabled_extension_names: instance_extensions.as_ptr(),
            };

            let mut instance: *mut c_void = std::ptr::null_mut();
            let res = (dispatch.create_instance)(&instance_ci, std::ptr::null(), &mut instance);
            if res != VK_SUCCESS || instance.is_null() {
                return Err(format!("vkCreateInstance failed: res={res}"));
            }

            let mut device_count: u32 = 0;
            (dispatch.enumerate_physical_devices)(instance, &mut device_count, std::ptr::null_mut());
            if device_count == 0 {
                (dispatch.destroy_instance)(instance, std::ptr::null());
                return Err("No Vulkan physical devices found".into());
            }

            let mut physical_devices = vec![std::ptr::null_mut(); device_count as usize];
            (dispatch.enumerate_physical_devices)(instance, &mut device_count, physical_devices.as_mut_ptr());
            let physical_device = physical_devices[0];

            let mut queue_family_count: u32 = 0;
            (dispatch.get_physical_device_queue_family_properties)(
                physical_device,
                &mut queue_family_count,
                std::ptr::null_mut(),
            );
            let mut queue_families = vec![
                VkQueueFamilyProperties {
                    queue_flags: 0,
                    queue_count: 0,
                    timestamp_valid_bits: 0,
                    min_image_transfer_granularity: [0, 0, 0],
                };
                queue_family_count as usize
            ];
            (dispatch.get_physical_device_queue_family_properties)(
                physical_device,
                &mut queue_family_count,
                queue_families.as_mut_ptr(),
            );

            let queue_family_index = queue_families
                .iter()
                .position(|q| q.queue_flags & VK_QUEUE_GRAPHICS_BIT != 0)
                .ok_or_else(|| "No graphics queue family found".to_string())? as u32;

            let priority = 1.0f32;
            let queue_ci = VkDeviceQueueCreateInfo {
                s_type: VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
                p_next: std::ptr::null(),
                flags: 0,
                queue_family_index,
                queue_count: 1,
                p_queue_priorities: &priority,
            };

            let device_extensions = [b"VK_KHR_swapchain\0".as_ptr() as *const c_char];

            let device_ci = VkDeviceCreateInfo {
                s_type: VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
                p_next: std::ptr::null(),
                flags: 0,
                queue_create_info_count: 1,
                p_queue_create_infos: &queue_ci,
                enabled_layer_count: 0,
                pp_enabled_layer_names: std::ptr::null(),
                enabled_extension_count: device_extensions.len() as u32,
                pp_enabled_extension_names: device_extensions.as_ptr(),
                p_enabled_features: std::ptr::null(),
            };

            let mut device: *mut c_void = std::ptr::null_mut();
            let res = (dispatch.create_device)(physical_device, &device_ci, std::ptr::null(), &mut device);
            if res != VK_SUCCESS || device.is_null() {
                (dispatch.destroy_instance)(instance, std::ptr::null());
                return Err(format!("vkCreateDevice failed: res={res}"));
            }

            let mut queue: *mut c_void = std::ptr::null_mut();
            (dispatch.get_device_queue)(device, queue_family_index, 0, &mut queue);

            nlog("Vulkan: 成功初始化 VulkanCore (Instance, Device, Graphics Queue 就绪)");

            Ok(VulkanCore {
                dispatch,
                instance,
                physical_device,
                device,
                queue,
                queue_family_index,
            })
        }
    }

    /// 创建适配 10-bit HDR / SDR 的 Android 原生 Swapchain
    pub fn create_swapchain_for_window(
        &self,
        window: *mut c_void,
        prefer_hdr: bool,
    ) -> Result<VulkanSwapchain, String> {
        unsafe {
            let surface_ci = VkAndroidSurfaceCreateInfoKHR {
                s_type: VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR,
                p_next: std::ptr::null(),
                flags: 0,
                window,
            };

            let mut surface: u64 = 0;
            let res = (self.dispatch.create_android_surface_khr)(
                self.instance,
                &surface_ci,
                std::ptr::null(),
                &mut surface,
            );
            if res != VK_SUCCESS || surface == 0 {
                return Err(format!("vkCreateAndroidSurfaceKHR failed: {res}"));
            }

            let mut caps = VkSurfaceCapabilitiesKHR {
                min_image_count: 0,
                max_image_count: 0,
                current_extent: VkExtent2D::default(),
                min_image_extent: VkExtent2D::default(),
                max_image_extent: VkExtent2D::default(),
                max_image_array_layers: 0,
                supported_transforms: 0,
                current_transform: 0,
                supported_composite_alpha: 0,
                supported_usage_flags: 0,
            };
            (self.dispatch.get_physical_device_surface_capabilities_khr)(
                self.physical_device,
                surface,
                &mut caps,
            );

            let mut format_count: u32 = 0;
            (self.dispatch.get_physical_device_surface_formats_khr)(
                self.physical_device,
                surface,
                &mut format_count,
                std::ptr::null_mut(),
            );
            let mut formats = vec![
                VkSurfaceFormatKHR {
                    format: 0,
                    color_space: 0,
                };
                format_count as usize
            ];
            (self.dispatch.get_physical_device_surface_formats_khr)(
                self.physical_device,
                surface,
                &mut format_count,
                formats.as_mut_ptr(),
            );

            // 优先选取 10-bit HDR 格式 (PQ ST2084 或 HLG)，若不支持则优雅回退至 8-bit sRGB
            let (chosen_format, chosen_cs, is_hdr) = if prefer_hdr {
                if let Some(f) = formats.iter().find(|f| {
                    f.format == VK_FORMAT_A2B10G10R10_UNORM_PACK32
                        && (f.color_space == VK_COLOR_SPACE_HDR10_ST2084_EXT
                            || f.color_space == VK_COLOR_SPACE_BT2020_HLG_EXT)
                }) {
                    (f.format, f.color_space, true)
                } else if let Some(f) = formats.iter().find(|f| f.format == VK_FORMAT_A2B10G10R10_UNORM_PACK32) {
                    (f.format, f.color_space, true)
                } else {
                    (formats[0].format, formats[0].color_space, false)
                }
            } else {
                (formats[0].format, formats[0].color_space, false)
            };

            let width = caps.current_extent.width.max(1);
            let height = caps.current_extent.height.max(1);
            let image_count = (caps.min_image_count + 1).clamp(2, caps.max_image_count.max(3));

            let swapchain_ci = VkSwapchainCreateInfoKHR {
                s_type: VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR,
                p_next: std::ptr::null(),
                flags: 0,
                surface,
                min_image_count: image_count,
                image_format: chosen_format,
                image_color_space: chosen_cs,
                image_extent: VkExtent2D { width, height },
                image_array_layers: 1,
                image_usage: VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
                image_sharing_mode: 0,
                queue_family_index_count: 0,
                p_queue_family_indices: std::ptr::null(),
                pre_transform: caps.current_transform,
                composite_alpha: 1, // VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR
                present_mode: VK_PRESENT_MODE_FIFO_KHR,
                clipped: 1,
                old_swapchain: 0,
            };

            let mut swapchain: u64 = 0;
            let res = (self.dispatch.create_swapchain_khr)(
                self.device,
                &swapchain_ci,
                std::ptr::null(),
                &mut swapchain,
            );
            if res != VK_SUCCESS || swapchain == 0 {
                (self.dispatch.destroy_surface_khr)(self.instance, surface, std::ptr::null());
                return Err(format!("vkCreateSwapchainKHR failed: {res}"));
            }

            let mut sc_image_count: u32 = 0;
            (self.dispatch.get_swapchain_images_khr)(self.device, swapchain, &mut sc_image_count, std::ptr::null_mut());
            let mut images = vec![0u64; sc_image_count as usize];
            (self.dispatch.get_swapchain_images_khr)(self.device, swapchain, &mut sc_image_count, images.as_mut_ptr());

            nlog(&format!(
                "Vulkan: 创建 Swapchain 成功 dims={}x{} format={} cs={} is_hdr={} images={}",
                width, height, chosen_format, chosen_cs, is_hdr, images.len()
            ));

            Ok(VulkanSwapchain {
                surface,
                swapchain,
                images,
                width,
                height,
                format: chosen_format,
                color_space: chosen_cs,
                is_hdr,
            })
        }
    }

    /// 构建对接 mpv_render_context_create 的 Vulkan 初始化参数
    pub fn create_mpv_init_params(&self) -> MpvVulkanInitParams {
        MpvVulkanInitParams {
            instance: self.instance,
            physical_device: self.physical_device,
            device: self.device,
            queue: self.queue,
            queue_family_index: self.queue_family_index,
            get_instance_proc_addr: self.dispatch.get_instance_proc_addr,
        }
    }
}

// ---------- 策略模式：Vulkan 渲染策略实现 ----------

pub struct VulkanRenderStrategy {
    pub core: VulkanCore,
    pub swapchain: Option<VulkanSwapchain>,
    pub window: *mut c_void,
    pub image_available_semaphore: u64,
    pub render_finished_semaphore: u64,
    pub is_ready: bool,
}

unsafe impl Send for VulkanRenderStrategy {}
unsafe impl Sync for VulkanRenderStrategy {}

impl VulkanRenderStrategy {
    pub fn new() -> Result<Self, String> {
        let core = VulkanCore::init()?;
        let sem_ci = VkSemaphoreCreateInfo {
            s_type: VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
            p_next: std::ptr::null(),
            flags: 0,
        };
        let mut sem_avail = 0u64;
        let mut sem_finish = 0u64;
        unsafe {
            let res1 = (core.dispatch.create_semaphore)(core.device, &sem_ci, std::ptr::null(), &mut sem_avail);
            let res2 = (core.dispatch.create_semaphore)(core.device, &sem_ci, std::ptr::null(), &mut sem_finish);
            if res1 != VK_SUCCESS || res2 != VK_SUCCESS {
                if sem_avail != 0 {
                    (core.dispatch.destroy_semaphore)(core.device, sem_avail, std::ptr::null());
                }
                if sem_finish != 0 {
                    (core.dispatch.destroy_semaphore)(core.device, sem_finish, std::ptr::null());
                }
                return Err("Failed to create Vulkan semaphores".into());
            }
        }
        Ok(Self {
            core,
            swapchain: None,
            window: std::ptr::null_mut(),
            image_available_semaphore: sem_avail,
            render_finished_semaphore: sem_finish,
            is_ready: false,
        })
    }
}

impl RenderStrategy for VulkanRenderStrategy {
    fn name(&self) -> &'static str {
        "Vulkan (10-bit HDR Swapchain)"
    }

    fn backend_type(&self) -> RenderBackend {
        RenderBackend::Vulkan
    }

    fn attach_surface(&mut self, window: *mut c_void, _mpv_render_ctx: &mut *mut c_void) -> Result<(), i32> {
        if window.is_null() {
            return Err(-1);
        }
        self.window = window;
        if let Some(sc) = self.swapchain.take() {
            unsafe {
                (self.core.dispatch.destroy_swapchain_khr)(self.core.device, sc.swapchain, std::ptr::null());
                (self.core.dispatch.destroy_surface_khr)(self.core.instance, sc.surface, std::ptr::null());
            }
        }
        match self.core.create_swapchain_for_window(window, true) {
            Ok(sc) => {
                self.swapchain = Some(sc);
                self.is_ready = true;
                nlog("Vulkan: attach_surface 成功绑定 ANativeWindow 并初始化 Swapchain");
                Ok(())
            }
            Err(e) => {
                nlog(&format!("Vulkan: attach_surface 失败: {e}"));
                self.is_ready = false;
                Err(-2)
            }
        }
    }

    fn detach_surface(&mut self) {
        self.is_ready = false;
        unsafe {
            if !self.core.device.is_null() {
                (self.core.dispatch.device_wait_idle)(self.core.device);
            }
            if let Some(sc) = self.swapchain.take() {
                (self.core.dispatch.destroy_swapchain_khr)(self.core.device, sc.swapchain, std::ptr::null());
                (self.core.dispatch.destroy_surface_khr)(self.core.instance, sc.surface, std::ptr::null());
            }
        }
        self.window = std::ptr::null_mut();
        nlog("Vulkan: detach_surface 完成，Swapchain 与 Surface 已释放");
    }

    fn is_ready(&self) -> bool {
        self.is_ready && self.swapchain.is_some()
    }

    fn ensure_render_context(&mut self, mpv_render_ctx: &mut *mut c_void) -> bool {
        if !mpv_render_ctx.is_null() {
            return true;
        }
        let mut success = false;
        with_api_logged("mpv_render_context_create(vulkan)", |api| {
            let (_guard, handle) = match unsafe { mpv_handle_guarded() } {
                Ok(h) => h,
                Err(_) => return,
            };
            let mut init_params = self.core.create_mpv_init_params();
            let api_type = CString::new("vulkan").unwrap();
            let mut params = [
                MpvRenderParam {
                    param_type: MPV_RENDER_PARAM_API_TYPE,
                    data: api_type.as_ptr() as *mut c_void,
                },
                MpvRenderParam {
                    param_type: MPV_RENDER_PARAM_VULKAN_INIT_PARAMS,
                    data: &mut init_params as *mut _ as *mut c_void,
                },
                MpvRenderParam {
                    param_type: 0,
                    data: std::ptr::null_mut(),
                },
            ];
            let rc = unsafe { (api.mpv_render_context_create)(mpv_render_ctx, handle, params.as_mut_ptr()) };
            if rc == MPV_ERROR_SUCCESS && !mpv_render_ctx.is_null() {
                unsafe {
                    (api.mpv_render_context_set_update_callback)(
                        *mpv_render_ctx,
                        Some(crate::render::mpv_update_callback),
                        std::ptr::null_mut(),
                    );
                }
                nlog("Vulkan: 成功创建 mpv_render_context 并绑定回调");
                success = true;
            } else {
                nlog(&format!("Vulkan: 创建 mpv_render_context 失败 (rc={rc})，说明当前 libmpv 未启用 placebo vulkan 后端"));
                success = false;
            }
        });
        success
    }

    fn free_render_context(&mut self, mpv_render_ctx: &mut *mut c_void) {
        if mpv_render_ctx.is_null() {
            return;
        }
        with_api_logged("mpv_render_context_free(vulkan)", |api| unsafe {
            (api.mpv_render_context_set_update_callback)(*mpv_render_ctx, None, std::ptr::null_mut());
            (api.mpv_render_context_free)(*mpv_render_ctx);
            *mpv_render_ctx = std::ptr::null_mut();
        });
    }

    fn draw_frame(&mut self, mpv_render_ctx: *mut c_void, _force: bool) -> DrawOutcome {
        if !self.is_ready() || mpv_render_ctx.is_null() {
            return DrawOutcome::Skipped;
        }
        let sc = match self.swapchain.as_ref() {
            Some(s) => s,
            None => return DrawOutcome::Skipped,
        };

        unsafe {
            let mut image_index: u32 = 0;
            let acquire_res = (self.core.dispatch.acquire_next_image_khr)(
                self.core.device,
                sc.swapchain,
                1_000_000_000,
                self.image_available_semaphore,
                0,
                &mut image_index,
            );

            if acquire_res == VK_ERROR_OUT_OF_DATE_KHR || acquire_res == VK_SUBOPTIMAL_KHR {
                nlog("Vulkan: 收到 Swapchain 尺寸过期信号，重新创建 Swapchain");
                if let Ok(new_sc) = self.core.create_swapchain_for_window(self.window, true) {
                    if let Some(old_sc) = self.swapchain.replace(new_sc) {
                        (self.core.dispatch.destroy_swapchain_khr)(self.core.device, old_sc.swapchain, std::ptr::null());
                        (self.core.dispatch.destroy_surface_khr)(self.core.instance, old_sc.surface, std::ptr::null());
                    }
                }
                return DrawOutcome::Skipped;
            } else if acquire_res != VK_SUCCESS {
                return DrawOutcome::Skipped;
            }

            let sc = self.swapchain.as_ref().unwrap();
            let mut vk_image = MpvVulkanImage {
                image: sc.images[image_index as usize],
                image_layout: VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                format: sc.format,
                width: sc.width as c_int,
                height: sc.height as c_int,
            };

            let mut block_time: c_int = 0;
            let mut params = [
                MpvRenderParam {
                    param_type: MPV_RENDER_PARAM_VULKAN_IMAGE,
                    data: &mut vk_image as *mut _ as *mut c_void,
                },
                MpvRenderParam {
                    param_type: MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME,
                    data: &mut block_time as *mut _ as *mut c_void,
                },
                MpvRenderParam {
                    param_type: 0,
                    data: std::ptr::null_mut(),
                },
            ];

            let _ = crate::mpv::with_api(|api| {
                (api.mpv_render_context_render)(mpv_render_ctx, params.as_mut_ptr())
            });

            let present_info = VkPresentInfoKHR {
                s_type: VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
                p_next: std::ptr::null(),
                wait_semaphore_count: 0,
                p_wait_semaphores: std::ptr::null(),
                swapchain_count: 1,
                p_swapchains: &sc.swapchain,
                p_image_indices: &image_index,
                p_results: std::ptr::null_mut(),
            };

            let _ = (self.core.dispatch.queue_present_khr)(self.core.queue, &present_info);

            crate::render::G_SESSION.frames_drawn.fetch_add(1, Ordering::Relaxed);
            crate::render::G_SESSION.last_draw_ms.store(crate::monotonic_ms() as u64, Ordering::Release);

            DrawOutcome::Presented
        }
    }

    fn skip_frame(&mut self, mpv_render_ctx: *mut c_void) {
        if !mpv_render_ctx.is_null() {
            unsafe { crate::render::skip_frame_native(mpv_render_ctx) };
        }
    }

    fn destroy(&mut self) {
        self.detach_surface();
        unsafe {
            if self.image_available_semaphore != 0 {
                (self.core.dispatch.destroy_semaphore)(self.core.device, self.image_available_semaphore, std::ptr::null());
                self.image_available_semaphore = 0;
            }
            if self.render_finished_semaphore != 0 {
                (self.core.dispatch.destroy_semaphore)(self.core.device, self.render_finished_semaphore, std::ptr::null());
                self.render_finished_semaphore = 0;
            }
        }
    }
}
