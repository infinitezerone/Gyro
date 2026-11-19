package dev.gyro.feature.player.component

import android.graphics.BlurMaskFilter
import android.graphics.Paint as AndroidPaint
import android.os.SystemClock
import dev.gyro.feature.player.PlayerScrubState
import dev.gyro.feature.player.gesture.DoubleTapSide
import dev.gyro.feature.player.gesture.GestureHudState
import dev.gyro.core.model.HwdecMode

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt
import dev.gyro.feature.player.component.sheet.common.SheetAccentBlue
import dev.gyro.core.designsystem.R
import dev.gyro.core.model.time.formatDuration
import dev.gyro.core.designsystem.icon.GyroIcons

private val HudPillFillDark = Color.Black.copy(alpha = 0.58f)
private val HudPillBorderColor = Color.White.copy(alpha = 0.18f)
private val HudPillInvertedInk = Color(0xFF141414)
private val LevelHudWidth = 168.dp
private val LevelHudExpandedHeight = 34.dp
private val LevelHudCompactHeight = 8.dp
internal const val LEVEL_HUD_COLLAPSE_MIN_ELAPSED_MS = 380L
internal const val LEVEL_HUD_COLLAPSE_MIN_DELTA = 0.05f

internal enum class LevelHudKind { NONE, BRIGHTNESS, VOLUME }

internal data class LevelHudMorphState(
    val activeKind: LevelHudKind = LevelHudKind.NONE,
    val sessionStartMs: Long = 0L,
    val lastRatio: Float = 0f,
    val cumulativeDelta: Float = 0f,
    val isCompact: Boolean = false
) {
    fun onHudUpdate(kind: LevelHudKind, ratio: Float, nowMs: Long): LevelHudMorphState {
        if (kind == LevelHudKind.NONE) {
            // Keep isCompact during AnimatedVisibility exit fade-out so the pill never pops back open.
            return if (activeKind == LevelHudKind.NONE) this else copy(activeKind = LevelHudKind.NONE)
        }
        if (kind != activeKind) {
            return LevelHudMorphState(
                activeKind = kind,
                sessionStartMs = nowMs,
                lastRatio = ratio,
                cumulativeDelta = 0f,
                isCompact = false
            )
        }
        val delta = abs(ratio - lastRatio)
        if (delta == 0f) return this
        val updatedDelta = cumulativeDelta + delta
        val elapsedMs = (nowMs - sessionStartMs).coerceAtLeast(0L)
        val shouldCollapse = isCompact || (
            elapsedMs >= LEVEL_HUD_COLLAPSE_MIN_ELAPSED_MS &&
                updatedDelta >= LEVEL_HUD_COLLAPSE_MIN_DELTA
            )
        return copy(
            lastRatio = ratio,
            cumulativeDelta = updatedDelta,
            isCompact = shouldCollapse
        )
    }
}

/**
 * Clean, lightweight HUD overlay:
 * 1. Top-center scrub time preview while dragging the progress bar（屏幕正上方统一时间预览）.
 * 2. Center floating capsule slider for Volume & Brightness（居中呈现，避开顶/底栏）.
 * 3. Top-center floating pill for 2.0x playback speed.
 * 4. YouTube-style side jump pills for Double-Tap.
 * Note: Seeking HUD directly reuses the bottom progress bar in PlayerControlsOverlay.
 */
@Composable
fun BoxScope.PlayerHudOverlay(
    hudState: GestureHudState,
    scrubState: PlayerScrubState,
    durationMs: () -> Long,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        // 0. Top-Center Scrub & Gesture Seek Time Preview（拖拽进度条或屏幕水平滑动快进/快退，屏幕正上方显示目标时间与偏差）
        var lastValidTargetMs by remember { mutableLongStateOf(0L) }
        val isSeeking = (scrubState.isActive && !scrubState.isPreciseSeeking) || hudState is GestureHudState.Seek
        val total = durationMs()
        if (isSeeking && total > 0L) {
            val liveMs = when {
                hudState is GestureHudState.Seek -> hudState.targetMs
                scrubState.isActive -> (scrubState.fraction * total).toLong().coerceIn(0L, total)
                else -> lastValidTargetMs
            }
            lastValidTargetMs = liveMs
        }

        AnimatedVisibility(
            visible = isSeeking && total > 0L,
            enter = fadeIn(animationSpec = tween(100)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .displayCutoutPadding()
                .statusBarsPadding()
                .padding(top = 64.dp)
        ) {
            if (total > 0L) {
                ScrubTimePreview(
                    targetMs = lastValidTargetMs,
                    totalMs = total
                )
            }
        }

        // 1. 屏幕正上方两段式形态收窄药丸（音量 / 亮度 HUD：初现为纯白一体胶囊带图标，持续滑动自动收窄成 8dp 极细白条）
        var lastLevelHud by remember { mutableStateOf<GestureHudState?>(null) }
        var levelMorphState by remember { mutableStateOf(LevelHudMorphState()) }
        val currentLevelKind = when (hudState) {
            is GestureHudState.Brightness -> LevelHudKind.BRIGHTNESS
            is GestureHudState.Volume -> LevelHudKind.VOLUME
            else -> LevelHudKind.NONE
        }
        val currentLevelRatio = when (hudState) {
            is GestureHudState.Brightness -> hudState.brightnessRatio
            is GestureHudState.Volume -> hudState.volumeRatio
            else -> 0f
        }
        if (currentLevelKind != LevelHudKind.NONE) {
            lastLevelHud = hudState
        }
        SideEffect {
            val next = levelMorphState.onHudUpdate(
                kind = currentLevelKind,
                ratio = currentLevelRatio,
                nowMs = SystemClock.uptimeMillis()
            )
            if (next != levelMorphState) {
                levelMorphState = next
            }
        }

        AnimatedVisibility(
            visible = currentLevelKind != LevelHudKind.NONE,
            enter = fadeIn(animationSpec = tween(150)) + slideInVertically(animationSpec = tween(150)) { -it / 2 },
            exit = fadeOut(animationSpec = tween(250)) + slideOutVertically(animationSpec = tween(200)) { -it / 2 },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .displayCutoutPadding()
                .statusBarsPadding()
                .padding(top = 64.dp)
        ) {
            when (val displayHud = lastLevelHud) {
                is GestureHudState.Brightness -> {
                    TopFillPillHud(
                        icon = GyroIcons.Brightness,
                        contentDescription = stringResource(R.string.cd_brightness),
                        ratio = displayHud.brightnessRatio,
                        isCompact = levelMorphState.isCompact
                    )
                }
                is GestureHudState.Volume -> {
                    val icon = when {
                        displayHud.percent <= 0 -> GyroIcons.VolumeMute
                        displayHud.percent <= 50 -> GyroIcons.VolumeLow
                        else -> GyroIcons.VolumeHigh
                    }
                    TopFillPillHud(
                        icon = icon,
                        contentDescription = stringResource(R.string.cd_volume),
                        ratio = displayHud.volumeRatio,
                        isCompact = levelMorphState.isCompact
                    )
                }
                else -> Unit
            }
        }

        // 2. Top-Center Speed Boost Capsule (Long-press 2.0x)
        AnimatedVisibility(
            visible = hudState is GestureHudState.Speed,
            enter = fadeIn(animationSpec = tween(150)) + slideInVertically(animationSpec = tween(150)) { -it / 2 },
            exit = fadeOut(animationSpec = tween(300)) + slideOutVertically(animationSpec = tween(300)) { -it / 2 },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .displayCutoutPadding()
                .statusBarsPadding()
                .padding(top = 64.dp)
        ) {
            val speedState = hudState as? GestureHudState.Speed
            if (speedState != null) {
                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .exteriorPillShadow()
                        .clip(CircleShape)
                        .background(HudPillFillDark)
                        .border(0.5.dp, HudPillBorderColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = GyroIcons.FastForward,
                            contentDescription = stringResource(R.string.speed_title),
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.hud_speed_indicator, speedState.speed),
                            style = TextStyle(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                    }
                }
            }
        }

        // 2.5. 屏幕正上方切集指示胶囊（上下滑动直接切视频）
        AnimatedVisibility(
            visible = hudState is GestureHudState.SwitchVideo,
            enter = fadeIn(animationSpec = tween(150)) + slideInVertically(animationSpec = tween(150)) { -it / 2 },
            exit = fadeOut(animationSpec = tween(300)) + slideOutVertically(animationSpec = tween(300)) { -it / 2 },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .displayCutoutPadding()
                .statusBarsPadding()
                .padding(top = 64.dp)
        ) {
            val switchState = hudState as? GestureHudState.SwitchVideo
            if (switchState != null) {
                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .exteriorPillShadow()
                        .clip(CircleShape)
                        .background(HudPillFillDark)
                        .border(0.5.dp, HudPillBorderColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (switchState.isNext) GyroIcons.SkipNext else GyroIcons.SkipPrevious,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (switchState.isNext) stringResource(R.string.cd_next_video) else stringResource(R.string.cd_prev_video),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // 3. 纯透明双击快进/快退指示（无胶囊背景、无圆圈，仅保留纯净文字与箭头图标）
        val doubleTapState = hudState as? GestureHudState.DoubleTap
        val isDoubleTapLeft = doubleTapState?.side == DoubleTapSide.LEFT
        AnimatedVisibility(
            visible = doubleTapState != null,
            enter = fadeIn(animationSpec = tween(100)) + scaleIn(initialScale = 0.85f),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier
                .align(if (isDoubleTapLeft) Alignment.CenterStart else Alignment.CenterEnd)
                .displayCutoutPadding()
                .padding(horizontal = 64.dp)
        ) {
            if (doubleTapState != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (isDoubleTapLeft) GyroIcons.FastRewind else GyroIcons.FastForward,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = stringResource(
                            if (isDoubleTapLeft) R.string.hud_rewind_seconds else R.string.hud_forward_seconds,
                            doubleTapState.seconds
                        ),
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontFeatureSettings = "tnum",
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.90f),
                                blurRadius = 8f
                            )
                        )
                    )
                }
            }
        }
    }
}

/**
 * 屏幕正上方拖拽进度时间预览（纯净大字无胶囊，一眼看清，视野开阔通透）
 */
@Composable
private fun ScrubTimePreview(
    targetMs: Long,
    totalMs: Long
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = formatDuration(targetMs, padHours = true),
            style = TextStyle(
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontFeatureSettings = "tnum",
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.90f),
                    blurRadius = 12f
                )
            )
        )
        Text(
            text = " / " + formatDuration(totalMs, padHours = true),
            style = TextStyle(
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.70f),
                fontFeatureSettings = "tnum",
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.90f),
                    blurRadius = 12f
                )
            )
        )
    }
}

/**
 * 紧凑诊断浮层 (StatsDiagnosticOverlay)：
 * 控制层隐藏时常驻画面左上角，替代 mpv stats 页 1 的核心读数。
 * 需要遮蔽系统状态栏/刘海场景由调用方负责 insets。
 */
@Composable
fun StatsDiagnosticOverlay(
    fps: Double,
    droppedFrames: Long,
    hwdecMode: HwdecMode,
    bitrate: Long = 0L,
    hdrLabel: String? = null,
    sourceWidth: Int = 0,
    sourceHeight: Int = 0,
    sourceFrameRate: Float = 0f,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.60f),
        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.12f)),
        modifier = modifier
    ) {
        // 60 samples, one per second. The number says where it is now; the line says how it got
        // there, which is the only way a stutter is visible at all - a single frame of 58 fps
        // reads as healthy until you see it fell from 60.
        // Only the bitrate gets a line. Frame rate and drop count are near-constant by design -
        // a flat line next to them would be decoration, not information.
        val bitrateHistory = remember { mutableStateListOf<Float>() }
        val latestBitrate by rememberUpdatedState(bitrate)

        LaunchedEffect(Unit) {
            while (isActive) {
                delay(1000)
                pushSample(bitrateHistory, latestBitrate.toFloat())
            }
        }

        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            // What the file is, before what is happening to it. Resolution and source frame rate
            // were missing entirely, which made the panel unable to answer "is this really 1080p".
            val spec = if (sourceHeight > 0) "${sourceHeight}p" else ""
            if (spec.isNotEmpty()) {
                Text(
                    text = spec,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.75f)
                )
            }
            if (!hdrLabel.isNullOrBlank()) {
                Text(
                    text = hdrLabel,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = SheetAccentBlue,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }

            // Grouped because the numbers answer different questions: VIDEO is the stream, RENDER
            // is what this device manages to do with it.
            DiagnosticGroup("VIDEO")
            // "actual/source": a repeat of the spec line above would say nothing, while the pair
            // answers whether the device is keeping up with the file.
            val fpsText = when {
                fps > 0 && sourceFrameRate > 0f -> "${fmt1(fps)}/${fmt2(sourceFrameRate.toDouble())}"
                fps > 0 -> fmt1(fps)
                else -> "--"
            }
            DiagnosticRow(label = "FPS", value = fpsText)
            DiagnosticRow(label = "DROP", value = droppedFrames.toString())
            DiagnosticRow(label = "RATE", value = fmtBitrate(bitrate), spark = bitrateHistory)

            // Playback speed and vsync used to live here. Speed is a setting, not a measurement,
            // and vsync-ratio is only published in display-sync mode - this pipeline does its own
            // present timing, so it read "--" forever.
            DiagnosticGroup("RENDER")
            DiagnosticRow(label = "DEC", value = if (hwdecMode.isHardware) "HW" else "SW")
        }
    }
}

private const val SPARK_CAPACITY = 60

private fun pushSample(list: androidx.compose.runtime.snapshots.SnapshotStateList<Float>, value: Float) {
    list.add(value)
    if (list.size > SPARK_CAPACITY) list.removeAt(0)
}

/**
 * A 60-sample sparkline: no axes, no labels, because there is no room and nothing to read off it
 * except shape. Flat line means steady, a dip means the thing actually happened.
 */
@Composable
private fun Sparkline(values: List<Float>, modifier: Modifier = Modifier) {
    if (values.size < 2) {
        Spacer(modifier)
        return
    }
    val minValue = values.min()
    val maxValue = values.max()
    val span = (maxValue - minValue).coerceAtLeast(0.001f)
    Canvas(modifier) {
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = index * step
            val y = size.height - ((value - minValue) / span) * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = SheetAccentBlue, style = Stroke(width = 1.2.dp.toPx()))
    }
}

private fun fmt1(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)

private fun fmt2(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)

private fun fmtBitrate(bits: Long): String = when {
    bits >= 1_000_000L -> String.format(java.util.Locale.US, "%.1fM", bits / 1_000_000.0)
    bits >= 1_000L -> String.format(java.util.Locale.US, "%.0fK", bits / 1_000.0)
    else -> "--"
}

@Composable
private fun DiagnosticGroup(title: String) {
    Text(
        text = title,
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White.copy(alpha = 0.40f),
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 1.dp)
    )
}

@Composable
private fun DiagnosticRow(label: String, value: String, spark: List<Float>? = null) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.width(38.dp)
        )
        Text(
            text = value,
            style = TextStyle(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontFeatureSettings = "tnum"
            ),
            modifier = Modifier.width(66.dp)
        )
        // Fixed width whether or not there is a series, so the columns line up between rows that
        // have history and rows that do not.
        if (spark != null) {
            Sparkline(
                values = spark,
                modifier = Modifier
                    .width(52.dp)
                    .height(12.dp)
            )
        } else {
            Spacer(modifier = Modifier.width(52.dp))
        }
    }
}

/**
 * 屏幕正上方一体式纯白填充药丸 HUD (TopFillPillHud)：
 * 整颗药丸直接从左到右做纯白实心填充，利用像素级裁剪让图标在白底上自动反转为深墨色；
 * 持续调节时平滑收窄为极细纯白胶囊条并淡出图标，减少观影遮挡。
 */
@Composable
internal fun TopFillPillHud(
    icon: ImageVector,
    contentDescription: String,
    ratio: Float,
    isCompact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val clampedRatio = ratio.coerceIn(0f, 1f)
    val animatedRatio by animateFloatAsState(
        targetValue = clampedRatio,
        animationSpec = spring(stiffness = Spring.StiffnessHigh, dampingRatio = Spring.DampingRatioNoBouncy),
        label = "hudRatio"
    )
    val animatedHeight by animateDpAsState(
        targetValue = if (isCompact) LevelHudCompactHeight else LevelHudExpandedHeight,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy),
        label = "hudHeight"
    )
    val iconAlpha by animateFloatAsState(
        targetValue = if (isCompact) 0f else 1f,
        animationSpec = tween(durationMillis = if (isCompact) 110 else 180),
        label = "hudIconAlpha"
    )
    val a11yDescription = "$contentDescription ${(clampedRatio * 100f).roundToInt()}%"

    Box(
        modifier = modifier
            .width(LevelHudWidth)
            .height(LevelHudExpandedHeight)
            .semantics(mergeDescendants = true) {
                this.contentDescription = a11yDescription
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(animatedHeight)
                .exteriorPillShadow()
                .clip(CircleShape)
                .background(HudPillFillDark)
        ) {
            if (iconAlpha > 0.01f) {
                PillHudIconOverlay(
                    icon = icon,
                    tint = Color.White,
                    alpha = iconAlpha
                )
            }

            // 填充与反色层：按比例裁剪为纯白实心，内部的深墨色图标随进度条扫过自动无缝反色
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics {}
                    .drawWithContent {
                        val fillWidth = size.width * animatedRatio
                        if (fillWidth > 0f) {
                            clipRect(right = fillWidth) {
                                this@drawWithContent.drawContent()
                            }
                        }
                    }
                    .background(Color.White)
            ) {
                if (iconAlpha > 0.01f) {
                    PillHudIconOverlay(
                        icon = icon,
                        tint = HudPillInvertedInk,
                        alpha = iconAlpha
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(0.5.dp, HudPillBorderColor, CircleShape)
            )
        }
    }
}

private fun Modifier.exteriorPillShadow(
    blurRadius: Dp = 10.dp,
    offsetY: Dp = 2.dp,
    color: Color = Color.Black.copy(alpha = 0.24f)
): Modifier = drawWithCache {
    val radius = size.height / 2f
    val cornerRadius = CornerRadius(radius, radius)
    val pillPath = Path().apply {
        addRoundRect(RoundRect(Rect(Offset.Zero, size), cornerRadius))
    }
    val blurPx = blurRadius.toPx()
    val offsetYPx = offsetY.toPx()
    // 模糊阴影只能落在平台 Paint 上（Compose 的 Paint 没有 maskFilter），
    // 因此这里直接用框架 Paint 绘制，不再走 asFrameworkPaint 转换。
    val shadowColorArgb = color.toArgb()
    val shadowPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        // 显式 this：不加会被解析成外层 Composable 参数里的 val color。
        this.color = shadowColorArgb
        if (blurPx > 0f) {
            maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
        }
    }
    onDrawBehind {
        clipPath(pillPath, clipOp = ClipOp.Difference) {
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawRoundRect(
                    0f,
                    offsetYPx,
                    size.width,
                    size.height + offsetYPx,
                    radius,
                    radius,
                    shadowPaint
                )
            }
        }
    }
}

@Composable
private fun PillHudIconOverlay(
    icon: ImageVector,
    tint: Color,
    alpha: Float
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .requiredSize(18.dp)
                .graphicsLayer {
                    this.alpha = alpha
                    val scale = 0.72f + 0.28f * alpha
                    scaleX = scale
                    scaleY = scale
                }
        )
    }
}
