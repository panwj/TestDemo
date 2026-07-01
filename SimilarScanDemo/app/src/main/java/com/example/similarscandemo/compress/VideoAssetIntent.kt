package com.example.similarscandemo.compress

import android.content.Intent
import android.net.Uri
import com.clean.videocompress.api.model.CompressVideoAsset

object VideoAssetIntent {
    private const val EXTRA_ID = "video_id"
    private const val EXTRA_URI = "video_uri"
    private const val EXTRA_NAME = "video_name"
    private const val EXTRA_SIZE = "video_size"
    private const val EXTRA_DURATION = "video_duration"
    private const val EXTRA_WIDTH = "video_width"
    private const val EXTRA_HEIGHT = "video_height"
    private const val EXTRA_DATE_ADDED = "video_date_added"
    private const val EXTRA_DATE_MODIFIED = "video_date_modified"
    private const val EXTRA_BITRATE = "video_bitrate"

    fun put(intent: Intent, asset: CompressVideoAsset): Intent {
        return intent
            .putExtra(EXTRA_ID, asset.id)
            .putExtra(EXTRA_URI, asset.uri.toString())
            .putExtra(EXTRA_NAME, asset.displayName)
            .putExtra(EXTRA_SIZE, asset.sizeBytes)
            .putExtra(EXTRA_DURATION, asset.durationMs)
            .putExtra(EXTRA_WIDTH, asset.width)
            .putExtra(EXTRA_HEIGHT, asset.height)
            .putExtra(EXTRA_DATE_ADDED, asset.dateAddedSeconds)
            .putExtra(EXTRA_DATE_MODIFIED, asset.dateModifiedSeconds)
            .putExtra(EXTRA_BITRATE, asset.bitrate)
    }

    fun get(intent: Intent): CompressVideoAsset? {
        val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse) ?: return null
        return CompressVideoAsset(
            id = intent.getLongExtra(EXTRA_ID, -1L),
            uri = uri,
            displayName = intent.getStringExtra(EXTRA_NAME).orEmpty(),
            sizeBytes = intent.getLongExtra(EXTRA_SIZE, 0L),
            durationMs = intent.getLongExtra(EXTRA_DURATION, 0L),
            width = intent.getIntExtra(EXTRA_WIDTH, 0),
            height = intent.getIntExtra(EXTRA_HEIGHT, 0),
            dateAddedSeconds = intent.getLongExtra(EXTRA_DATE_ADDED, 0L),
            dateModifiedSeconds = intent.getLongExtra(EXTRA_DATE_MODIFIED, 0L),
            bitrate = intent.getIntExtra(EXTRA_BITRATE, 0)
        )
    }
}
