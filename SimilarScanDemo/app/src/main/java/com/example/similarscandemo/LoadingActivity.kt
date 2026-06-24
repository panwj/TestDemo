package com.example.similarscandemo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.example.similarscandemo.onboarding.OnboardingState

/**
 * 冷启动 Loading 页面。
 *
 * 与竞品流程保持一致：冷启动固定展示 3 秒动画区域，再根据首次引导状态进入
 * Onboarding 或主页。页面切换后立即 finish，热启动和页面返回不会重复展示。
 */
class LoadingActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val openNextPage = Runnable {
        val target = if (OnboardingState.isCompleted(this)) {
            MainActivity::class.java
        } else {
            OnboardingActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_loading)
        handler.postDelayed(openNextPage, LOADING_DURATION_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(openNextPage)
        super.onDestroy()
    }

    companion object {
        private const val LOADING_DURATION_MS = 3_000L
    }
}
