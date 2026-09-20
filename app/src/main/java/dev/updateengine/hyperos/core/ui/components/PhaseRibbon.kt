package dev.updateengine.hyperos.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.updateengine.hyperos.core.common.Formatters
import dev.updateengine.hyperos.core.model.OtaProgress
import dev.updateengine.hyperos.core.model.Phase
import dev.updateengine.hyperos.core.model.ProgressDetail
import dev.updateengine.hyperos.core.ui.theme.SuccessGreen
import dev.updateengine.hyperos.core.ui.theme.WarningAmber
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PhaseRibbon(
    progress: OtaProgress,
    modifier: Modifier = Modifier
) {
    val phaseColor = when (progress.phase) {
        Phase.IDLE -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        Phase.DONE, Phase.ARMED, Phase.READY_TO_REBOOT, Phase.SAFE_MODE_CLEAR -> SuccessGreen
        Phase.FAILED, Phase.ROOT_PATCH_FAILED -> MiuixTheme.colorScheme.error
        Phase.FLASHING, Phase.ROOT_PATCHING, Phase.MERGING -> MiuixTheme.colorScheme.primary
        else -> WarningAmber
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(phaseColor.copy(alpha = 0.2f))
                    .border(1.dp, phaseColor, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = progress.phase.name.replace('_', ' '),
                    color = phaseColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = progress.statusText.ifBlank { "Ready" },
            color = MiuixTheme.colorScheme.onSurfaceContainer,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )

        // Progress Bar
        when (val detail = progress.detail) {
            is ProgressDetail.Bytes -> {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = detail.fraction,
                    modifier = Modifier.fillMaxWidth(),
                    height = 6.dp
                )
                Spacer(modifier = Modifier.height(6.dp))
                val rate = Formatters.formatTransferRate(detail.rateBps)
                val eta = Formatters.formatEta(detail.etaSecs)
                Text(
                    text = "${Formatters.formatBytes(detail.done)} / ${Formatters.formatBytes(detail.total)} ($rate • ETA $eta)",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp
                )
            }
            is ProgressDetail.Percent -> {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = detail.value.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth(),
                    height = 6.dp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = detail.caption ?: "${(detail.value * 100).toInt()}%",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp
                )
            }
            is ProgressDetail.Indeterminate -> {
                if (progress.isFlashingOrPost) {
                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = null,
                        modifier = Modifier.fillMaxWidth(),
                        height = 6.dp
                    )
                }
            }
        }
    }
}
