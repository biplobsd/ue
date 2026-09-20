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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun RootPatchFailedCard(
    errorMessage: String,
    onRetryClick: () -> Unit,
    onRevertSlotClick: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Root Patch Verification Failed",
    showRetryPatch: Boolean = true,
    resolution: String? = null
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(2.dp, MiuixTheme.colorScheme.error, RoundedCornerShape(16.dp)),
        insideMargin = PaddingValues(18.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MiuixTheme.colorScheme.error.copy(alpha = 0.2f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = "CRITICAL SAFETY LOCK: DO NOT REBOOT",
                color = MiuixTheme.colorScheme.error,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = title,
            color = MiuixTheme.colorScheme.onSurfaceContainer,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = errorMessage.ifBlank {
                "The inactive slot partition was not cryptographically modified with root. Rebooting now would boot into unrooted stock system or fail."
            },
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        resolution?.takeIf { it.isNotBlank() }?.let { text ->
            Text(
                text = "WHAT TO DO",
                color = MiuixTheme.colorScheme.primary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                text = text,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            if (showRetryPatch) {
                TextButton(
                    text = "Retry Patch",
                    onClick = onRetryClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors()
                )

                Spacer(modifier = Modifier.width(12.dp))
            }

            Button(
                onClick = onRevertSlotClick,
                modifier = Modifier.weight(if (showRetryPatch) 1.4f else 1f),
                colors = ButtonDefaults.buttonColors(
                    color = MiuixTheme.colorScheme.error,
                    contentColor = MiuixTheme.colorScheme.onError
                )
            ) {
                Text(
                    text = "Revert Slot Switch",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
