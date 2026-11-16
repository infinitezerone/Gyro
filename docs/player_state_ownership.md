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
- [x] `PlayerViewportState` PiP 冻结不变量（已有测试 ✓，PlayerViewportStateTest）
- [ ] START_FILE 清零几何缓存 → 首帧信号前几何必须已知（native 侧，FakeNative 状态机）
- [ ] `PlayerSheetsHost` 的订阅只活在 sheet 可见期（语义由组合位置保证，文档化即可）
- [ ] `normalizeContentFps` 匹配顺序敏感（已有测试 ✓，DisplayFitTest）

## 四、PiP 进入动画的两个症状（2026-11-15 诊断归档）

**证据链**（SwitchTrace 的 `PIP_PARAMS_APPLY` / `PIP_ENTER_REQUEST` 钉子）：

- 应用侧参数全程正确且新鲜：横屏 sourceRect = `Rect(256, 0 - 2517, 1272)`（剥黑边后的精确视频矩形），enter 请求与最近一次 apply 完全一致。
- 修复前：进入动画进行到一半（enter 后约 550ms），PiP 窗口尺寸（1019×573 → 1160×653）会污染
  `fullscreenContainerSize` 与 `videoBoundsInWindow` → `setPictureInPictureParams` 带着
  错误 hint **在动画中途重放两次**。根因：系统的 PiP mode 回调滞后物理窗口收缩若干帧，
  `isPipLayout` 门控存在空窗。修复：`freezeViewportFullscreen = hideChrome`（含
  `pipRequested`，点击瞬间即冻结），覆盖 `onSizeChanged` 与 `onGloballyPositioned` 两条路径。
- 修复后：进入期间参数重放归零（日志验证）。

**症状裁决**：

1. **偶发遮罩过渡** —— 参数中途重放是高置信根因，修复已消除重放；偶发概率低，未能二次
   复现确认，但机制上已闭环。
2. **横屏黑边延迟消失（必现）** —— 参数正确仍存在 → 坐实是 **ColorOS 对程序化
   `enterPictureInPictureMode` 的动画实现**（整窗快照收缩，不做 sourceRectHint 即时裁剪）；
   上滑自动进入（系统驱动）不受影响。应用层无缓解手段（无参 enter 实验实测无效，已回退）。
   屏幕刚进入时另有 9 次/100ms 的 params 重放（入场动画 bounds 连续变化），
   与本症状无关，值得日后防抖。


## 五、PiP 退出与展开动画下压黑屏归档（2026-11-16 诊断与修复）

**症状**：从 PiP 小窗点击返回全屏时，画面瞬间被压到小窗可视范围下方，悬浮小窗全黑，随后随着窗口拉伸才从底部慢慢露出来，且返回后控制层无法唤起。

**根因与修复**：
1. **`PlayerVideoFeedLayer` 漏门控**：分层重构时 `onGloballyPositioned` 遗漏了 `!freezeViewportFullscreen` 门控，导致小窗坐标写入了 `videoBoundsInWindow`，污染了退出转场的系统 `sourceRectHint` 目标。修复：透传并在 `windowRect` 处恢复该保护。
2. **`pipEntering` 焦点闭锁**：PiP 丢失焦点置位 `pipEntering = true`，返回时因 `isSettling == true`（300ms 延迟）无法清除，转场结束后不再触发焦点变化导致死锁。修复：`LaunchedEffect` 联合监听 `(windowFocused, isInPipMode, isSettling)` 并在退出延迟结束后显式清零。
3. **退小窗第 0 帧修改 orientation**：原先无差别设置 `activity.requestedOrientation = orientationBeforePip`，即使原本为 `UNSPECIFIED` 也强行切 `SENSOR_PORTRAIT`，与 WindowManager 展开动画发生排版冲突。修复：仅在用户显式锁定方向时才恢复。
4. **抽屉状态随进 PiP 关闭**：进入小窗时清理 `activeSheetTab = null`，杜绝退出时触发抽屉弹起与额外的 `sheetShiftY`。
