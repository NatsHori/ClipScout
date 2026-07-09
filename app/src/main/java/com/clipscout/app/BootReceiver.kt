package com.clipscout.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 端末起動時にScreenshotDetectionServiceを自動再開するBroadcastReceiver。
 *
 * 必要な権限: android.permission.RECEIVE_BOOT_COMPLETED
 * トリガー:
 *   - android.intent.action.BOOT_COMPLETED (標準Android)
 *   - android.intent.action.QUICKBOOT_POWERON (Huawei/MIUI系)
 *
 * PreferencesHelperで「サービス有効」が保存されている場合のみ起動する。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ClipScout_Boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Boot broadcast received: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            if (PreferencesHelper.isServiceEnabled(context)) {
                Log.i(TAG, "Service is enabled. Starting ScreenshotDetectionService...")
                ScreenshotDetectionService.start(context)
            } else {
                Log.d(TAG, "Service is disabled. Skipping auto-start.")
            }
        }
    }
}
