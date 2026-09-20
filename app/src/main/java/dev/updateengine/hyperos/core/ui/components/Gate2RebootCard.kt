package dev.updateengine.hyperos.core.ui.components

import androidx.compose.foundation.background
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
import dev.updateengine.hyperos.core.ui.theme.SuccessGreen
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun Gate2RebootCard(
    onRebootClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(18.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(SuccessGreen.copy(alpha = 0.2f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "GATE 2: READY TO REBOOT",
                    color = SuccessGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Flashing & Root Patch Verified",
            color = MiuixTheme.colorScheme.onSurfaceContainer,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "• Inactive slot written successfully\n• KernelSU Next root patch cryptographically verified\n• All KernelSU modules disabled for the first boot (verified through ksud)\n• Emergency recovery file written to /sdcard/Download/ota_emergency_recovery.txt",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        Button(
            onClick = onRebootClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(color = SuccessGreen, contentColor = MiuixTheme.colorScheme.onPrimary)
        ) {
            Text(
                text = "Reboot Now",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }
    }
}
