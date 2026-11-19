package com.example.v26cheer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 선수 한 명: 응원가(필수), 등장곡(선택) */
data class Player(val name: String, val cheer: String?, val walk: String?)

object Store {
    private fun p(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun players(c: Context): MutableList<Player> {
        val sp = p(c)
        val raw = sp.getString("players", null)
        if (raw == null) { // 이전 버전(songs) 데이터 이전
            val old = JSONArray(sp.getString("songs", "[]"))
            return MutableList(old.length()) {
                val o = old.getJSONObject(it)
                Player(o.getString("name"), o.getString("uri"), null)
            }
        }
        val arr = JSONArray(raw)
        return MutableList(arr.length()) {
            val o = arr.getJSONObject(it)
            Player(
                o.getString("name"),
                o.optString("cheer").ifEmpty { null },
                o.optString("walk").ifEmpty { null }
            )
        }
    }

    fun savePlayers(c: Context, list: List<Player>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("name", it.name)
                    .put("cheer", it.cheer ?: "").put("walk", it.walk ?: "")
            )
        }
        p(c).edit().putString("players", arr.toString()).apply()
    }

    fun int(c: Context, key: String, def: Int) = p(c).getInt(key, def)
    fun putInt(c: Context, key: String, v: Int) = p(c).edit().putInt(key, v).apply()
    fun str(c: Context, key: String, def: String = "") = p(c).getString(key, def) ?: def
    fun putStr(c: Context, key: String, v: String) = p(c).edit().putString(key, v).apply()
}

/**
 * 화면 영역 (좌, 위, 너비, 높이) — 화면 대비 비율.
 * 컴프야v26 세로 화면(720x1280) 스크린샷 기준으로 측정한 값입니다.
 */
object Regions {
    val BATTER = floatArrayOf(0.75f, 0.832f, 0.17f, 0.030f)      // 타자 카드 이름
    val PLAYBALL = floatArrayOf(0.463f, 0.886f, 0.42f, 0.052f)   // PLAY BALL 버튼(보라색)
    val YEL_TOP = floatArrayOf(0.040f, 0.012f, 0.022f, 0.030f)   // 위쪽 팀 노란 막대
    val YEL_BOT = floatArrayOf(0.040f, 0.044f, 0.022f, 0.034f)   // 아래쪽 팀 노란 막대
    val TEAM_TOP = floatArrayOf(0.122f, 0.014f, 0.06f, 0.020f)   // 위쪽 팀 이름
    val TEAM_BOT = floatArrayOf(0.122f, 0.049f, 0.06f, 0.020f)   // 아래쪽 팀 이름
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

    /** OCR 텍스트에서 등록된 선수 이름 찾기 (오탈자 1글자 허용, '26 같은 접미사는 무시) */
    fun find(text: String, players: List<Player>): Player? {
        val t = norm(text)
        if (t.isEmpty()) return null
        var best: Player? = null
        var bestScore = Int.MAX_VALUE
        for (s in players) {
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
