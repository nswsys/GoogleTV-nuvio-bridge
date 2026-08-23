package com.nswsys.nuviobridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

object NuvioLauncher {
    private val knownPackages = listOf("com.nuvio.tv", "com.nuvio.app")

    fun isInstalled(context: Context): Boolean =
        knownPackages.any { context.packageManager.getLaunchIntentForPackage(it) != null } ||
            buildIntent(MediaMatch(603, MediaType.MOVIE, "The Matrix", 100.0))
                .resolveActivity(context.packageManager) != null

    fun open(context: Context, match: MediaMatch): Boolean {
        val baseIntent = buildIntent(match)
        val installedPackage = knownPackages.firstOrNull {
            context.packageManager.getLaunchIntentForPackage(it) != null
        }

        val explicitIntent = Intent(baseIntent).apply {
            installedPackage?.let { setPackage(it) }
        }
        if (startSafely(context, explicitIntent)) return true

        // The package name differs between Nuvio distributions. Falling back
        // to the registered nuvio:// handler keeps both variants compatible.
        return startSafely(context, baseIntent)
    }

    private fun buildIntent(match: MediaMatch): Intent {
        val uri = Uri.parse("nuvio://tmdb/${match.type.nuvioPath}/${match.tmdbId}")
        return Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    private fun startSafely(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (exception: Exception) {
        Log.w("NuvioBridgeLauncher", "Unable to open ${intent.data}", exception)
        false
    }
}
