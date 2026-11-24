package com.stasao.gcam

/** Exact release asset names, including GitHub's normalization of older bracketed names. */
internal object GCamReleaseAssetNames {
    fun find(tag: String, availableNames: Collection<String>): String? {
        if (!Regex("v[0-9]+(?:\\.[0-9]+){1,3}").matches(tag)) return null
        return listOf("gCam-$tag.apk", "gCam_[$tag].apk", "gCam_.$tag.apk")
            .firstOrNull { it in availableNames }
    }
}
