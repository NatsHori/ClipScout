package com.clipscout.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * EncryptedSharedPreferencesを用いた安全なデータ永続化ヘルパー。
 * APIキーはAES256-GCM暗号化でローカルに保存される。
 */
object PreferencesHelper {

    private const val PREF_FILE_NAME = "clipscout_secure_prefs"
    private const val KEY_API_KEY = "gemini_api_key"
    private const val KEY_SYSTEM_PROMPT = "system_prompt"
    private const val KEY_SERVICE_ENABLED = "service_enabled"
    private const val KEY_MODEL_NAME = "gemini_model_name"

    const val DEFAULT_MODEL_NAME = "gemini-1.5-flash"

    val DEFAULT_SYSTEM_PROMPT = """あなたはプロのスカウト担当者です。
以下のInstagram（またはSNS）のスクリーンショット画像を分析し、そのユーザーに送るスカウトDMを日本語で生成してください。

【ルール】
- 相手のプロフィールや投稿内容に基づき、具体的な魅力を1〜2点ピックアップして言及する
- 丁寧かつ親しみやすいトーンにする（堅すぎず、馴れ馴れしすぎない）
- 300文字以内でまとめる
- スカウトの目的（モデル/インフルエンサー/アンバサダー等）は画像の文脈から推測して自然に盛り込む
- 挨拶から始め、自然なDM文面にする
- 最後にアクション（返信をお待ちしています等）を添える

スカウトDM本文のみを出力してください。説明文・前置き・コメントは一切不要です。""".trimIndent()

    /**
     * EncryptedSharedPreferencesインスタンスを生成して返す。
     * MasterKey.KeyScheme.AES256_GCMで暗号化。
     */
    private fun getEncryptedPreferences(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREF_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ========== APIキー ==========

    fun getApiKey(context: Context): String {
        return getEncryptedPreferences(context).getString(KEY_API_KEY, "") ?: ""
    }

    fun saveApiKey(context: Context, apiKey: String) {
        getEncryptedPreferences(context)
            .edit()
            .putString(KEY_API_KEY, apiKey.trim())
            .apply()
    }

    // ========== システムプロンプト ==========

    fun getSystemPrompt(context: Context): String {
        return getEncryptedPreferences(context)
            .getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT)
            ?: DEFAULT_SYSTEM_PROMPT
    }

    fun saveSystemPrompt(context: Context, prompt: String) {
        getEncryptedPreferences(context)
            .edit()
            .putString(KEY_SYSTEM_PROMPT, prompt)
            .apply()
    }

    // ========== サービス有効/無効フラグ ==========

    fun isServiceEnabled(context: Context): Boolean {
        return getEncryptedPreferences(context)
            .getBoolean(KEY_SERVICE_ENABLED, false)
    }

    fun setServiceEnabled(context: Context, enabled: Boolean) {
        getEncryptedPreferences(context)
            .edit()
            .putBoolean(KEY_SERVICE_ENABLED, enabled)
            .apply()
    }

    // ========== Geminiモデル名 ==========

    fun getModelName(context: Context): String {
        return getEncryptedPreferences(context)
            .getString(KEY_MODEL_NAME, DEFAULT_MODEL_NAME)
            ?: DEFAULT_MODEL_NAME
    }

    fun saveModelName(context: Context, modelName: String) {
        getEncryptedPreferences(context)
            .edit()
            .putString(KEY_MODEL_NAME, modelName.trim())
            .apply()
    }
}
