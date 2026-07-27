package com.lifetrack.app.ui.settings

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lifetrack.app.data.repo.BackupRepository
import com.lifetrack.app.sync.DriveAuth
import com.lifetrack.app.sync.GoogleDriveManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val driveManager = GoogleDriveManager(app)
    private val backupRepo = BackupRepository.get(app)
    private val prefs = app.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)

    private val _status = MutableStateFlow("Not Connected")
    val status: StateFlow<String> = _status

    // Restored from prefs, so a process restart doesn't look like a signed-out state.
    private val _account = MutableStateFlow(prefs.getString("account_name", null))
    val account: StateFlow<String?> = _account

    /** Non-null when Google needs to show its consent screen. The UI launches it. */
    private val _consentRequest = MutableStateFlow<PendingIntent?>(null)
    val consentRequest: StateFlow<PendingIntent?> = _consentRequest

    private var busy = false

    /** What to resume once consent comes back. */
    private enum class Pending { BACKUP, RESTORE }
    private var pending: Pending? = null

    init {
        if (_account.value != null) _status.value = "Connected"
    }

    fun linkDrive(context: Context) = viewModelScope.launch {
        runCatching {
            _status.value = "Connecting..."
            val email = driveManager.signIn(context)
            if (email != null) {
                _account.value = email
                prefs.edit().putString("account_name", email).apply()
                _status.value = "Connected"
            } else {
                _status.value = "Failed to connect"
            }
        }.onFailure {
            Log.e(TAG, "Sign-in error", it)
            _status.value = "Connection Error: ${it.localizedMessage}"
        }
    }

    fun backupNow() = start(Pending.BACKUP)

    fun restore() = start(Pending.RESTORE)

    private fun start(op: Pending) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            pending = op
            _status.value = if (op == Pending.BACKUP) "Backing up..." else "Restoring..."
            runCatching {
                when (val auth = driveManager.requestAuthorization()) {
                    is DriveAuth.Authorized -> run(op, auth.accessToken)
                    is DriveAuth.NeedsConsent -> {
                        // Hand off to the UI; run() resumes in onConsentResult().
                        _status.value = "Waiting for Google permission..."
                        _consentRequest.value = auth.pendingIntent
                        busy = false
                    }
                }
            }.onFailure { fail(op, it) }
        }
    }

    fun consentLaunched() { _consentRequest.value = null }

    fun onConsentResult(data: Intent?) {
        val op = pending ?: return
        val token = driveManager.authorizationFromIntent(data)
        if (token == null) {
            pending = null
            busy = false
            _status.value = "Drive permission was declined"
            return
        }
        viewModelScope.launch {
            busy = true
            runCatching { run(op, token) }.onFailure { fail(op, it) }
        }
    }

    private suspend fun run(op: Pending, accessToken: String) {
        val service = driveManager.driveFor(accessToken)
        when (op) {
            Pending.BACKUP -> {
                driveManager.uploadBackup(service, backupRepo.exportAll())
                _status.value = "Backup Successful"
            }
            Pending.RESTORE -> {
                val json = driveManager.downloadBackup(service)
                _status.value = if (json == null) {
                    "No Backup Found"
                } else {
                    backupRepo.importAll(json)
                    "Restore Successful"
                }
            }
        }
        pending = null
        busy = false
    }

    private fun fail(op: Pending, t: Throwable) {
        val what = if (op == Pending.BACKUP) "Backup" else "Restore"
        Log.e(TAG, "$what failed", t)
        _status.value = "$what Failed: ${t.localizedMessage ?: t::class.java.simpleName}"
        pending = null
        busy = false
    }

    private companion object { const val TAG = "SettingsViewModel" }
}
