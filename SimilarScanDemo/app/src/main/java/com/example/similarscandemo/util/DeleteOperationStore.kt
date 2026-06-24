package com.example.similarscandemo.util

import android.content.Context
import java.util.UUID

/**
 * 记录系统删除确认属于哪个 App 进程。
 *
 * 同一进程内即使从扫描通知重新打开主页，也不能把仍在等待系统确认的资源恢复。
 * 进程重启后 sessionId 会变化，此时主页可判定数据库中的 DELETE_PENDING 是遗留操作。
 */
object DeleteOperationStore {
    private const val PREFS = "delete_operation"
    private const val KEY_OWNER_SESSION = "owner_session"
    private const val KEY_STARTED_AT = "started_at"
    private const val STALE_IN_SAME_PROCESS_MS = 10L * 60L * 1000L

    private val currentSessionId: String = UUID.randomUUID().toString()

    fun begin(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_OWNER_SESSION, currentSessionId)
            .putLong(KEY_STARTED_AT, System.currentTimeMillis())
            .apply()
    }

    fun finish(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    fun shouldRecover(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val owner = preferences.getString(KEY_OWNER_SESSION, "").orEmpty()
        if (owner.isBlank()) return true
        if (owner != currentSessionId) return true
        return now - preferences.getLong(KEY_STARTED_AT, now) >= STALE_IN_SAME_PROCESS_MS
    }
}
