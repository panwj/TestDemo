package com.example.similarscandemo.util

import android.content.Context
import com.tencent.mmkv.MMKV

/**
 * Demo 业务层轻量级 KV 存储入口。
 *
 * MMKV 初始化是幂等的。这里集中处理初始化，避免各个业务 Store 分散调用
 * MMKV.initialize()，也方便后续统一调整存储目录或加密策略。
 */
object MmkvStore {
    @Volatile
    private var initialized = false

    fun store(context: Context, id: String): MMKV {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    MMKV.initialize(context.applicationContext)
                    initialized = true
                }
            }
        }
        return MMKV.mmkvWithID(id)
    }
}
