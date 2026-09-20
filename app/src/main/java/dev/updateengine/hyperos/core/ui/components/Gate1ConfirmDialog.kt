package dev.updateengine.hyperos.core.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.updateengine.hyperos.core.model.PreFlashChecklist
import dev.updateengine.hyperos.core.ui.theme.WarningAmber
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun Gate1ConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: (PreFlashChecklist) -> Unit
) {
    var checklist by remember { mutableStateOf(PreFlashChecklist()) }

    WindowDialog(
        show = true,
        title = "Confirm System Safety Checklist",
        summary = "All 5 safety items must be reviewed and confirmed before update_engine is permitted to flash the inactive slot.",
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "GATE 1: PRE-FLASH SAFETY GATES",
                color = WarningAmber,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Checkbox 1: Backup
            ChecklistItem(
                checked = checklist.backupConfirmed,
                onCheckedChange = { checklist = checklist.copy(backupConfirmed = it) },
                title = "1. External Dual Backup Off-Phone",
                description = "I have backed up all critical data to a PC or external storage. If an unexpected File-Based Encryption decryption error occurs, a wipe may be required."
            )

            // Checkbox 2: Battery & Power
            ChecklistItem(
                checked = checklist.batteryConfirmed,
                onCheckedChange = { checklist = checklist.copy(batteryConfirmed = it) },
                title = "2. Battery & Stable Power",
                description = "Battery is >= 60% OR device is connected to a reliable charger. A sudden power loss during partition writes can corrupt the inactive slot."
            )

            // Checkbox 3: Module Safe Mode
            ChecklistItem(
                checked = checklist.modulesSafeModeConfirmed,
                onCheckedChange = { checklist = checklist.copy(modulesSafeModeConfirmed = it) },
                title = "3. Root Modules Disabled For The First Boot",
                description = "Every KernelSU module will be disabled (verified through ksud) so nothing can inject while snapuserd and the display compositor come up. I will re-enable them one by one in KernelSU Next after the snapshot merge finishes."
            )

            // Checkbox 4: Storage Margin
            ChecklistItem(
                checked = checklist.storageConfirmed,
                onCheckedChange = { checklist = checklist.copy(storageConfirmed = it) },
                title = "4. Storage COW Space Margin",
                description = "I have enough free space on /data for the extracted payload plus the Virtual A/B snapshot allocation (16–20 GB is the safe margin). The engine's downloaded copy is purged after verification to make room."
            )

            // Checkbox 5: Bootloader & Fastboot Emergency
            ChecklistItem(
                checked = checklist.fastbootRescueConfirmed,
                onCheckedChange = { checklist = checklist.copy(fastbootRescueConfirmed = it) },
                title = "5. Bootloader Unlocked & Fastboot Rescue",
                description = "My bootloader is unlocked. I understand I can switch back to my working slot anytime via 'fastboot --set-active' if boot ever fails."
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(
                    text = "Cancel",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors()
                )

                Spacer(modifier = Modifier.width(12.dp))

                Button(
                    onClick = { onConfirm(checklist) },
                    enabled = checklist.isAllChecked,
                    modifier = Modifier.weight(1.5f),
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Text(
                        text = if (checklist.isAllChecked) "Start Flashing" else "Confirm (5)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun ChecklistItem(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    description: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(
                1.dp,
                if (checked) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline,
                RoundedCornerShape(10.dp)
            )
            .clickable { onCheckedChange(!checked) },
        cornerRadius = 10.dp,
        insideMargin = PaddingValues(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Top
        ) {
            Checkbox(
                state = ToggleableState(checked),
                onClick = { onCheckedChange(!checked) }
            )

            Spacer(modifier = Modifier.width(6.dp))

            Column {
                Text(
                    text = title,
                    color = if (checked) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceContainer
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
