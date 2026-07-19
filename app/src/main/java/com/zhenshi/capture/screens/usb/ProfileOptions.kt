package com.zhenshi.capture.screens.usb

import com.zhenshi.capture.media.VideoProfile

data class ResolutionOption(
    val width: Int,
    val height: Int,
) {
    val key: String get() = "${width}x${height}"
}

fun List<VideoProfile>.resolutionOptions(): List<ResolutionOption> =
    map { ResolutionOption(it.width, it.height) }
        .distinctBy { it.key }
        .sortedByDescending { it.width * it.height }

fun List<VideoProfile>.fpsOptionsFor(width: Int, height: Int): List<Int> =
    if (any { it.width == width && it.height == height }) {
        VideoProfile.STANDARD_FPS_OPTIONS
    } else {
        emptyList()
    }

fun List<VideoProfile>.findProfile(width: Int, height: Int, fps: Int): VideoProfile? =
    firstOrNull { it.width == width && it.height == height && it.fps == fps }
        ?: run {
            val normalizedFps = fps.takeIf { it in VideoProfile.STANDARD_FPS_OPTIONS }
                ?: VideoProfile.DEFAULT_FPS
            if (any { it.width == width && it.height == height }) {
                VideoProfile(width, height, normalizedFps)
            } else {
                null
            }
        }
