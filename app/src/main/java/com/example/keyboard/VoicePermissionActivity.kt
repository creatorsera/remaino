package com.example.keyboard

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible helper that asks for the microphone permission. Android only allows
 * runtime permissions from an Activity, so the keyboard opens this, it disappears
 * immediately after the user answers, and the mic can then be used.
 */
class VoicePermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finish()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            Toast.makeText(
                this,
                if (granted) "Microphone allowed. Tap the mic again." else "Microphone permission is needed for speech.",
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }
    }

    private companion object {
        const val REQUEST_MIC = 41
    }
}
