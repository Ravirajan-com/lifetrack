package com.lifetrack.app.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Outcome of asking Google for permission to touch the app's Drive folder. */
sealed interface DriveAuth {
    /** Consent already granted -- here is a live OAuth access token. */
    data class Authorized(val accessToken: String) : DriveAuth

    /** First run, or consent was revoked. The UI must launch this and wait for the result. */
    data class NeedsConsent(val pendingIntent: PendingIntent) : DriveAuth
}

/**
 * Drive access for the appDataFolder.
 *
 * WHY THIS NO LONGER USES GoogleAccountCredential
 * -----------------------------------------------
 * The previous version signed in with Credential Manager (which yields an *ID token* -- proof of
 * who the user is) and handhanded the resulting email to
 * `GoogleAccountCredential.usingOAuth2(...).setSelectedAccountName(email)`.
 *
 * Those are unrelated systems. GoogleAccountCredential is the legacy AccountManager path: it looks
 * the name up among the device's registered accounts and requires the Drive scope to have already
 * been consented for this app. A Credential Manager sign-in registers nothing and grants no
 * scopes, so that lookup produced null and the first token fetch died inside
 * `android.accounts.Account`, whose constructor throws exactly
 *     IllegalArgumentException("the name must not be empty: " + name)
 * -- hence "the name must not be empty: null". The empty name was the ACCOUNT name. Not the file
 * name, not the application name.
 *
 * Authentication ("who are you") and authorization ("may I use your Drive") are separate steps.
 * This version performs the second one properly via AuthorizationClient, which returns an access
 * token directly, so no AccountManager involvement remains.
 */
class GoogleDriveManager(private val context: Context) {

    private val TAG = "GoogleDriveManager"

    /**
     * WEB (not Android) OAuth client id, used only to request the ID token.
     * The *Android* OAuth client -- package name + signing SHA-1 -- must also exist in the same
     * Cloud project, or authorization below fails regardless of what this code does.
     */
    private val WEB_CLIENT_ID =
        "248825356913-sdgtmojfotdgpqvbvqma71imppo42rgv.apps.googleusercontent.com"

    private val credentialManager = CredentialManager.create(context)
    private val authClient = Identity.getAuthorizationClient(context)

    // ------------------------------------------------------------------ identity

    /** Returns the signed-in user's email, for display only. Grants no Drive access by itself. */
    suspend fun signIn(activityContext: Context): String? {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(WEB_CLIENT_ID)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        return try {
            val result = credentialManager.getCredential(activityContext, request)
            GoogleIdTokenCredential.createFrom(result.credential.data).id
        } catch (e: Exception) {
            Log.e(TAG, "Sign-in failed", e)
            null
        }
    }

    suspend fun signOut() {
        credentialManager.clearCredentialState(ClearCredentialStateRequest())
    }

    // --------------------------------------------------------------- authorization

    private fun authorizationRequest(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DriveScopes.DRIVE_APPDATA)))
            .build()

    /**
     * Asks for the appdata scope. Silent and instant once consent exists, so calling it before
     * every upload is both safe and correct -- access tokens expire after about an hour.
     */
    suspend fun requestAuthorization(): DriveAuth {
        val result = authClient.authorize(authorizationRequest()).await()
        val resolution = result.pendingIntent
        return if (result.hasResolution() && resolution != null) {
            DriveAuth.NeedsConsent(resolution)
        } else {
            val token = result.accessToken
                ?: error("Google granted the scope but returned no access token")
            DriveAuth.Authorized(token)
        }
    }

    /** Pulls the access token out of the consent screen's result Intent. */
    fun authorizationFromIntent(data: Intent?): String? = runCatching {
        authClient.getAuthorizationResultFromIntent(data).accessToken
    }.onFailure { Log.e(TAG, "Could not read authorization result", it) }.getOrNull()

    // ------------------------------------------------------------------ drive

    /** Drive client that authenticates with a bearer token. No AccountManager, no account name. */
    fun driveFor(accessToken: String): Drive {
        val initializer = HttpRequestInitializer { request ->
            request.headers.authorization = "Bearer $accessToken"
            request.connectTimeout = 30_000
            request.readTimeout = 60_000
        }
        return Drive.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            initializer
        ).setApplicationName("LifeTrack").build()
    }

    suspend fun uploadBackup(service: Drive, content: String) = withContext(Dispatchers.IO) {
        val fileName = "lifetrack_backup.json"

        val existing = service.files().list()
            .setSpaces("appDataFolder")
            .setQ("name = '$fileName' and trashed = false")
            .setFields("files(id)")
            .execute().files

        val media = ByteArrayContent("application/json", content.toByteArray(Charsets.UTF_8))

        if (existing.isNullOrEmpty()) {
            val metadata = File().apply {
                name = fileName
                parents = listOf("appDataFolder")
            }
            service.files().create(metadata, media).setFields("id").execute()
            Log.d(TAG, "Created backup in appDataFolder")
        } else {
            // On update, send content only. Re-sending `parents` is rejected by Drive v3 and the
            // name is unchanged, so there is nothing useful to put in the metadata.
            service.files().update(existing[0].id, null, media).setFields("id").execute()
            Log.d(TAG, "Updated backup " + existing[0].id)
        }
    }

    suspend fun downloadBackup(service: Drive): String? = withContext(Dispatchers.IO) {
        val files = service.files().list()
            .setSpaces("appDataFolder")
            .setQ("name = 'lifetrack_backup.json' and trashed = false")
            .setFields("files(id)")
            .execute().files

        if (files.isNullOrEmpty()) return@withContext null

        val out = java.io.ByteArrayOutputStream()
        service.files().get(files[0].id).executeMediaAndDownloadTo(out)
        out.toString("UTF-8")
    }
}

/** Task -> coroutine, so no kotlinx-coroutines-play-services dependency is needed. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { value -> if (cont.isActive) cont.resume(value) }
    addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
}
