package io.github.tanzir71.harkara.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.tanzir71.harkara.R
import io.github.tanzir71.harkara.ui.components.HButton
import io.github.tanzir71.harkara.ui.components.HButtonVariant
import io.github.tanzir71.harkara.ui.components.HIcon
import io.github.tanzir71.harkara.ui.components.HTextField
import io.github.tanzir71.harkara.ui.components.QrCode
import io.github.tanzir71.harkara.ui.theme.HarkaraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shortest backup password the sheet accepts. */
const val MIN_BACKUP_PASSWORD = 8

/** Where the backup sheet is. */
sealed interface BackupStep {
    data object Password : BackupStep
    data object Working : BackupStep
    data class Qr(val ncryptsec: String) : BackupStep
}

/** Backup sheet content: password + confirm, then save as a file or show as a QR code. */
@Composable
fun BackupSheetContent(
    step: BackupStep,
    password: String,
    confirm: String,
    onPassword: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onSaveFile: () -> Unit,
    onShowQr: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HarkaraTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (step is BackupStep.Qr) {
            SheetTitle(stringResource(R.string.backup_qr_title))
            Text(stringResource(R.string.backup_qr_body), style = HarkaraTheme.type.body, color = c.textSecondary)
            QrCode(
                step.ncryptsec,
                stringResource(R.string.backup_qr_description),
                Modifier.align(Alignment.CenterHorizontally).widthIn(max = 240.dp).fillMaxWidth(),
            )
            Text(step.ncryptsec, style = HarkaraTheme.type.mono.copy(fontSize = HarkaraTheme.type.micro.fontSize), color = c.textTertiary)
            HButton(stringResource(R.string.done), onDone, Modifier.fillMaxWidth(), variant = HButtonVariant.Primary, height = 52.dp)
            return@Column
        }
        SheetTitle(stringResource(R.string.backup_title))
        Text(stringResource(R.string.backup_body), style = HarkaraTheme.type.body, color = c.textSecondary)
        val tooShort = password.isNotEmpty() && password.length < MIN_BACKUP_PASSWORD
        val mismatch = confirm.isNotEmpty() && confirm != password
        HTextField(
            password,
            onPassword,
            stringResource(R.string.backup_password),
            password = true,
            error = if (tooShort) stringResource(R.string.backup_password_short) else null,
        )
        HTextField(
            confirm,
            onConfirm,
            stringResource(R.string.backup_confirm),
            password = true,
            error = if (mismatch) stringResource(R.string.backup_password_mismatch) else null,
            imeAction = ImeAction.Done,
        )
        val ready = step == BackupStep.Password && password.length >= MIN_BACKUP_PASSWORD && password == confirm
        if (step == BackupStep.Working) {
            Text(stringResource(R.string.backup_working), style = HarkaraTheme.type.caption, color = c.textSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HButton(stringResource(R.string.backup_show_qr), onShowQr, Modifier.weight(1f), variant = HButtonVariant.Secondary, icon = R.drawable.ic_qr, enabled = ready, height = 52.dp)
            HButton(stringResource(R.string.backup_save_file), onSaveFile, Modifier.weight(1f), variant = HButtonVariant.Primary, enabled = ready, height = 52.dp)
        }
    }
}

/** Restore sheet content: paste or open an `ncryptsec`, type its password, restore. */
@Composable
fun RestoreSheetContent(
    backup: String,
    password: String,
    working: Boolean,
    error: String?,
    onBackup: (String) -> Unit,
    onPassword: (String) -> Unit,
    onOpenFile: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HarkaraTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SheetTitle(stringResource(R.string.restore_title))
        Text(stringResource(R.string.restore_body), style = HarkaraTheme.type.body, color = c.textSecondary)
        HTextField(backup, onBackup, stringResource(R.string.restore_backup), mono = true)
        HButton(stringResource(R.string.restore_open_file), onOpenFile, variant = HButtonVariant.Ghost, icon = R.drawable.ic_handbook, height = 40.dp)
        HTextField(password, onPassword, stringResource(R.string.backup_password), password = true, error = error, imeAction = ImeAction.Done)
        if (working) Text(stringResource(R.string.restore_working), style = HarkaraTheme.type.caption, color = c.textSecondary)
        HButton(
            stringResource(R.string.restore_action),
            onRestore,
            Modifier.fillMaxWidth(),
            variant = HButtonVariant.Primary,
            enabled = !working && backup.isNotBlank() && password.isNotEmpty(),
            height = 52.dp,
        )
    }
}

@Composable
private fun SheetTitle(text: String) {
    val c = HarkaraTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        HIcon(R.drawable.ic_lock, tint = c.textPrimary)
        Spacer(Modifier.width(10.dp))
        Text(text, style = HarkaraTheme.type.title, color = c.textPrimary, modifier = Modifier.semantics { heading() })
    }
}

/** The backup sheet with its state: scrypt runs off the main thread, then the file is saved or the QR shown. */
@Composable
fun BackupSheet(
    export: (password: String) -> String,
    saveFile: (name: String, text: String, onResult: (Boolean) -> Unit) -> Unit,
    onFinished: (saved: Boolean?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by remember { mutableStateOf<BackupStep>(BackupStep.Password) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val fileName = stringResource(R.string.backup_file_name)
    fun run(then: (String) -> Unit) {
        step = BackupStep.Working
        scope.launch {
            val text = runCatching { withContext(Dispatchers.Default) { export(password) } }.getOrNull()
            if (text == null) {
                step = BackupStep.Password
                onFinished(false)
            } else {
                then(text)
            }
        }
    }
    BackupSheetContent(
        step = step,
        password = password,
        confirm = confirm,
        onPassword = { password = it },
        onConfirm = { confirm = it },
        onSaveFile = {
            run { text ->
                saveFile(fileName, text) { saved ->
                    step = BackupStep.Password
                    onFinished(saved)
                }
            }
        },
        onShowQr = { run { text -> step = BackupStep.Qr(text) } },
        onDone = { onFinished(null) },
        modifier = modifier,
    )
}

/** The restore sheet with its state. [restore] returns false for a wrong password or damaged backup. */
@Composable
fun RestoreSheet(
    restore: (backup: String, password: String) -> Boolean,
    openFile: (onResult: (String?) -> Unit) -> Unit,
    onRestored: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var backup by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val wrong = stringResource(R.string.restore_failed)
    RestoreSheetContent(
        backup = backup,
        password = password,
        working = working,
        error = error,
        onBackup = {
            backup = it
            error = null
        },
        onPassword = {
            password = it
            error = null
        },
        onOpenFile = {
            // Cancelled or unreadable files leave the field as it was; a bad file fails at Restore.
            openFile { text ->
                if (text != null) {
                    backup = text.trim()
                    error = null
                }
            }
        },
        onRestore = {
            working = true
            scope.launch {
                val ok = withContext(Dispatchers.Default) { restore(backup.trim(), password) }
                working = false
                if (ok) onRestored() else error = wrong
            }
        },
        modifier = modifier,
    )
}
