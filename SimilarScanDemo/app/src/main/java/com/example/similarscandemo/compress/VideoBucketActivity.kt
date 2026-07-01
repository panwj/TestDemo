package com.example.similarscandemo.compress

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.GridView
import android.widget.TextView
import com.clean.videocompress.api.model.CompressVideoAsset
import com.example.similarscandemo.R
import com.example.similarscandemo.util.FormatUtils
import java.util.concurrent.Executors

class VideoBucketActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var grid: GridView
    private var adapter: CompressVideoAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_bucket)
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
        grid = findViewById(R.id.videoGrid)
        grid.setOnItemClickListener { _, _, position, _ ->
            val asset = adapter?.getItem(position) ?: return@setOnItemClickListener
            startActivity(VideoAssetIntent.put(Intent(this, VideoCompressDetailActivity::class.java), asset))
        }
        loadBucket()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun loadBucket() {
        val bucketKey = intent.getStringExtra(EXTRA_BUCKET_KEY).orEmpty()
        executor.execute {
            val bucket = VideoCompressRepository.loadBucket(applicationContext, bucketKey)
            val videos = bucket?.videos.orEmpty()
            runOnUiThread {
                findViewById<TextView>(R.id.titleText).text = bucket?.title ?: "Compress"
                findViewById<TextView>(R.id.summaryText).text =
                    "${videos.size} Videos · ${FormatUtils.formatBytes(videos.sumOf(CompressVideoAsset::sizeBytes))}"
                adapter = CompressVideoAdapter(this, videos)
                grid.adapter = adapter
            }
        }
    }

    companion object {
        const val EXTRA_BUCKET_KEY = "bucket_key"
    }
}
