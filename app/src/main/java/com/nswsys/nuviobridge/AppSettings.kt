package com.nswsys.nuviobridge

import android.content.Context

object AppSettings {
    private const val PREFERENCES = "app_settings"
    private const val TMDB_API_KEY = "tmdb_api_key"
    private const val SKIP_SPONSORED_SECTIONS = "skip_sponsored_sections"
    private const val SPONSORED_DEBUG_LOGGING = "sponsored_debug_logging"

    fun tmdbApiKey(context: Context): String {
        val saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(TMDB_API_KEY, null)
            .orEmpty()
            .trim()
        return saved.ifBlank { BuildConfig.TMDB_API_KEY.trim() }
    }

    fun savedTmdbApiKey(context: Context): String =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(TMDB_API_KEY, "")
            .orEmpty()

    fun setTmdbApiKey(context: Context, value: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(TMDB_API_KEY, value.trim())
            .apply()
    }

    fun skipSponsoredSections(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(SKIP_SPONSORED_SECTIONS, true)

    fun setSkipSponsoredSections(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(SKIP_SPONSORED_SECTIONS, enabled)
            .apply()
    }

    /**
     * Ad markup differs per Google TV build, locale and A/B bucket, so the
     * service can dump the focused row and the detector's verdict to logcat
     * (`adb logcat -s NuvioBridgeAds:D`) to diagnose a device that is not
     * skipping its ads.
     */
    fun sponsoredDebugLogging(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(SPONSORED_DEBUG_LOGGING, false)

    fun setSponsoredDebugLogging(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(SPONSORED_DEBUG_LOGGING, enabled)
            .apply()
    }
}
