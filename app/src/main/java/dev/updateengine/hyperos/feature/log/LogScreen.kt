package dev.updateengine.hyperos.feature.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.updateengine.hyperos.HyperOsOtaApp
import dev.updateengine.hyperos.core.ui.glass.FloatingBottomBarClearance
import dev.updateengine.hyperos.core.ui.theme.SuccessGreen
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun LogScreen(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    val app = HyperOsOtaApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logLines = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        val logFile = java.io.File(context.filesDir, "pipeline.log")
        if (logFile.exists()) {
            try {
                val existing = logFile.readLines()
                logLines.addAll(existing.takeLast(500))
            } catch (_: Exception) {}
        }
        app.pipelineRunner.logLines.collect { line ->
            logLines.add(line)
            if (logLines.size > 1000) {
                logLines.removeAt(0)
            }
            listState.animateScrollToItem((logLines.size - 1).coerceAtLeast(0))
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Live Execution Logs",
                color = MiuixTheme.colorScheme.onBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )

            TextButton(
                text = "Clear",
                onClick = {
                    logLines.clear()
                    app.pipelineRunner.clearLogs()
                },
                colors = ButtonDefaults.textButtonColors()
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("OTA Logs", logLines.joinToString("\n"))
                    clipboard.setPrimaryClip(clip)
                    scope.launch { snackbarHostState.showSnackbar("Logs copied to clipboard") }
                },
                colors = ButtonDefaults.buttonColorsPrimary()
            ) {
                Text(
                    text = "Copy",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.padding(top = 12.dp))

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(MiuixTheme.colorScheme.surface)
                .border(1.dp, MiuixTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            if (logLines.isEmpty()) {
                Text(
                    text = "No logs yet. Output from update_engine_client and ksud will appear here in real time.",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = FloatingBottomBarClearance)
                ) {
                    items(logLines) { line ->
                        val color = when {
                            line.contains("error", ignoreCase = true) ||
                                line.contains("fail", ignoreCase = true) ->
                                MiuixTheme.colorScheme.error

                            line.contains("verified", ignoreCase = true) ||
                                line.contains("success", ignoreCase = true) ||
                                line.contains("- Done!") -> SuccessGreen

                            line.contains("onStatusUpdate") -> MiuixTheme.colorScheme.primary

                            else -> MiuixTheme.colorScheme.onSurface
                        }
                        Text(
                            text = line,
                            color = color,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }
    }
}
