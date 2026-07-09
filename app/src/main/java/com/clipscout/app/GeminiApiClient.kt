package com.clipscout.app

import android.graphics.Bitmap
import android.util.Base64
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Gemini 1.5 Flash APIとのHTTP通信クライアント。
 * OkHttp + Kotlin Coroutinesを使用。
 *
 * エンドポイント:
 *   https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent
 *
 * リクエスト構造:
 *   contents[0].parts[0] = systemPrompt (text)
 *   contents[0].parts[1] = screenshot image (inline_data / base64 JPEG)
 */
class GeminiApiClient {

    companion object {
        private const val BASE_URL_PREFIX =
            "https://generativelanguage.googleapis.com/v1beta/models/"
        private const val CONNECT_TIMEOUT_SEC = 30L
        private const val READ_TIMEOUT_SEC = 60L
        private const val WRITE_TIMEOUT_SEC = 30L
        /** JPEG圧縮品質（0-100）。85はファイルサイズと画質のバランスが良い。 */
        private const val JPEG_QUALITY = 85
        /** 大きすぎる画像をリサイズしてメモリとAPIコストを削減するための最大サンプリングサイズ */
        private const val BITMAP_SAMPLE_SIZE = 2
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * スクリーンショット画像とシステムプロンプトをGemini APIに送り、
     * スカウトDM文を生成して返す。
     *
     * @param apiKey     Gemini APIキー
     * @param modelName  使用モデル名 (例: gemini-1.5-flash, gemini-1.5-pro)
     * @param systemPrompt スカウト生成用のシステムプロンプト
     * @param bitmap     スクリーンショットのBitmap
     * @return Result<String> 成功時はDM文、失敗時はエラー
     */
    suspend fun generateScoutDm(
        apiKey: String,
        modelName: String = "gemini-1.5-flash",
        systemPrompt: String,
        bitmap: Bitmap
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val base64Image = bitmapToBase64(bitmap)
            val requestBodyMap = buildRequestBody(systemPrompt, base64Image)
            val jsonBody = gson.toJson(requestBodyMap)

            val endpointUrl = "${BASE_URL_PREFIX}${modelName}:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(endpointUrl)
                .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            val responseBodyStr = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Gemini API Error [${response.code}]: ${responseBodyStr.take(300)}")
                )
            }

            val parsedResponse = gson.fromJson(responseBodyStr, GeminiResponse::class.java)
            val generatedText = parsedResponse.candidates
                ?.firstOrNull()
                ?.content
                ?.parts
                ?.mapNotNull { it.text }
                ?.joinToString("")

            if (generatedText.isNullOrBlank()) {
                Result.failure(Exception("Gemini APIからの応答が空でした"))
            } else {
                Result.success(generatedText.trim())
            }

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * BitmapをBase64エンコードされたJPEG文字列に変換する。
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    /**
     * Gemini APIへのリクエストボディを構築する。
     *
     * JSON構造:
     * {
     *   "contents": [{
     *     "parts": [
     *       { "text": "systemPrompt" },
     *       { "inline_data": { "mime_type": "image/jpeg", "data": "base64..." } }
     *     ]
     *   }],
     *   "generationConfig": { "temperature": 0.7, "maxOutputTokens": 600 }
     * }
     */
    private fun buildRequestBody(systemPrompt: String, base64Image: String): Map<String, Any> {
        val userInstruction = """
以下のスクリーンショットのプロフィールや投稿内容を分析し、対象者に送るスカウトDMを作成してください。

【厳格な文章ルール】
1. 短すぎる文章（100文字以下）や、途中で途切れる文章は禁止です。必ず挨拶から最後の締めの言葉まで完全に完結させた180文字〜250文字程度の文章を作成してください。
2. 画像から相手の具体的な魅力や世界観を最低2つ読み取り、文章内で自然に言及して褒めてください。
3. 構成は「丁寧な挨拶 → 具体的な魅力への共感・賞賛 → スカウトの提案（モデル/アンバサダー等） → 返信のお願い」にしてください。
4. 前置き・説明文・思考プロセス等は一切出力せず、そのまま送れるDM本文のみを出力してください。
""".trimIndent()

        return mapOf(
            "system_instruction" to mapOf(
                "parts" to listOf(
                    mapOf("text" to systemPrompt)
                )
            ),
            "contents" to listOf(
                mapOf(
                    "parts" to listOf(
                        mapOf("text" to userInstruction),
                        mapOf(
                            "inline_data" to mapOf(
                                "mime_type" to "image/jpeg",
                                "data" to base64Image
                            )
                        )
                    )
                )
            ),
            "generationConfig" to mapOf(
                "temperature" to 0.75,
                "maxOutputTokens" to 4096,
                "topP" to 0.95
            )
        )
    }

    // ===== レスポンスのデータクラス（Gson用） =====

    data class GeminiResponse(
        val candidates: List<Candidate>?
    )

    data class Candidate(
        val content: Content?
    )

    data class Content(
        val parts: List<Part>?
    )

    data class Part(
        val text: String?
    )
}
