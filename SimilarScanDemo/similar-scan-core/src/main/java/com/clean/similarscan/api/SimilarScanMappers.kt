package com.clean.similarscan.api

import com.clean.similarscan.api.model.GroupCategory
import com.clean.similarscan.api.model.MediaAsset
import com.clean.similarscan.api.model.MediaKind
import com.clean.similarscan.api.model.ProductCategory
import com.clean.similarscan.api.model.ProductCategoryType
import com.clean.similarscan.api.model.ScanProgress
import com.clean.similarscan.api.model.ScanResult
import com.clean.similarscan.api.model.ScanStage
import com.clean.similarscan.api.model.SimilarGroup
import com.clean.similarscan.internal.model.GroupCategory as InternalGroupCategory
import com.clean.similarscan.internal.model.MediaAsset as InternalMediaAsset
import com.clean.similarscan.internal.model.MediaKind as InternalMediaKind
import com.clean.similarscan.internal.model.ProductCategory as InternalProductCategory
import com.clean.similarscan.internal.model.ProductCategoryType as InternalProductCategoryType
import com.clean.similarscan.internal.model.ScanProgress as InternalScanProgress
import com.clean.similarscan.internal.model.ScanResult as InternalScanResult
import com.clean.similarscan.internal.model.ScanStage as InternalScanStage
import com.clean.similarscan.internal.model.SimilarGroup as InternalSimilarGroup

internal fun InternalMediaKind.toApi(): MediaKind = MediaKind.valueOf(name)
internal fun MediaKind.toInternal(): InternalMediaKind = InternalMediaKind.valueOf(name)

internal fun InternalGroupCategory.toApi(): GroupCategory = GroupCategory.valueOf(name)
internal fun InternalProductCategoryType.toApi(): ProductCategoryType = ProductCategoryType.valueOf(name)
internal fun InternalScanStage.toApi(): ScanStage = ScanStage.valueOf(name)

internal fun InternalMediaAsset.toApi(): MediaAsset {
    return MediaAsset(
        id = id,
        uri = uri,
        kind = kind.toApi(),
        name = name,
        width = width,
        height = height,
        duration = duration,
        size = size,
        createdAt = createdAt,
        updatedAt = updatedAt,
        dateAdded = dateAdded,
        bucket = bucket,
        pathHint = pathHint,
        mimeType = mimeType,
        isFavorite = isFavorite,
        isEdited = isEdited,
        generationAdded = generationAdded,
        generationModified = generationModified,
        chatSource = chatSource,
        contentSha256 = contentSha256,
        qualityScore = qualityScore
    )
}

internal fun MediaAsset.toInternal(): InternalMediaAsset {
    return InternalMediaAsset(
        id = id,
        uri = uri,
        kind = kind.toInternal(),
        name = name,
        width = width,
        height = height,
        duration = duration,
        size = size,
        createdAt = createdAt,
        updatedAt = updatedAt,
        dateAdded = dateAdded,
        bucket = bucket,
        pathHint = pathHint,
        mimeType = mimeType,
        isFavorite = isFavorite,
        isEdited = isEdited,
        generationAdded = generationAdded,
        generationModified = generationModified,
        chatSource = chatSource,
        contentSha256 = contentSha256,
        qualityScore = qualityScore
    )
}

internal fun InternalSimilarGroup.toApi(): SimilarGroup {
    return SimilarGroup(
        id = id,
        title = title,
        subtitle = subtitle,
        category = category.toApi(),
        kind = kind.toApi(),
        assets = assets.map { it.toApi() },
        totalAssetCount = totalAssetCount,
        totalSizeBytes = totalSizeBytes,
        latestAssetTimeMillis = latestAssetTimeMillis
    )
}

internal fun InternalProductCategory.toApi(): ProductCategory {
    return ProductCategory(
        type = type.toApi(),
        groups = groups.map { it.toApi() }
    )
}

internal fun InternalScanProgress.toApi(): ScanProgress {
    return ScanProgress(
        stage = stage.toApi(),
        processedCount = processedCount,
        discoveredGroupCount = discoveredGroupCount,
        message = message,
        elapsedTimeMs = elapsedTimeMs,
        elapsedTimeText = elapsedTimeText
    )
}

internal fun InternalScanResult.toApi(): ScanResult {
    return ScanResult(
        assetCount = assetCount,
        groups = groups.map { it.toApi() },
        message = message,
        elapsedTimeMs = elapsedTimeMs,
        elapsedTimeText = elapsedTimeText
    )
}
