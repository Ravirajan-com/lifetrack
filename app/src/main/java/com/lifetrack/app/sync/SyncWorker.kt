package com.lifetrack.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lifetrack.app.data.repo.BackupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val backupRepo = BackupRepository.get(applicationContext)
        val driveManager = GoogleDriveManager(applicationContext)

        val prefs = applicationContext.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        prefs.getString("account_name", null) ?: return@withContext Result.failure()

        runCatching {
            when (val auth = driveManager.requestAuthorization()) {
                is DriveAuth.Authorized -> {
                    val service = driveManager.driveFor(auth.accessToken)
                    driveManager.uploadBackup(service, backupRepo.exportAll())
                    Result.success()
                }
                // A worker has no Activity to show consent on. The user must open Settings and
                // tap Backup Now once; after that this path stays silent forever.
                is DriveAuth.NeedsConsent -> Result.failure()
            }
        }.getOrElse {
            it.printStackTrace()
            Result.retry()
        }
    }
}
