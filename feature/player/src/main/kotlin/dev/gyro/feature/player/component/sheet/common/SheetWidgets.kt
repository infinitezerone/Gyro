package dev.gyro.feature.player.component.sheet.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import dev.gyro.core.designsystem.component.GyroChip
import dev.gyro.core.designsystem.component.GyroFineTuneSliderRow
import dev.gyro.core.designsystem.component.GyroSectionHeader
import dev.gyro.core.designsystem.theme.GyroColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

// —— 统一映射自 GyroColors 的面板色板别名，保持抽屉与全局设置完全同源 ——
internal val SheetBgColor = GyroColors.PanelBackground
internal val SheetCardColor = GyroColors.PanelCard
internal val SheetCardAltColor = GyroColors.PanelCardElevated
internal val SheetTrackColor = GyroColors.PanelTrack
internal val SheetTextColor = GyroColors.TextPrimary
internal val SheetTextSecondaryColor = GyroColors.TextSecondary

internal val SheetAccentBlue = GyroColors.Primary
internal val SheetGreen = GyroColors.AccentGreenStrong
internal val SheetAmber = GyroColors.AccentOrange

private const val DELAY_MIN_SEC = -10.0
private const val DELAY_MAX_SEC = 10.0
private const val DELAY_SLIDER_LIMIT = 5f

/**
 * 音轨/字幕延迟校准复用块（音频延迟与字幕延迟共用）。
 */
@Composable
internal fun DelayCalibrationBlock(
    label: String,
    currentSec: Double,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isModified = abs(currentSec) >= 0.05
    Column(modifier = modifier.fillMaxWidth()) {
        GyroSectionHeader(
            title = label,
            actionLabel = stringResource(R.string.action_reset),
            onActionClick = { onValueChange(0.0) },
            actionEnabled = isModified,
            horizontalPadding = 16.dp,
            modifier = Modifier.padding(top = 8.dp),
        )

        GyroFineTuneSliderRow(
            label = stringResource(R.string.sync_status_synced).takeIf { !isModified }
                ?: formatDelayValue(currentSec),
            valueText = formatDelayValue(currentSec),
            value = currentSec.toFloat(),
            valueRange = -DELAY_SLIDER_LIMIT..DELAY_SLIDER_LIMIT,
            onValueChange = { raw ->
                val stepped = round(raw * 10.0) / 10.0
                onValueChange(stepped.coerceIn(DELAY_MIN_SEC, DELAY_MAX_SEC))
            },
            step = 0.1f,
            snapValue = 0f,
            snapThreshold = 0.08f,
            isModified = isModified,
            onStepDown = {
                onValueChange((round((currentSec - 0.1) * 10.0) / 10.0).coerceIn(DELAY_MIN_SEC, DELAY_MAX_SEC))
            },
            onStepUp = {
                onValueChange((round((currentSec + 0.1) * 10.0) / 10.0).coerceIn(DELAY_MIN_SEC, DELAY_MAX_SEC))
            },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                -0.5 to "-0.5s",
                -0.1 to "-0.1s",
                0.1 to "+0.1s",
                0.5 to "+0.5s",
            ).forEach { (delta, chipLabel) ->
                GyroChip(
                    label = chipLabel,
                    selected = false,
                    onClick = {
                        val next = (round((currentSec + delta) * 10.0) / 10.0)
                            .coerceIn(DELAY_MIN_SEC, DELAY_MAX_SEC)
                        onValueChange(next)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

internal fun formatDelayValue(seconds: Double): String =
    if (abs(seconds) < 0.05) {
        "0.0s"
    } else {
        String.format(Locale.US, "%+.1fs", seconds)
    }

@Composable
internal fun EmptyTrackNotice(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = message, fontSize = 13.sp, color = SheetTextSecondaryColor)
    }
}

/**
 * ffmpeg reports codecs as "H.265 / HEVC (High Efficiency Video Coding)"; a row needs "HEVC".
 * Shared by the settings row and the media-info page so they never disagree on the same file.
 */
internal fun shortCodecName(raw: String): String {
    val text = raw.trim()
    return when {
        text.contains("HEVC", true) || text.contains("H.265", true) -> "HEVC"
        text.contains("AV1", true) -> "AV1"
        text.contains("H.264", true) || text.contains("AVC", true) -> "H.264"
        text.contains("VP9", true) -> "VP9"
        text.contains("VP8", true) -> "VP8"
        else -> text.substringBefore('/').trim().uppercase()
    }
}

@Composable
internal fun MediaInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = SheetTextSecondaryColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = SheetTextColor,
                fontFeatureSettings = "tnum",
            ),
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 210.dp),
        )
    }
}

/**
 * 格式化码率字符串 (bps -> Mbps / kbps)
 */
internal fun formatBitrate(bps: Long): String {
    if (bps <= 0) return "--"
    val mbps = bps / 1_000_000.0
    val kbps = bps / 1_000.0
    return if (mbps >= 1.0) {
        String.format(Locale.US, "%.2f Mbps", mbps)
    } else {
        String.format(Locale.US, "%.0f kbps", kbps)
    }
}
