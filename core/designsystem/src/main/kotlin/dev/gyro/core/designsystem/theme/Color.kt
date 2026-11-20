package dev.gyro.core.designsystem.theme

import androidx.compose.ui.graphics.Color

object GyroColors {
    val Background = Color(0xFF0F1117)
    val Surface = Color(0xFF171B24)
    val SurfaceVariant = Color(0xFF1E2330)
    val TopBarBackground = Color(0xFF141822)
    val Primary = Color(0xFF2196F3)
    val PrimaryLight = Color(0xFF64B5F6)
    val AccentGreen = Color(0xFF81C784)
    val AccentOrange = Color(0xFFFFB74D)
    val Error = Color(0xFFFF5252)
    val TextPrimary = Color.White
    val TextSecondary = Color.White.copy(alpha = 0.6f)
    val TextMuted = Color.White.copy(alpha = 0.4f)
    val SheetBackground = Color(0xFF1E1E1E)

    // —— 播放抽屉与全局设置页统一面板 Token ——
    val PanelBackground = Color(0xFF14161D)
    val PanelCard = Color(0xFF1C202B)
    val PanelCardElevated = Color(0xFF242938)
    val PanelTrack = Color(0xFF333A4D)
    val PanelDivider = Color.White.copy(alpha = 0.07f)
    val PanelBorder = Color.White.copy(alpha = 0.08f)
    val AccentGreenStrong = Color(0xFF4CAF50)

    // —— 播放器悬浮控件层（伪磨砂配方：分层半透明 + 细描边 + 柔投影，零 blur 成本） ——
    val OverlayFill = Color.Black.copy(alpha = 0.55f)
    val OverlayFillStrong = Color.Black.copy(alpha = 0.75f)
    val OverlayStroke = Color.White.copy(alpha = 0.10f)
}
