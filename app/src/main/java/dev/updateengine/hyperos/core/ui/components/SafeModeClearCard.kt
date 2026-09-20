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
fun SafeModeClearCard(
    onClearSafeModeClick: () -> Unit,
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
                    text = "VIRTUAL A/B MERGE COMPLETE",
                    color = SuccessGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Safe Mode Active",
            color = MiuixTheme.colorScheme.onSurfaceContainer,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "The Virtual A/B snapshots are fully consolidated into the physical partitions. You can now clear the update flag and re-enable your modules one by one in KernelSU Next — they were disabled on purpose so nothing could inject during the first boot and the merge.",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        Button(
            onClick = onClearSafeModeClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColorsPrimary()
        ) {
            Text(
                text = "Remove Safe Mode",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}
