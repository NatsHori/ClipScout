package com.clipscout.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.clipscout.app.databinding.ActivityMainBinding

/**
 * ClipScoutのメイン設定画面。
 *
 * 機能:
 * - Gemini APIキーの入力・表示切替・保存
 * - システムプロンプトの編集・リセット・保存
 * - サービスのON/OFFトグル
 * - 権限ステータスの表示と各権限への誘導
 *   - READ_MEDIA_IMAGES（メディア読み取り）
 *   - POST_NOTIFICATIONS（通知）
 *   - SYSTEM_ALERT_WINDOW（他アプリ上に表示 ← クリップボード書き込みに必須）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** READ_MEDIA_IMAGES / POST_NOTIFICATIONS を一括でリクエストするランチャー */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        updatePermissionStatus()
        val allGranted = results.values.all { it }
        if (allGranted) {
            showToast("✅ 権限が付与されました")
        } else {
            showToast("一部の権限が拒否されました。設定から手動で付与してください。")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClickListeners()
        loadSavedSettings()
    }

    override fun onResume() {
        super.onResume()
        // 設定画面から戻った際に権限・サービス状態を再チェック
        updatePermissionStatus()
        updateServiceStatus()
    }

    // ========== UI セットアップ ==========

    private fun setupClickListeners() {
        // APIキー表示/非表示トグル
        binding.btnToggleApiKeyVisibility.setOnClickListener {
            val isCurrentlyHidden = binding.etApiKey.transformationMethod != null
            if (isCurrentlyHidden) {
                binding.etApiKey.transformationMethod = null
                binding.btnToggleApiKeyVisibility.text = "隠す"
            } else {
                binding.etApiKey.transformationMethod = PasswordTransformationMethod.getInstance()
                binding.btnToggleApiKeyVisibility.text = "表示"
            }
            // カーソルを末尾へ移動
            binding.etApiKey.setSelection(binding.etApiKey.text?.length ?: 0)
        }

        // 設定保存ボタン
        binding.btnSaveSettings.setOnClickListener {
            saveSettings()
        }

        // プロンプトリセットボタン
        binding.btnResetPrompt.setOnClickListener {
            binding.etSystemPrompt.setText(PreferencesHelper.DEFAULT_SYSTEM_PROMPT)
            showToast("プロンプトをデフォルトに戻しました")
        }

        // サービスON/OFFスイッチ
        binding.switchService.setOnCheckedChangeListener { _, isChecked ->
            onServiceToggled(isChecked)
        }

        // 権限リクエストボタン（メディア + 通知）
        binding.btnRequestRuntimePermissions.setOnClickListener {
            requestRuntimePermissions()
        }

        // オーバーレイ権限設定画面へ誘導ボタン
        binding.btnOpenOverlaySettings.setOnClickListener {
            openOverlayPermissionSettings()
        }
    }

    // ========== 設定の読み込み / 保存 ==========

    private fun loadSavedSettings() {
        // APIキーはマスク表示でロード
        binding.etApiKey.setText(PreferencesHelper.getApiKey(this))
        binding.etApiKey.transformationMethod = PasswordTransformationMethod.getInstance()
        binding.btnToggleApiKeyVisibility.text = "表示"

        // システムプロンプト
        binding.etSystemPrompt.setText(PreferencesHelper.getSystemPrompt(this))

        // サービス状態（スイッチはonResumeで再チェックするためここでは静かにセット）
        binding.switchService.isChecked = PreferencesHelper.isServiceEnabled(this)
    }

    private fun saveSettings() {
        val apiKey = binding.etApiKey.text.toString().trim()
        val systemPrompt = binding.etSystemPrompt.text.toString().trim()

        if (apiKey.isBlank()) {
            binding.etApiKey.error = "APIキーを入力してください"
            binding.etApiKey.requestFocus()
            return
        }
        if (systemPrompt.isBlank()) {
            binding.etSystemPrompt.error = "プロンプトを入力してください"
            binding.etSystemPrompt.requestFocus()
            return
        }

        PreferencesHelper.saveApiKey(this, apiKey)
        PreferencesHelper.saveSystemPrompt(this, systemPrompt)
        showToast("💾 設定を保存しました")
    }

    // ========== サービスON/OFF ==========

    private fun onServiceToggled(isChecked: Boolean) {
        if (isChecked) {
            // 有効化前のバリデーション
            if (!hasAllRequiredPermissions()) {
                binding.switchService.isChecked = false
                showToast("❌ 必要な権限をすべて付与してから有効にしてください")
                return
            }
            if (PreferencesHelper.getApiKey(this).isBlank()) {
                binding.switchService.isChecked = false
                showToast("❌ APIキーを保存してから有効にしてください")
                return
            }
            // サービス起動
            PreferencesHelper.setServiceEnabled(this, true)
            ScreenshotDetectionService.start(this)
            showToast("🟢 スクリーンショット監視を開始しました")
        } else {
            // サービス停止
            PreferencesHelper.setServiceEnabled(this, false)
            ScreenshotDetectionService.stop(this)
            showToast("🔴 監視を停止しました")
        }
        updateServiceStatus()
    }

    private fun updateServiceStatus() {
        val isEnabled = PreferencesHelper.isServiceEnabled(this)
        // スイッチをプログラムから変更する際、リスナーが二重発火しないよう一時的にリスナーを外す
        binding.switchService.setOnCheckedChangeListener(null)
        binding.switchService.isChecked = isEnabled
        binding.switchService.setOnCheckedChangeListener { _, checked -> onServiceToggled(checked) }

        binding.tvServiceStatus.text = if (isEnabled) "🟢 監視中" else "🔴 停止中"
        binding.tvServiceStatus.setTextColor(
            if (isEnabled)
                getColor(R.color.status_active)
            else
                getColor(R.color.status_inactive)
        )
    }

    // ========== 権限管理 ==========

    private fun updatePermissionStatus() {
        val hasMedia = hasMediaPermission()
        val hasNotification = hasNotificationPermission()
        val hasOverlay = Settings.canDrawOverlays(this)

        fun statusText(granted: Boolean) = if (granted) "✅ 付与済み" else "❌ 未付与"
        fun statusColor(granted: Boolean) = if (granted) getColor(R.color.status_active) else getColor(R.color.status_inactive)

        binding.tvPermMediaStatus.text = statusText(hasMedia)
        binding.tvPermMediaStatus.setTextColor(statusColor(hasMedia))

        binding.tvPermNotifStatus.text = statusText(hasNotification)
        binding.tvPermNotifStatus.setTextColor(statusColor(hasNotification))

        binding.tvPermOverlayStatus.text = statusText(hasOverlay)
        binding.tvPermOverlayStatus.setTextColor(statusColor(hasOverlay))

        // 全権限が揃っていればボタンを非表示
        val needsRuntimePerms = !hasMedia || !hasNotification
        binding.btnRequestRuntimePermissions.visibility =
            if (needsRuntimePerms) View.VISIBLE else View.GONE

        binding.btnOpenOverlaySettings.visibility =
            if (!hasOverlay) View.VISIBLE else View.GONE

        // 全権限ステータスバナー
        val allGranted = hasMedia && hasNotification && hasOverlay
        binding.tvPermissionsBanner.visibility = if (allGranted) View.GONE else View.VISIBLE
    }

    private fun hasAllRequiredPermissions(): Boolean {
        return hasMediaPermission() && hasNotificationPermission() && Settings.canDrawOverlays(this)
    }

    private fun hasMediaPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_MEDIA_IMAGES
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Android 12以下は不要
        }
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(Manifest.permission.READ_MEDIA_IMAGES)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun openOverlayPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    // ========== ユーティリティ ==========

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
