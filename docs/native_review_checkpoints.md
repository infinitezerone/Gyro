# 端侧底层与跨语言边界工程指南：4+2 质检检查点
> **适用领域**：Android Native (Rust / C++)、JNI 跨语言桥接、EGL / Surface 硬件渲染、跨线程并发管道。  
> **核心定位**：告别底层 API 语法背诵，建立“图纸设计 + 严苛质检 + 自动化验证”的心智模型，主导并驾驭 AI 完成端侧底层工程。

---

## 一、心智模型：图纸与质检官

在底层和系统级工程中，AI 擅长编写 90% 繁琐机械的胶水代码（JNI 签名、参数映射、C/Rust 结构体包装）。人类工程师的核心价值不是“打字员”，而是**架构图纸的设计者与代码防爆的质检官**。

任何合格的 Native / JNI 实现，都必须通过以下 **“4 个微观边界公理”** 与 **“2 个宏观系统维度”** 的严格审查。

```
                  ┌──────────────────────────────────────────────┐
                  │          4 + 2 质检检查点全景图               │
                  └──────────────────────────────────────────────┘
                                         │
        ┌────────────────────────────────┴────────────────────────────────┐
        ▼                                                                 ▼
【微观边界（盯死每一次跨界）】                                   【宏观全局（盯死系统生命周期）】
1. 所有权公理 (Ownership)                                        5. 状态与时序 (Lifecycle & Epoch)
   谁创建、谁持有、谁释放？                                         窗口脱钩、上下文保活、Epoch 防幽灵
2. 上下文公理 (Context)                                          6. 可观测性 (Observability)
   线程亲和性、JNIEnv 绑定、防主线程锁卡死                          锁中毒自愈、错误码透传、拒绝静默失败
3. 隔离性公理 (Isolation)
   Panic 绝不穿透 FFI、ExceptionClear 防自爆
4. 开销公理 (Marshaling)
   杜绝堆内存抖动、禁高频 Ping-Pong 调用
```

---

## 二、微观边界：4 大硬核公理（盯死每一次跨界）

### 检查点 1：所有权公理（Ownership & Lifespan）
> **核心考量：跨越语言边界的每一块内存和句柄，由谁分配？由谁持有？由谁销毁？离开作用域后指针是否有效？**

* **JNI 引用类型闭环**：
  * **局部引用（LocalRef）防溢出**：在循环或高频逻辑中调用的 `GetObjectArrayElement` / `NewStringUTF` 等，默认都在 Android 512 上限的局部引用表中。高频必须手动释放或控制批量。
  * **全局引用（GlobalRef）升级**：跨越单个 JNI 调用周期的对象（如缓存的 `jclass`），必须在主线程通过 `env.new_global_ref(...)` 升级为全局引用；销毁时必须成对执行 `DeleteGlobalRef`。
* **原生内存与 RAII 托管**：
  * Java 传给 Native 的指针（或 Native 返回给 Java 的内存句柄 `nativeHandle: Long`），必须有清晰明确的析构时机，严禁出现**悬挂指针（Dangling Pointer）**或**二次释放（Double Free）**。

---

### 检查点 2：上下文公理（Thread Affinity & Execution Context）
> **核心考量：代码当前在哪个 OS 线程执行？调用环境（Runtime）是否合法？是否存在锁竞争风险？**

* **JNIEnv 严格线程绑定**：
  * `JNIEnv` 是每个线程专属的上下文句柄，**绝对不能跨线程传递或保存为静态变量**。
  * Native 自主创建的后台子线程（如事件线程、渲染线程）反向调用 Java 前，必须通过 `vm.attach_current_thread_as_daemon()` 动态挂载到 JVM。
* **防主线程死锁与卡顿（Anti-ANR）**：
  * **主线程绝不抢底层重锁**：上层 UI 线程通过 JNI 调用 Native 时，严禁同步争抢底层正在解码、渲染或 IO 的互斥锁（Mutex）。
  * 状态获取必须采用**“后台事件流推送到上层缓存（Push），UI 纯内存读取（Pull Cache）”**的异步解耦架构。

---

### 检查点 3：隔离性公理（Error Boundary & Isolation）
> **核心考量：底层发生故障或异常时，能否在边界处拦截，还是会引发整机进程原地蒸发？**

* **Rust Panic 绝不穿透 FFI**：
  * 在 `extern "C"` / `extern "system"` 导出函数最外层，**严禁裸奔 `.unwrap()` / `panic!`**。Panic 穿透 C 调用栈属于未定义行为（UB），会导致操作系统直接 `abort` 强制杀进程。
  * 危险操作必须做好防御判断，或使用 `std::panic::catch_unwind` 兜底。
* **JNI 异常延迟爆炸防御（ExceptionCheck / ExceptionClear）**：
  * Native 跨界调用 Java 方法后，如果 Java 抛出异常，Native 不会自动终止。
  * 每次反向调用后若有失败风险，必须调用 `env.exception_clear()` 清除挂起异常，否则下一次任何 JNI 操作都会直接导致虚拟机闪退崩溃。

---

### 检查点 4：开销公理（Marshaling & Performance Tax）
> **核心考量：跨语言数据传输是否造成了内存拷贝膨胀、堆抖动或性能瓶颈？**

* **杜绝高频堆内存分配（Heap Churn）**：
  * 频繁调用的属性名或命令字符串，**严禁每次调用都动态 `malloc` / `CString::new(...)`**。
  * 必须使用编译期常量或静态只读数据段（如 Rust 的 `c"prop_name"` 常量）。
* **拒绝细粒度 Ping-Pong 往返**：
  * 禁止在每帧渲染循环里通过 JNI 多次往返拉取零散字段。
  * 高频大数据应采用 `DirectByteBuffer`（直接内存零拷贝）或在 Native 内部打包为紧凑结构体一次性交换。

---

## 三、宏观全局：2 大系统维度（盯死模块生命周期）

### 检查点 5：状态与时序（Lifecycle & State Invariants）
> **核心考量：面对屏幕旋转、后台切换、画中画（PiP）及用户快速连击，状态机如何自愈？**

* **Surface 物理窗口解绑与 EGLContext 上下文保活（金蝉脱壳）**：
  * 切后台或销毁界面时，必须在销毁物理 Surface 前调用 `detachSurface()`：
    1. 释放 `ANativeWindow` 裸指针并置空；
    2. 销毁 `EGLSurface`；
    3. **保留 `EGLContext` 存活**（可挂载到 1x1 离屏虚拟 `Pbuffer` 上规避驱动 Bug），确保着色器和解码上下文无缝恢复。
* **Epoch 纪元机制（防幽灵事件回弹）**：
  * 频繁切换视频流或重建播放器时，递增全局 `epoch`。
  * 异步事件流必须携带事件发生时的 `epoch`，上层丢弃所有 `epoch < currentEpoch` 的过期残余事件，彻底避免进度条倒退或状态错乱。

---

### 检查点 6：可观测性与韧性（Observability & Poisoning Defense）
> **核心考量：底层遇到非预期状态时，是死无对证还是有迹可循？锁中毒与异常能否自愈？**

* **杜绝静默失败（No Silent Failures）**：
  * 互斥锁 `Mutex::lock()` 遇到 `PoisonError`、属性获取返回错误码时，**严禁使用空 `if let Ok` 悄悄吞没**。
  * 关键路径必须有系统级日志（`EngineLog` / `nlog`）与明确的错误状态透传。
* **性能追踪与 Trace 打标**：
  * 首帧渲染、管道初始化、关键事件分发需接入 ATrace / Systrace 追踪点，确保出现卡顿时能直接拉出火焰图定位。

---

## 四、自动化裁判体系（真理不靠人猜）

| 审查阶段 | 工具 / 命令 | 裁判重点 |
| :--- | :--- | :--- |
| **编译期静态审查** | `cargo check` / `cargo clippy`<br>`./gradlew lint` | 所有权模型、未使用的裸指针、生命周期标注 |
| **系统违规拦截** | `adb shell setprop debug.checkjni 1` | 局部引用溢出、非法类型转换、跨线程 JNIEnv 滥用 |
| **本地状态机验证** | `./gradlew :core:engine:testDebugUnitTest` | JVM 侧 Flow 协程分发、Epoch 过滤、FakeNative 状态机验证 |
| **设备极限破坏压测** | `./gradlew :core:engine:connectedDebugAndroidTest` | 真实 ANativeWindow 30 次高频 Attach/Detach、50 次销毁重建、Adreno/Mali 驱动兼容性 |

---

## 五、驾驭 AI 的 Spec 需求模板（实操武器）

当你需要让 AI 在当前工程中扩展或重构 Native 功能时，直接将以下提示词结构附加给 AI：

```markdown
请在 Native / JNI 层实现功能：[描述你的具体需求]

必须严格遵守工程质检规范：
1. 【所有权】：所有跨语言引用必须有闭环生命周期，静态类必须升级为 GlobalRef并在退出时释放。
2. 【上下文】：Native 跨线程回调必须先 AttachCurrentThreadAsDaemon；禁止在 UI 线程同步争抢底层耗时锁。
3. 【隔离性】：FFI 导出边界禁止裸奔 unwrap()，所有 Result 必须显式处理；反向调 Java 后必须在失败路径做 exception_clear()。
4. 【开销】：静态常量使用编译期常数字符串，禁止高频堆内存反复申请与销毁。
5. 【时序】：状态流转必须携带 Epoch 标记，涉及 Surface 必须严格遵循 EGLContext 保活与 ANativeWindow 独立解绑规范。
6. 【测试】：在 FakeMpvNativeBridge 中补齐 Mock 实现，并提供覆盖异常与时序边界的单元测试。
```
