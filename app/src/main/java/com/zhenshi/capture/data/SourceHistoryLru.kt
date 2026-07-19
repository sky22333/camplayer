package com.zhenshi.capture.data

/** 最近源列表：MRU 在前、去重、定长。 */
object SourceHistoryLru {
    const val MAX_ENTRIES = 20

    fun add(existing: List<String>, value: String, max: Int = MAX_ENTRIES): List<String> {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return existing
        return (listOf(trimmed) + existing.filter { it != trimmed }).take(max)
    }

    fun encode(list: List<String>): String = list.joinToString("\n")

    fun decode(raw: String?): List<String> =
        raw?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList().orEmpty()

    fun networkOnly(items: List<String>): List<String> =
        items.filter { !it.startsWith("usb:") }
}
