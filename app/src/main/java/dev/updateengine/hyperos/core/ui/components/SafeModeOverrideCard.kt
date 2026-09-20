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
import dev.updateengine.hyperos.core.ui.theme.WarningAmber
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Shown while the Virtual A/B snapshot merge is being monitored. Safe Mode stays armed until
 * update_engine confirms the merge finished, so the only safe action is to keep monitoring; the
 * manual override is deliberately placed behind a confirmation dialog.
 */
@Composable
fun SafeModeOverrideCard(
    title: String,
    message: String,
    showActions: Boolean,
    onResumeClick: () -> Unit,
    onForceClearSafeModeClick: () -> Unit,
    modifier: Modifier = Modifier,
    resolution: String? = null
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, WarningAmber, RoundedCornerShape(16.dp)),
        insideMargin = PaddingValues(18.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(WarningAmber.copy(alpha = 0.2f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = "VIRTUAL A/B SNAPSHOT MERGE",
                color = WarningAmber,
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
            text = message,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 12.sp,
            lineHeight = 17.sp,
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
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
        }

        if (showActions) {
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = "Resume Merge Monitoring",
                    onClick = onResumeClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors()
                )

                Spacer(modifier = Modifier.width(12.dp))

                Button(
                    onClick = onForceClearSafeModeClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.error,
                        contentColor = MiuixTheme.colorScheme.onError
                    )
                ) {
                    Text(
                        text = "Remove Safe Mode",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

/**
 * Confirmation for removing Safe Mode while update_engine never confirmed the merge finished. This
 * is the one action that can bring back the unlit-screen failure mode, so it is never automatic.
 */
@Composable
fun SafeModeOverrideDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    WindowDialog(
        show = true,
        title = "Remove Safe Mode Without Merge Confirmation?",
        summary = "update_engine never confirmed that the Virtual A/B snapshots finished merging.",
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Only continue if snapshotctl dump reports 'Update state: none' and the device has been " +
                    "idle since it rebooted. If modules are re-enabled while snapshots are still merging, " +
                    "snapuserd can crash and the screen can stay permanently unlit.",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp,
                lineHeight = 19.sp
            )

            Spacer(modifier = Modifier.height(18.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = "Cancel",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors()
                )

                Spacer(modifier = Modifier.width(12.dp))

                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1.3f),
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.error,
                        contentColor = MiuixTheme.colorScheme.onError
                    )
                ) {
                    Text(
                        text = "I Understand",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
