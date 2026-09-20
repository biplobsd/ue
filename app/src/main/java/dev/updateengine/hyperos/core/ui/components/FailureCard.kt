package dev.updateengine.hyperos.core.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.updateengine.hyperos.core.model.Phase
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Generic failure card.
 *
 * Every failure has to show its cause and the suggested next step on the Home screen: a pipeline that
 * stops with only "Verification failed" in the status ribbon forces the user into the log tab to find
 * out what actually went wrong.
 */
@Composable
fun FailureCard(
    title: String,
    message: String,
    resolution: String?,
    phase: Phase?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MiuixTheme.colorScheme.error, RoundedCornerShape(16.dp)),
        insideMargin = PaddingValues(18.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = 8.dp)
        ) {
            Text(
                text = (phase?.name?.replace('_', ' ') ?: "FAILED") + " — STOPPED",
                color = MiuixTheme.colorScheme.error,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        Text(
            text = title,
            color = MiuixTheme.colorScheme.onSurfaceContainer,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = message,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        resolution?.takeIf { it.isNotBlank() }?.let { text ->
            Spacer(modifier = Modifier.height(2.dp))
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
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
