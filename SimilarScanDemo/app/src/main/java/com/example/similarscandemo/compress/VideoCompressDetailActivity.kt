package com.example.similarscandemo.compress

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.clean.videocompress.api.VideoCompressObserver
import com.clean.videocompress.api.VideoCompressSdk
import com.clean.videocompress.api.VideoCompressTask
import com.clean.videocompress.api.model.CompressVideoAsset
import com.clean.videocompress.api.model.VideoCompressError
import com.clean.videocompress.api.model.VideoCompressOption
import com.clean.videocompress.api.model.VideoCompressProgress
import com.clean.videocompress.api.model.VideoCompressRequest
import com.clean.videocompress.api.model.VideoCompressResult
import com.example.similarscandemo.R
import com.example.similarscandemo.SubscriptionActivity
import com.example.similarscandemo.VideoPlayerActivity
import com.example.similarscandemo.util.FormatUtils

class VideoCompressDetailActivity : Activity() {
    private lateinit var asset: CompressVideoAsset
    private lateinit var quotaStore: VideoCompressionQuotaStore
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var compressButton: Button
    private lateinit var options: List<VideoCompressOption>
    private var task: VideoCompressTask? = null
    private var compressedOutputUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_compress_detail)
        val parsedAsset = VideoAssetIntent.get(intent)
        if (parsedAsset == null || parsedAsset.id < 0L) {
            finish()
            return
        }
        asset = parsedAsset
        quotaStore = VideoCompressionQuotaStore(this)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        compressButton = findViewById(R.id.compressButton)
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
        findViewById<TextView>(R.id.titleText).text = asset.displayName
        findViewById<TextView>(R.id.summaryText).text =
            "${FormatUtils.formatBytes(asset.sizeBytes)} · ${FormatUtils.formatDuration(asset.durationMs)}"
        VideoThumbLoader.load(findViewById<ImageView>(R.id.videoThumb), asset, 720)
        findViewById<FrameLayout>(R.id.videoPreviewContainer).setOnClickListener { playVideo() }
        findViewById<TextView>(R.id.playButton).setOnClickListener { playVideo() }
        options = VideoCompressOption.defaults()
        renderOptions()
        compressButton.setOnClickListener { confirmAndStart() }
    }

    override fun onDestroy() {
        task?.cancel()
        super.onDestroy()
    }

    private fun renderOptions() {
        val optionGroup = findViewById<RadioGroup>(R.id.optionGroup)
        optionGroup.removeAllViews()
        options.forEachIndexed { index, option ->
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = "${option.title} · ${option.description} · ${option.compressionRatePercent}% smaller"
                textSize = 15f
                setTextColor(0xFFE2E8F0.toInt())
                setPadding(0, 10, 0, 10)
                tag = option
                isChecked = index == 1
            }
            optionGroup.addView(button)
        }
    }

    private fun selectedOption(): VideoCompressOption {
        val optionGroup = findViewById<RadioGroup>(R.id.optionGroup)
        val checked = optionGroup.findViewById<RadioButton>(optionGroup.checkedRadioButtonId)
        return checked?.tag as? VideoCompressOption ?: options[1]
    }

    private fun playVideo() {
        startActivity(
            Intent(this, VideoPlayerActivity::class.java)
                .putExtra(VideoPlayerActivity.EXTRA_VIDEO_URI, asset.uri.toString())
        )
    }

    private fun confirmAndStart() {
        val remaining = quotaStore.remainingFreeCount()
        if (remaining <= 0) {
            AlertDialog.Builder(this)
                .setTitle("Daily compression limit reached")
                .setMessage("Unlock PRO to remove daily limits.")
                .setPositiveButton("View PRO") { _, _ ->
                    startActivity(Intent(this, SubscriptionActivity::class.java))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val option = selectedOption()
        AlertDialog.Builder(this)
            .setTitle("Compress this video?")
            .setMessage("Remaining daily limit $remaining/2. This will use ${option.title}.")
            .setPositiveButton("Compress") { _, _ -> startCompress(option) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startCompress(option: VideoCompressOption) {
        compressButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        quotaStore.consumeOneFreeQuota()
        val client = VideoCompressSdk.create(applicationContext)
        task = client.compress(
            VideoCompressRequest(asset, option),
            object : VideoCompressObserver {
                override fun onStart(asset: CompressVideoAsset) {
                    statusText.text = "Preparing compression..."
                }

                override fun onProgress(progress: VideoCompressProgress) {
                    progressBar.progress = progress.percent
                    statusText.text = "${progress.stage.name.lowercase().replace('_', ' ')} · ${progress.percent}%"
                }

                override fun onSuccess(result: VideoCompressResult) {
                    compressButton.isEnabled = true
                    compressedOutputUri = result.outputUri
                    showCompleteDialog(result)
                }

                override fun onFailure(error: VideoCompressError) {
                    compressButton.isEnabled = true
                    statusText.text = "Compression failed: $error"
                }

                override fun onCancelled(assetId: Long) {
                    compressButton.isEnabled = true
                    statusText.text = "Compression cancelled"
                }
            }
        )
    }

    private fun showCompleteDialog(result: VideoCompressResult) {
        statusText.text =
            "Saved to Movies · ${FormatUtils.formatBytes(result.outputSizeBytes)}"
        AlertDialog.Builder(this)
            .setTitle("Compression complete")
            .setMessage(
                "Saved ${FormatUtils.formatBytes(result.savedBytes)}. " +
                    "The compressed video has been saved to system Movies/Gallery. Keep or delete the original video?"
            )
            .setPositiveButton("Keep Original") { _, _ -> finish() }
            .setNegativeButton("Delete Original") { _, _ ->
                deleteOriginal()
                finish()
            }
            .setNeutralButton("Play Result") { _, _ ->
                playCompressedVideo()
            }
            .show()
    }

    private fun playCompressedVideo() {
        val uri = compressedOutputUri ?: return
        startActivity(
            Intent(this, VideoPlayerActivity::class.java)
                .putExtra(VideoPlayerActivity.EXTRA_VIDEO_URI, uri.toString())
        )
    }

    private fun deleteOriginal() {
        try {
            contentResolver.delete(asset.uri, null, null)
        } catch (_: Throwable) {
            // Demo 层先保留基础删除流程；系统确认删除授权可在后续业务细化中接入。
        }
    }
}
