package com.triplethreats.masteria.data

import android.annotation.SuppressLint
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseOptions
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.triplethreats.masteria.BuildConfig
import kotlinx.coroutines.tasks.await

/**
 * Firebase Authentication on the device (email + password, Google). After Firebase signs the user in,
 * the app sends the Firebase ID token to the backend (`POST /auth/firebase`), which verifies it and
 * returns the Masteria session token used for everything else.
 *
 * Configuration, either:
 *  - drop `google-services.json` into `android/app/` (the Google Services plugin is applied automatically), or
 *  - set FIREBASE_API_KEY, FIREBASE_APP_ID, FIREBASE_PROJECT_ID (and GOOGLE_WEB_CLIENT_ID) in local.properties.
 * Without either, [available] is false and the app falls back to the backend's own email/password auth.
 */
class FirebaseAuthClient(context: Context) {
    private val app = context.applicationContext

    init {
        // No google-services.json: initialise from local.properties values if they were provided.
        if (FirebaseApp.getApps(app).isEmpty() && BuildConfig.FIREBASE_API_KEY.isNotBlank() &&
            BuildConfig.FIREBASE_APP_ID.isNotBlank() && BuildConfig.FIREBASE_PROJECT_ID.isNotBlank()
        ) {
            runCatching {
                FirebaseApp.initializeApp(
                    app,
                    FirebaseOptions.Builder()
                        .setApiKey(BuildConfig.FIREBASE_API_KEY)
                        .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                        .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                        .build(),
                )
            }
        }
    }

    val available: Boolean get() = FirebaseApp.getApps(app).isNotEmpty()

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()

    /** OAuth web client id for Google sign-in: from google-services.json, else local.properties. */
    val googleWebClientId: String? by lazy {
        @SuppressLint("DiscouragedApi")
        val res = app.resources.getIdentifier("default_web_client_id", "string", app.packageName)
        (if (res != 0) app.getString(res) else null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.GOOGLE_WEB_CLIENT_ID.takeIf { it.isNotBlank() }
    }

    val googleAvailable: Boolean get() = available && googleWebClientId != null

    /** Signs in and returns a fresh Firebase ID token. */
    suspend fun signIn(email: String, password: String): String = mapErrors {
        val result = auth.signInWithEmailAndPassword(email.trim(), password).await()
        idToken(result.user)
    }

    /** Creates the Firebase account, sets the display name, sends a verification email, returns an ID token. */
    suspend fun signUp(name: String, email: String, password: String): String = mapErrors {
        val result = auth.createUserWithEmailAndPassword(email.trim(), password).await()
        val user = result.user ?: throw ApiException("Couldn't create your account. Please try again.")
        runCatching { user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name.trim()).build()).await() }
        runCatching { user.sendEmailVerification().await() }
        idToken(user)
    }

    /** Google account picker (Credential Manager). Returns null if the user dismissed it. [activityContext] must be an Activity. */
    suspend fun signInWithGoogle(activityContext: Context): String? = mapErrors {
        val clientId = googleWebClientId ?: throw ApiException("Google sign-in isn't set up in this build.")
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        val credential = try {
            CredentialManager.create(activityContext).getCredential(activityContext, request).credential
        } catch (e: GetCredentialCancellationException) {
            return@mapErrors null
        } catch (e: NoCredentialException) {
            throw ApiException("No Google account found on this phone. Add one in Settings, or use email.")
        } catch (e: GetCredentialException) {
            throw ApiException("Google sign-in didn't work: ${e.message ?: "please try again"}.")
        }
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw ApiException("Google sign-in returned an unexpected credential.")
        }
        val google = GoogleIdTokenCredential.createFrom(credential.data)
        val result = auth.signInWithCredential(GoogleAuthProvider.getCredential(google.idToken, null)).await()
        idToken(result.user)
    }

    suspend fun sendPasswordReset(email: String) = mapErrors {
        auth.sendPasswordResetEmail(email.trim()).await()
        Unit
    }

    fun signOut() {
        if (available) runCatching { auth.signOut() }
    }

    /** Best effort: Firebase may require a recent sign-in to delete; the backend data is deleted regardless. */
    suspend fun deleteCurrentUser() {
        if (!available) return
        runCatching { auth.currentUser?.delete()?.await() }
        signOut()
    }

    private suspend fun idToken(user: com.google.firebase.auth.FirebaseUser?): String {
        val u = user ?: throw ApiException("Sign-in didn't complete. Please try again.")
        return u.getIdToken(true).await().token ?: throw ApiException("Sign-in didn't complete. Please try again.")
    }

    private inline fun <T> mapErrors(block: () -> T): T = try {
        block()
    } catch (e: ApiException) {
        throw e
    } catch (e: FirebaseAuthWeakPasswordException) {
        throw ApiException(e.reason ?: "Please choose a stronger password (at least 6 characters).")
    } catch (e: FirebaseAuthUserCollisionException) {
        throw ApiException("An account with this email already exists. Sign in instead.")
    } catch (e: FirebaseAuthInvalidUserException) {
        throw ApiException("No account found for this email, or it has been disabled.")
    } catch (e: FirebaseAuthInvalidCredentialsException) {
        throw ApiException("Incorrect email or password.")
    } catch (e: FirebaseTooManyRequestsException) {
        throw ApiException("Too many attempts. Please wait a minute and try again.")
    } catch (e: FirebaseNetworkException) {
        throw ApiException("Can't reach Firebase. Check your connection and try again.", offline = true)
    } catch (e: FirebaseAuthException) {
        throw ApiException(e.message ?: "Sign-in failed. Please try again.")
    }
}
