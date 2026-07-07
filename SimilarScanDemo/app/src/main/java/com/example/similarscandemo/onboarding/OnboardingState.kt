package com.example.similarscandemo.onboarding

import android.content.Context
import com.example.similarscandemo.util.MmkvStore

/**
 * 保存首次启动流程状态。Demo 不接真实订阅，仅记录用户已完成引导。
 */
object OnboardingState {
    private const val PREFS = "onboarding"
    private const val KEY_COMPLETED = "completed"

    fun isCompleted(context: Context): Boolean {
        return MmkvStore.store(context, PREFS).decodeBool(KEY_COMPLETED, false)
    }

    fun markCompleted(context: Context) {
        MmkvStore.store(context, PREFS).encode(KEY_COMPLETED, true)
    }

}
