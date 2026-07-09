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
        private const val BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
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
     * @param apiKey   Gemini APIキー
     * @param systemPrompt スカウト生成用のシステムプロンプト
     * @param bitmap   スクリーンショットのBitmap
     * @return Result<String> 成功時はDM文、失敗時はエラー
     */
    suspend fun generateScoutDm(
        apiKey: String,
        systemPrompt: String,
        bitmap: Bitmap
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val base64Image = bitmapToBase64(bitmap)
            val requestBodyMap = buildRequestBody(systemPrompt, base64Image)
            val jsonBody = gson.toJson(requestBodyMap)

            val request = Request.Builder()
                .url("$BASE_URL?key=$apiKey")
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
                ?.firstOrNull()
                ?.text

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
        return mapOf(
            "contents" to listOf(
                mapOf(
                    "parts" to listOf(
                        mapOf("text" to systemPrompt),
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
                "temperature" to 0.7,
                "maxOutputTokens" to 600,
                "topP" to 0.9
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
