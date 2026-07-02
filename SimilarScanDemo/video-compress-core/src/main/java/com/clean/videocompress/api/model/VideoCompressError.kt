package com.clean.videocompress.api.model

/**
 * 视频压缩失败类型。
 *
 * code 是稳定错误码，业务层可以用它做 UI 文案、埋点和测试统计；
 * 每个子类型携带更细的上下文，方便定位具体原因。
 */
sealed class VideoCompressError(val code: VideoCompressErrorCode) {
    data class PermissionDenied(
        val operation: VideoCompressPermissionOperation
    ) : VideoCompressError(VideoCompressErrorCode.PERMISSION_DENIED)

    data object SourceNotFound : VideoCompressError(VideoCompressErrorCode.SOURCE_NOT_FOUND)

    data class UnsupportedFormat(
        val reason: String
    ) : VideoCompressError(VideoCompressErrorCode.UNSUPPORTED_FORMAT)

    data class NotWorthCompressing(
        val reason: String
    ) : VideoCompressError(VideoCompressErrorCode.NOT_WORTH_COMPRESSING)

    data class InsufficientStorage(
        val location: VideoCompressStorageLocation,
        val requiredBytes: Long,
        val availableBytes: Long
    ) : VideoCompressError(VideoCompressErrorCode.INSUFFICIENT_STORAGE)

    data class EngineFailed(
        val message: String?,
        val cause: Throwable?
    ) : VideoCompressError(VideoCompressErrorCode.ENGINE_FAILED)

    data class SaveFailed(
        val message: String?,
        val cause: Throwable?
    ) : VideoCompressError(VideoCompressErrorCode.SAVE_FAILED)

    data class ValidationFailed(
        val reason: String
    ) : VideoCompressError(VideoCompressErrorCode.VALIDATION_FAILED)

    data object Cancelled : VideoCompressError(VideoCompressErrorCode.CANCELLED)

    data object SdkClosed : VideoCompressError(VideoCompressErrorCode.SDK_CLOSED)
}

/**
 * 稳定错误码。新增失败场景时优先新增枚举，避免业务层解析字符串。
 */
enum class VideoCompressErrorCode {
    PERMISSION_DENIED,
    SOURCE_NOT_FOUND,
    UNSUPPORTED_FORMAT,
    NOT_WORTH_COMPRESSING,
    INSUFFICIENT_STORAGE,
    ENGINE_FAILED,
    SAVE_FAILED,
    VALIDATION_FAILED,
    CANCELLED,
    SDK_CLOSED
}

/**
 * 权限失败发生在哪个操作上。
 */
enum class VideoCompressPermissionOperation {
    READ_VIDEO,
    SAVE_VIDEO
}

/**
 * 磁盘空间不足发生在哪个存储位置上。
 */
enum class VideoCompressStorageLocation {
    TEMP_CACHE,
    MEDIA_LIBRARY
}
