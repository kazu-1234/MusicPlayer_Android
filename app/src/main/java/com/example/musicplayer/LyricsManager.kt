package com.example.musicplayer

import android.content.Context
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.math.BigInteger

data class LyricsResult(
    val id: Int,
    val name: String,
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val duration: Double,
    val instrumental: Boolean,
    val plainLyrics: String?,
    val syncedLyrics: String?
)

data class LrcLine(
    val timeMs: Long,
    val text: String
)

object LyricsManager {
    private val client = OkHttpClient()
    private val gson = Gson()
    private const val BASE_URL = "https://lrclib.net/api/search"

    fun parseLrc(lrc: String?): List<LrcLine> {
        if (lrc.isNullOrBlank()) return emptyList()
        val lines = mutableListOf<LrcLine>()
        // Format: [mm:ss.xx] text
        val regex = "\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})](.*)".toRegex()
        
        lrc.lines().forEach { line ->
            val match = regex.find(line)
            if (match != null) {
                val (min, sec, msStr, text) = match.destructured
                val ms = msStr.padEnd(3, '0').toLong() // .xx -> .xx0, .xxx -> .xxx
                val timeMs = min.toLong() * 60000 + sec.toLong() * 1000 + ms
                lines.add(LrcLine(timeMs, text.trim()))
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    // 文字列の正規化（小文字化、英数字のみ）
    private fun normalize(s: String?): String {
        return s?.lowercase()?.replace("[^a-z0-9]".toRegex(), "") ?: ""
    }

    // クエリがターゲットに含まれているか、またはその逆か（単純な包含チェック）
    private fun isFuzzyMatch(query: String, target: String): Boolean {
        val nQuery = normalize(query)
        val nTarget = normalize(target)
        if (nQuery.isBlank() || nTarget.isBlank()) return false
        return nTarget.contains(nQuery) || nQuery.contains(nTarget)
    }

    suspend fun getLyrics(context: Context, artist: String, title: String): LyricsResult? {
        return withContext(Dispatchers.IO) {
            // キャッシュキー修正: 日本語タイトルなどが _ に置換されて重複するのを防ぐため、ハッシュ値を使用
            val rawKey = "${artist}_${title}"
            val md = MessageDigest.getInstance("SHA-256")
            val hashParams = md.digest(rawKey.toByteArray())
            val hashString = String.format("%064x", BigInteger(1, hashParams))
            val cacheKey = "lyrics_$hashString"
            val cacheFile = File(context.cacheDir, "$cacheKey.json")

            // 1. Check Cache
            if (cacheFile.exists()) {
                try {
                    val cachedJson = cacheFile.readText()
                    return@withContext gson.fromJson(cachedJson, LyricsResult::class.java)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 2. Fetch from API (Search)
            try {
                // アーティスト名と曲名のみで検索
                val urlBuilder = StringBuilder(BASE_URL)
                urlBuilder.append("?artist_name=").append(URLEncoder.encode(artist, "UTF-8"))
                urlBuilder.append("&track_name=").append(URLEncoder.encode(title, "UTF-8"))

                val request = Request.Builder()
                    .url(urlBuilder.toString())
                    .header("User-Agent", "MusicPlayer/2.5.6 (https://github.com/kazu-1234/MusicPlayer)")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        // 検索結果はリスト配列
                        val listType = object : com.google.gson.reflect.TypeToken<List<LyricsResult>>() {}.type
                        val results: List<LyricsResult> = gson.fromJson(body, listType)
                        
                        // フィルタリング: アーティスト名と曲名がある程度一致するものだけに絞る
                        val validResults = results.filter { result ->
                            isFuzzyMatch(artist, result.artistName) && isFuzzyMatch(title, result.trackName)
                        }
                        
                        // 同期歌詞があるものを優先、なければ最初の結果
                        val bestMatch = validResults.firstOrNull { !it.syncedLyrics.isNullOrBlank() } 
                                     ?: validResults.firstOrNull()
                        
                        if (bestMatch != null) {
                            // 3. Save to Cache (単一オブジェクトとして保存)
                            val json = gson.toJson(bestMatch)
                            cacheFile.writeText(json)
                            return@withContext bestMatch
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return@withContext null
        }
    }
}
