package dev.gyro.feature.player.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import dev.gyro.core.designsystem.component.GyroCapsuleContainer
import dev.gyro.core.designsystem.component.GyroCircleActionButton
import dev.gyro.core.designsystem.theme.GyroColors
import dev.gyro.feature.player.component.sheet.PlayerSheetTab

/**
 * 顶部导航与快捷入口栏 (PlayerTopBar)。
 *
 * - 全悬浮胶囊设计：底衬为独立悬浮胶囊（伪磨砂，见 [GyroCapsuleContainer]），不再使用通栏渐变；
 * - 竖屏：返回 + 标题（weight 撑满、中间省略）+ 画中画 + ⋮ 溢出（字幕 / 设置），
 *   为标题腾出最大宽度（约 16 汉字 @15sp）；
 * - 横屏：返回 + 标题 + 字幕 + 画中画 + 设置（宽度充裕，保持直达按键）。
 */
@Composable
fun PlayerTopBar(
    title: String,
    isLandscape: Boolean,
    onBack: () -> Unit,
    onEnterPiP: () -> Unit,
    onOpenSheetTab: (PlayerSheetTab) -> Unit,
    onScreenshot: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .displayCutoutPadding()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        var menuExpanded by remember { mutableStateOf(false) }

        GyroCapsuleContainer {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 返回键：40dp 悬浮圆钮
                GyroCircleActionButton(
                    onClick = onBack,
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    size = 35.dp,
                    iconSize = 19.dp,
                    containerColor = Color.Transparent,
                    border = null
                )

                Spacer(modifier = Modifier.width(10.dp))

                // 视频标题（垂直居中，带文字阴影保证背景可读性；中间省略保留剧集尾部信息）
                Text(
                    text = title.ifBlank { stringResource(R.string.player_default_title) },
                    style = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.85f),
                            blurRadius = 8f
                        )
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                if (isLandscape) {
                    // 横屏：宽度充裕，功能键全部直达
                    GyroCircleActionButton(
                        onClick = { onOpenSheetTab(PlayerSheetTab.SUBTITLES) },
                        icon = Icons.Default.ClosedCaption,
                        contentDescription = stringResource(R.string.sheet_tab_subtitles),
                        size = 35.dp,
                        iconSize = 19.dp,
                        containerColor = Color.Transparent,
                        border = null
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    GyroCircleActionButton(
                        onClick = onEnterPiP,
                        icon = Icons.Default.PictureInPictureAlt,
                        contentDescription = stringResource(R.string.cd_pip),
                        size = 35.dp,
                        iconSize = 19.dp,
                        containerColor = Color.Transparent,
                        border = null
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    GyroCircleActionButton(
                        onClick = onScreenshot,
                        icon = Icons.Default.PhotoCamera,
                        contentDescription = stringResource(R.string.cd_screenshot),
                        size = 35.dp,
                        iconSize = 19.dp,
                        containerColor = Color.Transparent,
                        border = null
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    GyroCircleActionButton(
                        onClick = { onOpenSheetTab(PlayerSheetTab.SETTINGS_HUB) },
                        icon = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.cd_settings),
                        size = 35.dp,
                        iconSize = 19.dp,
                        containerColor = Color.Transparent,
                        border = null
                    )
                } else {
                    // 竖屏：仅保留 画中画 + ⋮ 溢出（字幕 / 设置收纳于此），为标题让出宽度
                    GyroCircleActionButton(
                        onClick = onEnterPiP,
                        icon = Icons.Default.PictureInPictureAlt,
                        contentDescription = stringResource(R.string.cd_pip),
                        size = 35.dp,
                        iconSize = 19.dp,
                        containerColor = Color.Transparent,
                        border = null
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Box {
                        GyroCircleActionButton(
                            onClick = { menuExpanded = true },
                            icon = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.cd_settings),
                            size = 35.dp,
                            iconSize = 19.dp,
                            containerColor = Color.Transparent,
                            border = null
                        )
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.cd_screenshot)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.PhotoCamera,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onScreenshot()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sheet_tab_subtitles)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.ClosedCaption,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onOpenSheetTab(PlayerSheetTab.SUBTITLES)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.cd_settings)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Settings,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onOpenSheetTab(PlayerSheetTab.SETTINGS_HUB)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
