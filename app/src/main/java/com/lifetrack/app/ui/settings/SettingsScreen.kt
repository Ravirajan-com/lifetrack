package com.lifetrack.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifetrack.app.ui.common.Eyebrow
import com.lifetrack.app.ui.common.InkCard
import com.lifetrack.app.ui.theme.Ink

@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val status by vm.status.collectAsState()
    val account by vm.account.collectAsState()
    val consentRequest by vm.consentRequest.collectAsState()
    val context = LocalContext.current

    // Google's Drive consent screen arrives as a PendingIntent, so it needs
    // StartIntentSenderForResult rather than a plain activity launch.
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> vm.onConsentResult(result.data) }

    LaunchedEffect(consentRequest) {
        consentRequest?.let { intent ->
            consentLauncher.launch(IntentSenderRequest.Builder(intent).build())
            vm.consentLaunched()
        }
    }

    val isError = status.contains("Failed") || status.contains("Error") ||
        status.contains("declined")
    val isBusy = status.endsWith("...")

    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(24.dp))

        Eyebrow("Google Drive Sync")
        InkCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Sync your data (Expenses, Gym, Goals) to your private Google Drive app data folder.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = Ink.mint
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        "Status: $status",
                        style = MaterialTheme.typography.titleSmall,
                        color = when {
                            status.contains("Successful") -> Ink.mint
                            isError -> Ink.danger
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
                account?.let {
                    Text("Account: $it", style = MaterialTheme.typography.labelSmall)
                }

                Spacer(Modifier.height(24.dp))

                if (account == null) {
                    Button(
                        onClick = { vm.linkDrive(context) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isBusy,
                        colors = ButtonDefaults.buttonColors(containerColor = Ink.mint)
                    ) {
                        Text("Connect Google Drive", color = Ink.bg)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { vm.backupNow() },
                            modifier = Modifier.weight(1f),
                            enabled = !isBusy,
                            colors = ButtonDefaults.buttonColors(containerColor = Ink.mint)
                        ) {
                            Text("Backup Now", color = Ink.bg)
                        }
                        OutlinedButton(
                            onClick = { vm.restore() },
                            modifier = Modifier.weight(1f),
                            enabled = !isBusy
                        ) {
                            Text("Restore")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))
        Text(
            "Note: Backups are stored in a hidden folder on your Drive and cannot be " +
                "seen directly in the Drive app.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
