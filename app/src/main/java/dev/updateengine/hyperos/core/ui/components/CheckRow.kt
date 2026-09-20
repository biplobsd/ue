package dev.updateengine.hyperos.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.updateengine.hyperos.core.model.Check
import dev.updateengine.hyperos.core.model.Outcome
import dev.updateengine.hyperos.core.model.Severity
import dev.updateengine.hyperos.core.ui.theme.SuccessGreen
import dev.updateengine.hyperos.core.ui.theme.WarningAmber
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun CheckRow(
    check: Check,
    modifier: Modifier = Modifier
) {
    val indicatorColor = when {
        check.outcome == Outcome.PASS -> SuccessGreen
        check.severity == Severity.BLOCKER -> MiuixTheme.colorScheme.error
        check.severity == Severity.WARN -> WarningAmber
        else -> MiuixTheme.colorScheme.disabledOnSurface
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(indicatorColor)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = check.title,
                    color = MiuixTheme.colorScheme.onBackground,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (check.severity == Severity.BLOCKER && check.outcome == Outcome.FAIL) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "BLOCKER",
                        color = MiuixTheme.colorScheme.error,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(
                text = check.detail,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            if (!check.remediation.isNullOrBlank() && check.outcome == Outcome.FAIL) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Fix: ${check.remediation}",
                    color = WarningAmber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
