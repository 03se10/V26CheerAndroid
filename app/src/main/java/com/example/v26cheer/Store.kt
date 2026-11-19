package com.example.v26cheer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Song(val name: String, val uri: String)

object Store {
    private fun p(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun songs(c: Context): MutableList<Song> {
        val arr = JSONArray(p(c).getString("songs", "[]"))
        return MutableList(arr.length()) {
            val o = arr.getJSONObject(it)
            Song(o.getString("name"), o.getString("uri"))
        }
    }

    fun saveSongs(c: Context, list: List<Song>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("uri", it.uri)) }
        p(c).edit().putString("songs", arr.toString()).apply()
    }

    // 인식 영역(화면 대비 %), 재생 시간(초, 0=끝까지)
    fun int(c: Context, key: String, def: Int) = p(c).getInt(key, def)
    fun putInt(c: Context, key: String, v: Int) = p(c).edit().putInt(key, v).apply()
}

object Matcher {
    fun norm(s: String) = s.filter { it.isLetterOrDigit() }.lowercase()

    private fun lev(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    /** OCR 결과 텍스트 안에서 등록된 선수 이름을 찾는다. (OCR 오탈자 1글자까지 허용) */
    fun find(text: String, songs: List<Song>): Song? {
        val t = norm(text)
        if (t.isEmpty()) return null
        var best: Song? = null
        var bestScore = Int.MAX_VALUE
        for (s in songs) {
            val n = norm(s.name)
            if (n.isEmpty()) continue
            var score = Int.MAX_VALUE
            if (t.contains(n)) score = -n.length
            else if (n.length >= 3 && t.length >= n.length) {
                for (i in 0..t.length - n.length) {
                    if (lev(t.substring(i, i + n.length), n) <= 1) { score = 100 - n.length; break }
                }
            }
            if (score < bestScore) { bestScore = score; best = s }
        }
        return best
    }
}
