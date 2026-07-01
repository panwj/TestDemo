package com.clean.videocompress.api.model

sealed class VideoCompressError {
    data object PermissionDenied : VideoCompressError()
    data object SourceNotFound : VideoCompressError()
    data object UnsupportedFormat : VideoCompressError()
    data class EngineFailed(val message: String?, val cause: Throwable?) : VideoCompressError()
    data class SaveFailed(val message: String?, val cause: Throwable?) : VideoCompressError()
    data object Cancelled : VideoCompressError()
}
