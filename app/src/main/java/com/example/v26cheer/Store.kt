package com.example.v26cheer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 선수 한 명: 응원가(필수), 등장곡(선택) */
data class Player(val name: String, val cheer: String?, val walk: String?)

/**
 * 팀 응원가. 이닝에 맞춰 자동 재생.
 * inning: 0=아무 이닝, 1~12 / orMore: 그 이닝 이후 매 이닝 / half: 0 상관없음, 1 초, 2 말
 * side: 0 상관없음, 1 내 팀 공격 때, 2 내 팀 수비 때 / sec: 0=끝까지
 */
data class TeamSong(
    val id: Long,
    val name: String,
    val uri: String?,
    val enabled: Boolean,
    val inning: Int,
    val orMore: Boolean,
    val half: Int,
    val side: Int,
    val sec: Int
)

/** 서비스가 홈 화면에 보여줄 실시간 상태 */
object Live {
    @Volatile var text: String = ""
}

object Store {
    private fun p(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    // ── 선수 ──
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

    // ── 팀 응원가 ──
    fun teamSongs(c: Context): MutableList<TeamSong> {
        val arr = JSONArray(p(c).getString("teamSongs", "[]"))
        return MutableList(arr.length()) {
            val o = arr.getJSONObject(it)
            TeamSong(
                o.optLong("id", it.toLong()),
                o.optString("name", "팀 응원가"),
                o.optString("uri").ifEmpty { null },
                o.optBoolean("enabled", true),
                o.optInt("inning", 8),
                o.optBoolean("orMore", false),
                o.optInt("half", 0),
                o.optInt("side", 0),
                o.optInt("sec", 40)
            )
        }
    }

    fun saveTeamSongs(c: Context, list: List<TeamSong>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("uri", it.uri ?: "")
                    .put("enabled", it.enabled).put("inning", it.inning).put("orMore", it.orMore)
                    .put("half", it.half).put("side", it.side).put("sec", it.sec)
            )
        }
        p(c).edit().putString("teamSongs", arr.toString()).apply()
    }

    // ── 기본 설정 ──
    fun int(c: Context, key: String, def: Int) = p(c).getInt(key, def)
    fun putInt(c: Context, key: String, v: Int) = p(c).edit().putInt(key, v).apply()
    fun str(c: Context, key: String, def: String = "") = p(c).getString(key, def) ?: def
    fun putStr(c: Context, key: String, v: String) = p(c).edit().putString(key, v).apply()
    fun bool(c: Context, key: String, def: Boolean) = p(c).getBoolean(key, def)
    fun putBool(c: Context, key: String, v: Boolean) = p(c).edit().putBoolean(key, v).apply()
}

/**
 * 화면 영역 (좌, 위, 너비, 높이) — 화면 대비 비율.
 * 사용자가 직접 지정한 6개 박스(타자 이름 / PLAY BALL / 스코어보드 / 이닝 / 해설 / 선수 소개바)를 저장해 두고,
 * 거기서 노란 막대·팀 이름·이닝 숫자·초말 삼각형 영역을 계산합니다.
 * 저장된 설정이 없으면 기본값을 씁니다. (스코어보드·이닝·소개바는 1080x2340 폰 스크린샷에서 측정,
 * 타자 이름·PLAY BALL·해설은 다른 스크린샷을 화면 너비 기준으로 환산한 추정값이라 직접 맞춰 주는 게 좋아요)
 */
object Regions {
    // 기본 박스
    val D_BATTER = floatArrayOf(0.75f, 0.862f, 0.17f, 0.0244f)     // PLAY BALL 단계의 타자 카드 이름
    val D_PB = floatArrayOf(0.463f, 0.906f, 0.42f, 0.0423f)        // PLAY BALL 버튼
    val D_SCORE = floatArrayOf(0.05f, 0.0432f, 0.1583f, 0.0662f)   // 노란 막대 ~ 팀 이름, 두 줄 전체
    val D_INNING = floatArrayOf(0.0185f, 0.0684f, 0.0259f, 0.0299f) // 이닝 숫자 + ▲▼
    val D_COMMENT = floatArrayOf(0.07f, 0.9718f, 0.89f, 0.0205f)   // 하단 해설 바
    val D_INTRO = floatArrayOf(0.25f, 0.8654f, 0.6296f, 0.0342f)   // 선수 소개바(번호·이름)

    // 계산된 영역 (서비스가 사용)
    @Volatile var BATTER: FloatArray = D_BATTER
    @Volatile var PLAYBALL: FloatArray = D_PB
    @Volatile var YEL_TOP: FloatArray = floatArrayOf()
    @Volatile var YEL_BOT: FloatArray = floatArrayOf()
    @Volatile var TEAM_TOP: FloatArray = floatArrayOf()
    @Volatile var TEAM_BOT: FloatArray = floatArrayOf()
    @Volatile var INNING_NUM: FloatArray = floatArrayOf()
    @Volatile var INNING_TRI: FloatArray = floatArrayOf()
    @Volatile var COMMENT: FloatArray = D_COMMENT
    @Volatile var INTRO: FloatArray = D_INTRO
    @Volatile var pbThreshold = 0.35f

    init { apply(D_BATTER, D_PB, D_SCORE, D_INNING, D_COMMENT, D_INTRO, null) }

    fun apply(
        batter: FloatArray, pb: FloatArray, score: FloatArray, inning: FloatArray,
        comment: FloatArray, intro: FloatArray, pbBase: Float?
    ) {
        BATTER = batter
        PLAYBALL = pb
        val hh = score[3] / 2f
        YEL_TOP = floatArrayOf(score[0], score[1], score[2] * 0.25f, hh)
        YEL_BOT = floatArrayOf(score[0], score[1] + hh, score[2] * 0.25f, hh)
        TEAM_TOP = floatArrayOf(score[0] + score[2] * 0.55f, score[1] + hh * 0.15f, score[2] * 0.45f, hh * 0.7f)
        TEAM_BOT = floatArrayOf(score[0] + score[2] * 0.55f, score[1] + hh + hh * 0.15f, score[2] * 0.45f, hh * 0.7f)
        INNING_NUM = floatArrayOf(inning[0], inning[1], inning[2], inning[3] * 0.6f)
        INNING_TRI = floatArrayOf(inning[0], inning[1] + inning[3] * 0.6f, inning[2], inning[3] * 0.4f)
        COMMENT = comment
        INTRO = intro
        pbThreshold = if (pbBase == null) 0.35f else (pbBase * 0.5f).coerceIn(0.08f, 0.35f)
    }

    private fun prefs(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    private fun arr(o: JSONObject, key: String, def: FloatArray): FloatArray {
        val a = o.optJSONArray(key) ?: return def
        if (a.length() != 4) return def
        return FloatArray(4) { a.getDouble(it).toFloat() }
    }

    private fun applyDefaults() = apply(D_BATTER, D_PB, D_SCORE, D_INNING, D_COMMENT, D_INTRO, null)

    /** 저장된 설정을 읽어 적용 (없으면 기본값) */
    fun load(c: Context) {
        val raw = prefs(c).getString("calib", null)
        if (raw == null) { applyDefaults(); return }
        try {
            val o = JSONObject(raw)
            val pbBase = if (o.has("pbBase")) o.getDouble("pbBase").toFloat() else null
            apply(
                arr(o, "batter", D_BATTER), arr(o, "pb", D_PB), arr(o, "score", D_SCORE),
                arr(o, "inning", D_INNING), arr(o, "comment", D_COMMENT), arr(o, "intro", D_INTRO), pbBase
            )
        } catch (e: Exception) {
            applyDefaults()
        }
    }

    /** 이전에 저장한 PLAY BALL 버튼 색 기준값 (없으면 null) */
    fun savedPbBase(c: Context): Float? {
        val raw = prefs(c).getString("calib", null) ?: return null
        return try { val o = JSONObject(raw); if (o.has("pbBase")) o.getDouble("pbBase").toFloat() else null } catch (e: Exception) { null }
    }

    /**
     * boxes: [타자, PLAY BALL, 스코어보드, 이닝, 해설, 소개바] (화면 대비 비율)
     * pbBase: 이번 화면에서 PLAY BALL 버튼이 보였을 때의 보라색 비율. 버튼이 안 보이는 화면이면 null → 이전 값 유지
     */
    fun save(c: Context, w: Int, h: Int, boxes: List<FloatArray>, pbBase: Float?) {
        fun ja(f: FloatArray) = JSONArray().apply { f.forEach { put(it.toDouble()) } }
        val base = pbBase ?: savedPbBase(c)
        val o = JSONObject().put("w", w).put("h", h)
            .put("batter", ja(boxes[0])).put("pb", ja(boxes[1])).put("score", ja(boxes[2]))
            .put("inning", ja(boxes[3])).put("comment", ja(boxes[4])).put("intro", ja(boxes[5]))
        if (base != null) o.put("pbBase", base.toDouble())
        prefs(c).edit().putString("calib", o.toString())
            .putInt("calibVer", prefs(c).getInt("calibVer", 0) + 1).apply()
        load(c)
    }

    fun clear(c: Context) {
        prefs(c).edit().remove("calib").putInt("calibVer", prefs(c).getInt("calibVer", 0) + 1).apply()
        load(c)
    }

    /** 저장된 화면 크기 (없으면 null) */
    fun savedSize(c: Context): Pair<Int, Int>? {
        val raw = prefs(c).getString("calib", null) ?: return null
        return try { val o = JSONObject(raw); Pair(o.getInt("w"), o.getInt("h")) } catch (e: Exception) { null }
    }

    /** 편집기 시작 박스: 저장된 것(화면 비율이 비슷할 때) 또는 기본값 */
    fun initialBoxes(c: Context, w: Int, h: Int): List<FloatArray> {
        val raw = prefs(c).getString("calib", null)
        if (raw != null) {
            try {
                val o = JSONObject(raw)
                val sw = o.getInt("w"); val sh = o.getInt("h")
                if (Math.abs(sw.toDouble() * h - w.toDouble() * sh) / (sw.toDouble() * h) < 0.03) {
                    return listOf(
                        arr(o, "batter", D_BATTER), arr(o, "pb", D_PB), arr(o, "score", D_SCORE),
                        arr(o, "inning", D_INNING), arr(o, "comment", D_COMMENT), arr(o, "intro", D_INTRO)
                    )
                }
            } catch (e: Exception) {}
        }
        return listOf(D_BATTER, D_PB, D_SCORE, D_INNING, D_COMMENT, D_INTRO)
    }
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
