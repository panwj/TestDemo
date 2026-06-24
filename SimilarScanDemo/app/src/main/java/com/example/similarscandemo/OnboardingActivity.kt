package com.example.similarscandemo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.ViewFlipper
import com.example.similarscandemo.onboarding.OnboardingState

/**
 * 三页首次启动引导。
 *
 * 页面分别对应隐私授权、重复照片整理和释放存储空间。
 */
class OnboardingActivity : Activity() {
    private lateinit var flipper: ViewFlipper
    private lateinit var continueButton: Button
    private lateinit var pageIndicator: TextView
    private var page = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (OnboardingState.isCompleted(this)) {
            openMain()
            return
        }

        setContentView(R.layout.activity_onboarding)
        flipper = findViewById(R.id.onboardingFlipper)
        continueButton = findViewById(R.id.onboardingContinue)
        pageIndicator = findViewById(R.id.pageIndicator)
        findViewById<TextView>(R.id.onboardingSkip).setOnClickListener { openSubscription() }
        continueButton.setOnClickListener {
            if (page < LAST_PAGE) {
                page++
                flipper.showNext()
                updateControls()
            } else {
                openSubscription()
            }
        }
        updateControls()
    }

    private fun updateControls() {
        pageIndicator.text = when (page) {
            0 -> "●  ○  ○"
            1 -> "○  ●  ○"
            else -> "○  ○  ●"
        }
        continueButton.text = if (page == LAST_PAGE) "Continue" else "Next"
    }

    private fun openSubscription() {
        startActivity(Intent(this, SubscriptionActivity::class.java))
        finish()
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        private const val LAST_PAGE = 2
    }
}
