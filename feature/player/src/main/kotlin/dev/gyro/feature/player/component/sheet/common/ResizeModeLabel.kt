package dev.gyro.feature.player.component.sheet.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.gyro.core.designsystem.R
import dev.gyro.engine.model.ResizeMode

/**
 * 画面比例的展示名。
 *
 * 放在 UI 层而不是枚举上：`engine-api` 不依赖 design system 的资源，枚举只表达引擎语义。
 */
@Composable
fun ResizeMode.displayLabel(): String = when (this) {
    ResizeMode.FIT -> stringResource(R.string.aspect_mode_fit_full)
    ResizeMode.CROP -> stringResource(R.string.aspect_mode_crop_full)
    ResizeMode.STRETCH -> stringResource(R.string.aspect_mode_stretch_full)
    ResizeMode.ASPECT_16_9 -> stringResource(R.string.aspect_mode_16_9_full)
    ResizeMode.ASPECT_21_9 -> stringResource(R.string.aspect_mode_21_9_full)
}
