package com.example.similarscandemo.compress

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import com.clean.videocompress.permission.VideoAccessLevel
import com.clean.videocompress.permission.VideoCompressPermissionChecker
import com.example.similarscandemo.MainActivity
import com.example.similarscandemo.R
import com.example.similarscandemo.contacts.ContactsActivity
import com.example.similarscandemo.util.FormatUtils
import java.util.concurrent.Executors

class VideoCompressActivity : Activity() {
    private lateinit var summaryText: TextView
    private lateinit var permissionButton: Button
    private lateinit var bucketList: ListView
    private var adapter: VideoBucketAdapter? = null
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_compress)
        summaryText = findViewById(R.id.summaryText)
        permissionButton = findViewById(R.id.permissionButton)
        bucketList = findViewById(R.id.bucketList)
        findViewById<Button>(R.id.photoTabButton).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<Button>(R.id.contactsTabButton).setOnClickListener {
            startActivity(Intent(this, ContactsActivity::class.java))
        }
        permissionButton.setOnClickListener {
            VideoPermissionHelper.request(this)
        }
        bucketList.setOnItemClickListener { _, _, position, _ ->
            val bucket = adapter?.getItem(position) ?: return@setOnItemClickListener
            startActivity(
                Intent(this, VideoBucketActivity::class.java)
                    .putExtra(VideoBucketActivity.EXTRA_BUCKET_KEY, bucket.key)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == VideoPermissionHelper.REQUEST_CODE) {
            render()
        }
    }

    private fun render() {
        val accessLevel = VideoCompressPermissionChecker.accessLevel(this)
        if (accessLevel == VideoAccessLevel.NONE) {
            permissionButton.visibility = View.VISIBLE
            summaryText.text = "Allow video access to find compression opportunities"
            adapter?.submitList(emptyList())
            return
        }
        permissionButton.visibility = View.GONE
        summaryText.text = "Loading videos..."
        executor.execute {
            val buckets = VideoCompressRepository.loadBuckets(applicationContext, forceRefresh = true)
            val videos = buckets.flatMap { it.videos }
            val totalBytes = videos.sumOf { it.sizeBytes }
            val savingBytes = buckets.sumOf { it.estimatedSavingBytes }
            runOnUiThread {
                summaryText.text =
                    "${videos.size} Videos · ${FormatUtils.formatBytes(totalBytes)} · Save up to ${FormatUtils.formatBytes(savingBytes)}"
                val current = adapter
                if (current == null) {
                    adapter = VideoBucketAdapter(this, buckets)
                    bucketList.adapter = adapter
                } else {
                    current.submitList(buckets)
                }
            }
        }
    }
}
