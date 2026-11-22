package filmlab.app

import android.content.Context
import android.content.SharedPreferences
import filmlab.core.film.DevelopParams

/** 設定と自動現像の状態（SharedPreferences） */
object Prefs {
    private const val NAME = "filmlab"
    private const val P = "p_"

    private fun sp(ctx: Context): SharedPreferences = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun params(ctx: Context): DevelopParams {
        val all = sp(ctx).all
        val m = HashMap<String, String?>()
        for ((k, v) in all) if (k.startsWith(P)) m[k.removePrefix(P)] = v?.toString()
        return DevelopParams.fromMap(m)
    }

    fun saveParams(ctx: Context, p: DevelopParams) {
        val e = sp(ctx).edit()
        for ((k, v) in p.toMap()) e.putString(P + k, v)
        e.apply()
    }

    fun autoEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("auto", false)

    fun setAutoEnabled(ctx: Context, on: Boolean) {
        val e = sp(ctx).edit().putBoolean("auto", on)
        if (on) e.putLong("autoSince", System.currentTimeMillis() / 1000 - 5)
        e.apply()
    }

    /** 自動現像の対象にする DNG の追加時刻（秒）の下限 */
    fun autoSince(ctx: Context): Long = sp(ctx).getLong("autoSince", Long.MAX_VALUE)

    fun processed(ctx: Context): Set<String> = sp(ctx).getStringSet("processed", emptySet()) ?: emptySet()

    @Synchronized
    fun markProcessed(ctx: Context, id: Long) {
        val s = HashSet(processed(ctx))
        s.add(id.toString())
        // 古いものを溜め込みすぎない
        val trimmed = if (s.size > 3000) s.sortedBy { it.toLongOrNull() ?: 0L }.takeLast(2000).toHashSet() else s
        sp(ctx).edit().putStringSet("processed", trimmed).apply()
    }

    fun setLastMessage(ctx: Context, msg: String) {
        sp(ctx).edit().putString("lastMessage", msg).apply()
    }

    fun lastMessage(ctx: Context): String = sp(ctx).getString("lastMessage", "") ?: ""
}
