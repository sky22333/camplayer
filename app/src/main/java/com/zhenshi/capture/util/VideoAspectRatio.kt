package com.zhenshi.capture.util

fun videoAspectRatio(width: Int, height: Int): Float =
    if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 16f / 9f
