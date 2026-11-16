package com.melih.altyazi

/** Bir altyazı satırı. Önce hızlı çeviri gelir, yapay zekâ çevirisi gelince güncellenir. */
class SubtitleEntry(
    val id: Int,
    val time: String,
    var who: String,
    var en: String,
    var tr: String,
    var notes: List<String>,
    var ai: Boolean = false,
    val engine: String = ""
)
