package dev.updateengine.hyperos.core.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.updateengine.hyperos.HyperOsOtaApp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private val ZIP_MIME_TYPES = arrayOf(
    "application/zip",
    "application/x-zip-compressed",
    "application/octet-stream"
)

/**
 * The single place a target ROM is chosen from: either a recovery package already on the device or
 * the official builds listed in the Updates tab.
 */
@Composable
fun TargetPickerDialog(
    show: Boolean,
    deviceCodename: String,
    onDismiss: () -> Unit,
    onBrowseUpdates: () -> Unit,
    onTargetReady: () -> Unit
) {
    val app = HyperOsOtaApp.instance
    val scope = rememberCoroutineScope()

    var isImporting by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(show) {
        if (show) {
            isImporting = false
            importError = null
        }
    }

    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            isImporting = true
            importError = null
            scope.launch {
                app.localPackageImporter.import(uri)
                    .onSuccess { target ->
                        app.pipelineRunner.setTarget(target)
                        isImporting = false
                        onDismiss()
                        onTargetReady()
                    }
                    .onFailure { error ->
                        isImporting = false
                        importError = error.message ?: "Could not read the selected package."
                    }
            }
        }
    }

    if (!show) return

    WindowDialog(
        show = true,
        title = "Select Target ROM",
        summary = "Pick a recovery package already on this device, or choose from the official builds available for $deviceCodename.",
        onDismissRequest = { if (!isImporting) onDismiss() }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (isImporting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Importing package...",
                            color = MiuixTheme.colorScheme.onSurfaceContainer,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Reading the package metadata. A zip on shared storage is used where " +
                                "it already is; only a file the engine cannot address directly is copied " +
                                "into staging first. Large packages take a while.",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                SourceOption(
                    title = "Pick .zip from storage",
                    description = "Choose a Full Recovery OTA .zip already on this device. It is read where it " +
                        "already lives, so no second 7-8 GB copy is made next to it.",
                    onClick = { pickZip.launch(ZIP_MIME_TYPES) }
                )

                SourceOption(
                    title = "Available updates",
                    description = "Browse official builds for $deviceCodename that match your regional resign and are newer than the installed build.",
                    onClick = {
                        onDismiss()
                        onBrowseUpdates()
                    }
                )

                importError?.let { message ->
                    Text(
                        text = message,
                        color = MiuixTheme.colorScheme.error,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceOption(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, MiuixTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        cornerRadius = 10.dp,
        insideMargin = PaddingValues(12.dp)
    ) {
        Text(
            text = title,
            color = MiuixTheme.colorScheme.primary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = description,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
    }
}
