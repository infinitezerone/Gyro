package dev.gyro.feature.player.component.sheet.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gyro.core.designsystem.R
import dev.gyro.core.model.GlslShader
import dev.gyro.feature.player.component.sheet.PlayerSheetCallbacks
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import dev.gyro.feature.player.component.sheet.common.SheetAccentBlue
import dev.gyro.feature.player.component.sheet.common.SheetSectionHeader
import dev.gyro.feature.player.component.sheet.common.SheetCardColor
import dev.gyro.feature.player.component.sheet.common.SheetTextSecondaryColor
import dev.gyro.feature.player.component.sheet.common.TrackItem

/**
 * GLSL 着色器与 Anime4K (ShadersTab)
 */
@Composable
internal fun ShadersTabContent(
    state: PlayerSheetUiState,
    callbacks: PlayerSheetCallbacks
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. 预设 GLSL 画质滤镜
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SheetSectionHeader("GLSL 画质渲染滤镜")

            TrackItem(
                title = "关闭滤镜",
                subtitle = "",
                isSelected = state.shaderState.activeShader == GlslShader.OFF,
                accentColor = SheetAccentBlue,
                icon = Icons.Default.AutoAwesome,
                onClick = callbacks.onClearShader
            )

            TrackItem(
                title = stringResource(R.string.shader_anime4k),
                subtitle = "画质超分与线条锐化",
                isSelected = state.shaderState.activeShader == GlslShader.ANIME4K,
                accentColor = SheetAccentBlue,
                icon = Icons.Default.AutoAwesome,
                onClick = { callbacks.onSelectShader(GlslShader.ANIME4K) }
            )
        }

        // 2. 自定义着色器
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SheetSectionHeader(stringResource(R.string.shader_custom))

            Surface(
                onClick = { callbacks.onImportCustomShader() },
                shape = RoundedCornerShape(14.dp),
                color = SheetCardColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(SheetAccentBlue.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = SheetAccentBlue,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "导入本地 .glsl 滤镜",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = SheetAccentBlue
                        )
                        Text(
                            text = stringResource(R.string.shader_desc_custom),
                            style = MaterialTheme.typography.bodySmall,
                            color = SheetTextSecondaryColor
                        )
                    }
                }
            }

            state.customShaders.forEach { file ->
                val isSelected = state.shaderState.activeShader == GlslShader.CUSTOM &&
                        state.shaderState.currentPath == file.absolutePath
                TrackItem(
                    title = file.nameWithoutExtension,
                    subtitle = file.name,
                    isSelected = isSelected,
                    accentColor = SheetAccentBlue,
                    icon = Icons.Default.AutoAwesome,
                    onClick = { callbacks.onSelectCustomShaderFile(file) }
                )
            }
        }
    }
}
