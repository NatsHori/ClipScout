package com.clipscout.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat

/**
 * クリップボード書き込み専用の透過ヘルパーActivity。
 *
 * ========== Android 10以降のバックグラウンドクリップボード制限について ==========
 * Android 10 (API 29) から、フォーカスを持たないアプリからのclipboard書き込みは
 * 無視またはブロックされる（ユーザー保護のための仕様）。
 *
 * 【回避手法 — 方式A: SYSTEM_ALERT_WINDOW + 透過Activity】
 * 1. ScreenshotDetectionService（Foreground Service）がDM文生成後に本Activityを起動
 * 2. SYSTEM_ALERT_WINDOW権限を持つアプリは、Android 10以降のBackground Activity Start
 *    制限の例外として、バックグラウンドからActivityを起動できる
 *    (ref: https://developer.android.com/guide/components/activities/background-starts)
 * 3. 本ActivityはTheme.ClipScout.Transparentで完全透明＆1pxサイズで起動
 * 4. onWindowFocusChanged(hasFocus=true) がコールされた時点でフォアグラウンド確保済み
 * 5. この時点でClipboardManager.setPrimaryClip()を呼び出し → 書き込み成功
 * 6. 即座にfinish()して終了（ユーザーには完了通知のみ表示）
 * ========================================================================
 */
class ClipboardHelperActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ClipScout_Clipboard"
        private const val CHANNEL_ID_COPIED = "clipscout_copied"
        private const val NOTIFICATION_ID_COPIED = 2001
    }

    private var clipboardWritten = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clipboard_helper)

        // ウィンドウを完全透明・最前面・タッチ不可に設定
        window.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            // ステータスバー・ナビゲーションバーを透明にしてシームレスに
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        }

        // 通知チャンネルを事前に作成
        createCopiedNotificationChannel()
    }

    /**
     * ウィンドウフォーカスが変化した際のコールバック。
     * hasFocus=true（＝フォアグラウンド確保）になったタイミングでクリップボードに書き込む。
     * これがAndroid 10+の制限を確実に回避するキーポイント。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.d(TAG, "onWindowFocusChanged: hasFocus=$hasFocus, written=$clipboardWritten")

        if (hasFocus && !clipboardWritten) {
            val generatedText = intent.getStringExtra(ScreenshotDetectionService.EXTRA_GENERATED_TEXT)

            if (!generatedText.isNullOrBlank()) {
                writeToClipboard(generatedText)
                clipboardWritten = true
                showCopiedNotification(generatedText)
            } else {
                Log.w(TAG, "Received empty text, skipping clipboard write.")
            }

            // 即座に終了してユーザーの操作を妨げない
            finish()
        }
    }

    /**
     * ClipboardManagerを使ってテキストをクリップボードに書き込む。
     * このメソッドはonWindowFocusChanged(hasFocus=true)の中から呼ばれるため、
     * フォアグラウンド確保済みの状態が保証されている。
     */
    private fun writeToClipboard(text: String) {
        try {
            val clipboard = getSystemService(ClipboardManager::class.java)
            val clip = ClipData.newPlainText("ClipScout Scout DM", text)
            clipboard.setPrimaryClip(clip)
            Log.i(TAG, "Clipboard write successful. Text length: ${text.length}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to clipboard: ${e.message}", e)
        }
    }

    /**
     * コピー完了を通知バナーでユーザーに知らせる。
     * 通知には生成されたDM文の冒頭20文字をプレビューとして表示。
     */
    private fun showCopiedNotification(copiedText: String) {
        val preview = if (copiedText.length > 40) "${copiedText.take(40)}…" else copiedText
        val nm = getSystemService(NotificationManager::class.java)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_COPIED)
            .setContentTitle("✅ DM文面をコピーしました")
            .setContentText(preview)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$preview\n\nそのまま貼り付けてご利用ください。")
            )
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIFICATION_ID_COPIED, notification)
    }

    private fun createCopiedNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        NotificationChannel(
            CHANNEL_ID_COPIED,
            "ClipScout コピー完了",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "スカウトDMのクリップボードコピー完了通知"
        }.also { nm.createNotificationChannel(it) }
    }
}
