package com.nswsys.nuviobridge

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var apiKeyInput: EditText
    private lateinit var skipSponsoredSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.config_status)
        apiKeyInput = findViewById(R.id.tmdb_api_key)
        skipSponsoredSwitch = findViewById(R.id.skip_sponsored_sections)
        apiKeyInput.setText(AppSettings.savedTmdbApiKey(this))
        skipSponsoredSwitch.isChecked = AppSettings.skipSponsoredSections(this)
        skipSponsoredSwitch.setOnCheckedChangeListener { _, enabled ->
            AppSettings.setSkipSponsoredSections(this, enabled)
        }
        findViewById<Button>(R.id.save_tmdb_key).setOnClickListener {
            AppSettings.setTmdbApiKey(this, apiKeyInput.text.toString())
            refreshStatus()
            Toast.makeText(this, R.string.tmdb_key_saved, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.open_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.test_nuvio).setOnClickListener {
            val opened = NuvioLauncher.open(
                this,
                MediaMatch(603, MediaType.MOVIE, "The Matrix", 100.0)
            )
            if (!opened) Toast.makeText(this, R.string.test_failed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val messages = buildList {
            when {
                AppSettings.tmdbApiKey(this@MainActivity).isBlank() -> add(getString(R.string.status_missing_tmdb))
                !NuvioLauncher.isInstalled(this@MainActivity) -> add(getString(R.string.status_missing_nuvio))
                else -> add(getString(R.string.status_ready))
            }
            add(
                getString(
                    if (isAccessibilityServiceEnabled()) {
                        R.string.status_service_enabled
                    } else {
                        R.string.status_service_disabled
                    }
                )
            )
        }
        statusView.text = messages.joinToString("\n\n")
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, RecommendationAccessibilityService::class.java)
            .flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
