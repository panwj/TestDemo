package com.example.similarscandemo.onboarding

import android.content.Context

/**
 * 保存首次启动流程状态。Demo 不接真实订阅，仅记录用户已完成引导。
 */
object OnboardingState {
    private const val PREFS = "onboarding"
    private const val KEY_COMPLETED = "completed"

    fun isCompleted(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_COMPLETED, false)
    }

    fun markCompleted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_COMPLETED, true)
            .apply()
    }

}
