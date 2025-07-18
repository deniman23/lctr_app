package com.example.lctr_app

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.google.zxing.integration.android.IntentIntegrator

class ScanContract : ActivityResultContract<Void?, String?>() {
    override fun createIntent(context: Context, input: Void?): Intent {
        val activity = context as? Activity ?: throw IllegalArgumentException("Context must be an Activity")
        return IntentIntegrator(activity).createScanIntent()
    }

    override fun parseResult(resultCode: Int, intent: Intent?): String? {
        return IntentIntegrator.parseActivityResult(resultCode, intent)?.contents
    }
}