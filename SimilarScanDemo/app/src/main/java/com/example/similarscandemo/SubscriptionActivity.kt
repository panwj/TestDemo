package com.example.similarscandemo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import com.example.similarscandemo.onboarding.OnboardingState

/**
 * 可区分的订阅展示页。
 *
 * Demo 不发起支付，主按钮和关闭按钮都完成首次启动流程并进入扫描首页。
 */
class SubscriptionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subscription)
        findViewById<TextView>(R.id.closeSubscription).setOnClickListener { complete() }
        findViewById<Button>(R.id.startTrialButton).setOnClickListener { complete() }
    }

    private fun complete() {
        OnboardingState.markCompleted(this)
        // 首次流程结束后立即进入权限申请，授权成功后自动开始扫描。
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_REQUEST_PERMISSION, true)
        )
        finish()
    }
}
