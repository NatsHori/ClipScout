package com.clipscout.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * スクリーンショット検知 + Gemini API連携を行うForeground Service。
 *
 * 動作フロー:
 * 1. ContentObserverでMediaStore.Images.Media.EXTERNAL_CONTENT_URIを監視
 * 2. 新規画像が「Screenshots」フォルダに追加されたことを確認
 * 3. GeminiApiClientでスカウトDM文を生成
 * 4. ClipboardHelperActivityを起動してクリップボードへコピー（Android 10+バックグラウンド制限回避）
 *
 * 【クリップボード制限回避の仕組み】
 * Android 10以降、バックグラウンドサービスから直接ClipboardManager.setPrimaryClip()を
 * 呼び出しても無視される（Android 10/11でサイレント失敗、12以降はさらに制限強化）。
 * SYSTEM_ALERT_WINDOW権限を持つアプリは、バックグラウンドからActivityを起動することが
 * できる（Background Activity Start制限の例外）。
 * そのため、透明な1pxのClipboardHelperActivityを一瞬起動し、
 * onWindowFocusChanged(hasFocus=true)のタイミング（フォアグラウンド確保後）で
 * クリップボードに書き込み、すぐにfinish()する。
 */
class ScreenshotDetectionService : Service() {

    companion object {
        const val CHANNEL_ID_MONITORING = "clipscout_monitoring"
        const val CHANNEL_ID_ERROR = "clipscout_error"
        const val NOTIFICATION_ID_FOREGROUND = 1001
        const val EXTRA_GENERATED_TEXT = "extra_generated_text"

        private const val TAG = "ClipScout_Service"

        /** スクリーンショット判定用のパスキーワード（小文字で比較） */
        private val SCREENSHOT_PATH_KEYWORDS = listOf("screenshot", "スクリーンショット")

        fun start(context: Context) {
            val intent = Intent(context, ScreenshotDetectionService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenshotDetectionService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val geminiClient = GeminiApiClient()
    private lateinit var screenshotObserver: ContentObserver

    /** 重複処理防止用：最後に処理したURIとフラグ */
    private var lastProcessedUri: Uri? = null
    private var isProcessing = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(NOTIFICATION_ID_FOREGROUND, buildMonitoringNotification())
        registerScreenshotObserver()
        Log.i(TAG, "Service started.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKYでシステムにkillされても自動再起動する
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(screenshotObserver)
        serviceScope.cancel()
        Log.i(TAG, "Service destroyed.")
        super.onDestroy()
    }

    // ========== ContentObserver登録 ==========

    /**
     * MediaStoreの画像テーブルを監視するContentObserverを登録する。
     * notifyForDescendants=true で子ディレクトリの変更も検知する。
     */
    private fun registerScreenshotObserver() {
        screenshotObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                uri?.let { onMediaStoreChanged(it) }
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            /* notifyForDescendants= */ true,
            screenshotObserver
        )
        Log.d(TAG, "Screenshot observer registered.")
    }

    // ========== スクリーンショット検出ロジック ==========

    /**
     * MediaStore変更通知を受け取り、スクリーンショットかどうかを判定する。
     * 直近5秒以内に追加された画像のうち、パスに"screenshot"を含むものを対象とする。
     */
    private fun onMediaStoreChanged(changedUri: Uri) {
        if (isProcessing) {
            Log.d(TAG, "Already processing, skipped.")
            return
        }

        serviceScope.launch {
            try {
                val screenshotInfo = findRecentScreenshot() ?: return@launch

                if (screenshotInfo.uri == lastProcessedUri) {
                    Log.d(TAG, "Already processed this URI, skipped.")
                    return@launch
                }

                Log.i(TAG, "New screenshot detected: ${screenshotInfo.name} at ${screenshotInfo.relativePath}")
                lastProcessedUri = screenshotInfo.uri
                isProcessing = true

                processScreenshot(screenshotInfo.uri)

            } catch (e: Exception) {
                Log.e(TAG, "Error in onMediaStoreChanged: ${e.message}", e)
            } finally {
                isProcessing = false
            }
        }
    }

    /**
     * MediaStoreから直近5秒以内に追加されたスクリーンショット画像を検索する。
     * "Screenshots"または"スクリーンショット"を含むパスのみを対象とする。
     *
     * @return スクリーンショット情報、見つからなければnull
     */
    private fun findRecentScreenshot(): ScreenshotInfo? {
        val fiveSecondsAgo = (System.currentTimeMillis() / 1000L) - 5

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        val selectionArgs = arrayOf(fiveSecondsAgo.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        return contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)) ?: ""
                val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)) ?: ""

                val combinedLower = (path + name).lowercase()
                val isScreenshot = SCREENSHOT_PATH_KEYWORDS.any { combinedLower.contains(it) }

                if (isScreenshot) {
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    return@use ScreenshotInfo(uri = uri, name = name, relativePath = path)
                }
            }
            null
        }
    }

    // ========== 処理本体 ==========

    /**
     * スクリーンショットURIからBitmapを取得し、Gemini APIでDM文を生成、
     * ClipboardHelperActivityへ渡す。
     */
    private suspend fun processScreenshot(uri: Uri) {
        val apiKey = PreferencesHelper.getApiKey(this)
        if (apiKey.isBlank()) {
            Log.w(TAG, "API key is empty. Showing error notification.")
            showErrorNotification("⚠️ APIキーが設定されていません", "ClipScoutアプリを開いてAPIキーを設定してください")
            return
        }

        val systemPrompt = PreferencesHelper.getSystemPrompt(this)
        val modelName = PreferencesHelper.getModelName(this)

        // 通知を「生成中」に更新
        updateMonitoringNotification("✨ スカウトDM生成中 ($modelName)...")

        val bitmap = loadBitmapFromUri(uri)
        if (bitmap == null) {
            Log.e(TAG, "Failed to load bitmap from URI: $uri")
            showErrorNotification("画像の読み込み失敗", "スクリーンショットを読み込めませんでした")
            updateMonitoringNotification(null)
            return
        }

        Log.d(TAG, "Calling Gemini API with model: $modelName...")
        val result = geminiClient.generateScoutDm(apiKey, modelName, systemPrompt, bitmap)
        bitmap.recycle()

        result.fold(
            onSuccess = { generatedText ->
                Log.i(TAG, "DM generated. Launching ClipboardHelperActivity.")
                launchClipboardHelper(generatedText)
            },
            onFailure = { error ->
                Log.e(TAG, "Gemini API error: ${error.message}", error)
                val errorMsg = error.message?.take(120) ?: "不明なエラー"
                showErrorNotification("DM生成エラー", errorMsg)
            }
        )

        // 通知を監視中に戻す
        updateMonitoringNotification(null)
    }

    /**
     * URIからBitmapを読み込む。
     * inSampleSize=2でリサイズし、メモリ消費を抑える。
     */
    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 2
                }
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap: ${e.message}", e)
            null
        }
    }

    /**
     * ClipboardHelperActivityをバックグラウンドから起動する。
     * SYSTEM_ALERT_WINDOW権限により、Android 10以降のBackground Activity Start制限を回避。
     */
    private fun launchClipboardHelper(text: String) {
        val intent = Intent(this, ClipboardHelperActivity::class.java).apply {
            putExtra(EXTRA_GENERATED_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        startActivity(intent)
    }

    // ========== 通知管理 ==========

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        // 常駐監視通知（低重要度: サイレント＆バナーなし）
        NotificationChannel(
            CHANNEL_ID_MONITORING,
            "ClipScout 監視",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "スクリーンショット監視中の常駐通知"
            setShowBadge(false)
        }.also { nm.createNotificationChannel(it) }

        // エラー・完了通知（通常重要度: バナー表示）
        NotificationChannel(
            CHANNEL_ID_ERROR,
            "ClipScout 通知",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "エラーや処理結果の通知"
        }.also { nm.createNotificationChannel(it) }
    }

    private fun buildMonitoringNotification(statusText: String? = null): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID_MONITORING)
            .setContentTitle("ClipScout")
            .setContentText(statusText ?: "スクリーンショットを監視中 📷")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateMonitoringNotification(statusText: String?) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID_FOREGROUND, buildMonitoringNotification(statusText))
    }

    private fun showErrorNotification(title: String, message: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID_ERROR)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .build()
        nm.notify(System.currentTimeMillis().toInt(), notification)
    }

    // ========== データクラス ==========

    private data class ScreenshotInfo(
        val uri: Uri,
        val name: String,
        val relativePath: String
    )
}
