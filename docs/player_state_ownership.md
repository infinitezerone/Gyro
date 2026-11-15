# PlayerScreen 状态归属蓝图

> 2026-11-15 重构轮的收尾记录 + 下一轮手术的设计前置。
> 原则同 [native_review_checkpoints.md](native_review_checkpoints.md)：让时序推演降级为结构约束，
> 凡是"什么时候发生"的判断，尽量变成"发生在哪里"的归属约束。

## 一、现状归属图（重构后）

| 状态簇 | 属主 | 状态 |
|---|---|---|
| 引擎快照 `PlaybackSnapshot` | `engine.state`，屏幕只读 | ✅ 权威清晰，不动 |
| UI 交互状态 | `PlayerState`（showControls / isLocked / activeSheetTab / scrub / zoom / HUD…） | ✅ 已收敛 |
| 视口几何 | `PlayerViewportState`（containerSize / fullscreenContainerSize / videoContainerOrigin / videoContainerSize / appliedBufferSize，写点为命名事件） | ✅ 本轮收敛 |
| 截图 | `PlayerScreenshotController`（PlayerScreenshotHelper.kt） | ✅ |
| 控制层契约 | `PlayerOverlayActions` 单对象 | ✅ 本轮收敛 |
| 手势接线 + zoom 重置时序 | `rememberPlayerGestureModifier`（无条件调用保 PiP 语义） | ✅ 本轮收敛 |
| 缩略图解码 | `loadVideoThumbnail` + `rememberThumbnailCache` | ✅ 去重 |
| Sheet 构造 | `PlayerSheetsHost`（订阅只活在 sheet 可见期） | ✅ |
| 瞬时接线散件 | surfaceViewRef / lastSubTrackId / wasInPipLayout / orientationBeforePip / pagerState / thumbnailCache | ⚠️ 半健康：各自单一消费者，可接受 |
| HDR 徽章 | PlayerScreen 内联 36 行 | ⚠️ 可提取为 component，收益低 |

## 二、下一轮手术（VM 接管）的设计约束

再做下去，收益就不再是搬运而是设计。三条设计约束先钉在这里：

1. **快照解包不下沉**：屏幕里 ~60 个 `snapshot.xxx` 解构 val 是可读性工具，下沉到 VM 只会加一层间接。VM 的正确增量是**领域事件**（换片失败原因、切换来源），不是搬运快照字段。
2. **actions 构造是下一个移位点**：`PlayerOverlayActions` 契约已存在；VM 接管时只需把构造位置从调用点移入 `rememberPlayerScreenActions(engine, viewModel, playerState)`，组件签名不再动。
3. **时序不变量必须 CI 化**：每次状态属主变更，必须同步把涉及的时序不变量（PiP 全屏 rect 不在 PiP 中重算、zoom 重置无条件运行、sheet 订阅只活在 sheet 可见期）钉成 FakeNative 状态机测试，推演一次、CI 永久持有。

## 三、已固化的时序不变量（测试锚点清单）

以下不变量目前由代码结构保证，尚无对应 CI 测试——下一轮按此清单补：

- [ ] 换片窗口内 `updateHdrState` 不读旧文件属性（已有测试 ✓，VideoGeometryCoordinatorTest）
- [ ] `rememberPlayerGestureModifier` 的 zoom 重置在 PiP 中仍然运行
- UI 层无法 JVM 测，需 FakeNative + Robolectric 或下推到纯函数层后测
- [ ] `PlayerViewportState.onContainerSized` 在 PiP 中不得更新 fullscreenContainerSize
- 可测：纯类，直接 JVM 单测
- [ ] START_FILE 清零几何缓存 → 首帧信号前几何必须已知（native 侧，FakeNative 状态机）
- [ ] `PlayerSheetsHost` 的订阅只活在 sheet 可见期（语义由组合位置保证，文档化即可）
- [ ] `normalizeContentFps` 匹配顺序敏感（已有测试 ✓，DisplayFitTest）
