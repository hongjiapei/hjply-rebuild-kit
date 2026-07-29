package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.SeedState
import io.nekohasekai.sfa.compose.ui.theme.LocalHjplyPalette
import io.nekohasekai.sfa.constant.Status
import java.util.Date
import java.util.concurrent.TimeUnit

@Composable
fun DashboardScreen(
    serviceStatus: Status = Status.Stopped,
    viewModel: DashboardViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val palette = LocalHjplyPalette.current
    LaunchedEffect(serviceStatus) { viewModel.updateServiceStatus(serviceStatus) }
    val connected = serviceStatus == Status.Started || serviceStatus == Status.Starting
    // Disable the connect button while the default profile is still being
    // seeded (first launch, network download in flight). Otherwise tapping
    // the button races against DefaultProfileSeeder and triggers the
    // "Empty configuration" alert from BoxService.
    val seedLoading = state.seedState is SeedState.Loading
    val profileReady = state.profiles.isNotEmpty() && state.selectedProfileId != -1L && !seedLoading
    val seedFailed = state.seedState is SeedState.Failed
    val connectionLabel = if (connected) "已连接" else "未连接"
    val mainLabel = if (connected) "连接正常" else "准备连接"
    val actionLabel = when {
        connected -> "断开连接"
        seedLoading -> "加载配置中..."
        seedFailed -> "重试"
        else -> "立即连接"
    }
    val secondaryLabel = if (connected) "大陆直连 · 其他代理" else "准备就绪 · 等待连接"
    val elapsed = state.serviceStartTime?.let { formatElapsed(System.currentTimeMillis() - it) } ?: "00:00"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp),
    ) {
        // Title block: mini app icon (white squircle + blue crescent+dot)
        // + selected profile name. We draw the squircle ourselves and place
        // the vector foreground inside, instead of using R.mipmap.ic_launcher
        // directly — adaptive-icon XMLs do not render reliably through
        // Compose's Image in all API 26+ configurations.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.iconPlate)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (state.isDarkMode) "切换到浅色模式" else "切换到深色模式",
                    ) { viewModel.toggleTheme() },
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = if (state.isDarkMode) "切换到浅色模式" else "切换到深色模式",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    state.selectedProfileName ?: "hjply",
                    color = palette.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                state.selectedProfileLastUpdated?.takeIf { it.time > 0 }?.let { ts ->
                    Text(
                        "更新于 ${formatRelativeTime(ts)}",
                        color = palette.textMuted,
                        fontSize = 11.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(26.dp))

        // Main connection card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = palette.card),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 30.dp, bottom = 38.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Top center status pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(palette.pillGreenSoft)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(palette.green))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            connectionLabel,
                            color = palette.green,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                Spacer(Modifier.height(26.dp))

                // Big circle — state-aware visual.
                // Disconnected: soft gray fill + dashed border + gray power icon (待启动).
                // Connected:    solid blue + soft halo + white shield (已保护).
                Box(
                    modifier = Modifier.size(168.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (connected) {
                        // Soft halo glow
                        Box(
                            modifier = Modifier
                                .size(168.dp)
                                .clip(CircleShape)
                                .background(palette.blue.copy(alpha = 0.18f)),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(148.dp)
                            .clip(CircleShape)
                            .background(if (connected) palette.blue else palette.offBg)
                            .let { mod ->
                                if (connected) {
                                    mod
                                } else {
                                    mod.border(width = 2.dp, color = palette.offBorder, shape = CircleShape)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(if (connected) R.drawable.ic_shield else R.drawable.ic_power),
                            contentDescription = actionLabel,
                            tint = if (connected) Color.White else palette.offIcon,
                            modifier = Modifier.size(76.dp),
                        )
                    }
                }

                Spacer(Modifier.height(30.dp))
                Text(
                    mainLabel,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    secondaryLabel,
                    fontSize = 13.sp,
                    color = palette.textMuted,
                )
            }
        }

        Spacer(Modifier.height(22.dp))

        // Stats label
        Text(
            "本次连接",
            fontSize = 13.sp,
            color = palette.textMuted,
            modifier = Modifier.padding(start = 4.dp),
        )
        Spacer(Modifier.height(10.dp))

        // Stats card — 4 columns (时长 / 上行 / 下行 / 网络)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = palette.card),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(86.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Metric(elapsed, "时长")
                Metric(state.uplinkTotal, "上行")
                Metric(state.downlinkTotal, "下行")
                Metric("Wi-Fi", "网络")
            }
        }

        Spacer(Modifier.height(22.dp))

        // Seed error banner — only shown when the seeder has failed and we
        // have nothing to connect to. Helps the user understand why the
        // connect button is disabled and gives a clear next step.
        if (seedFailed) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = palette.errorBannerBg),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "配置加载失败",
                        color = palette.errorBannerTitle,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        (state.seedState as? SeedState.Failed)?.reason
                            ?: "请检查网络后重试",
                        color = palette.errorBannerBody,
                        fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // Primary action button — replaces the old text-button inside the card.
        // Disabled while the default profile is still being seeded.
        Button(
            onClick = { if (seedFailed) viewModel.retrySeed() else viewModel.toggleService() },
            enabled = connected || profileReady || seedFailed,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.blue,
                contentColor = Color.White,
            ),
            contentPadding = PaddingValues(vertical = 14.dp),
        ) {
            Text(
                actionLabel,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(28.dp))
    }
}

@Composable private fun Metric(value: String, label: String) {
    val palette = LocalHjplyPalette.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxHeight()
            .padding(horizontal = 4.dp),
    ) {
        Text(
            value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = palette.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            fontSize = 11.sp,
            color = palette.textMuted,
        )
    }
}

private fun formatElapsed(milliseconds: Long): String {
    val seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds).coerceAtLeast(0)
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}

private fun formatRelativeTime(date: Date): String {
    val diffMs = (System.currentTimeMillis() - date.time).coerceAtLeast(0)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(diffMs)
    return when {
        seconds < 60 -> "刚刚"
        seconds < TimeUnit.HOURS.toSeconds(1) -> "${seconds / 60} 分钟前"
        seconds < TimeUnit.DAYS.toSeconds(1) -> "${seconds / 3600} 小时前"
        seconds < TimeUnit.DAYS.toSeconds(30) -> "${seconds / 86400} 天前"
        else -> "${seconds / TimeUnit.DAYS.toSeconds(30)} 个月前"
    }
}
