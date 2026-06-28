package dev.exe.kindleconverter.data

/** Kindle device profiles calibre supports, with human names for the picker. */
data class KindleProfile(val id: String, val name: String)

val KINDLE_PROFILES = listOf(
    KindleProfile("kindle_pw", "Kindle Paperwhite"),
    KindleProfile("kindle_pw3", "Kindle Paperwhite (HD)"),
    KindleProfile("kindle_oasis", "Kindle Oasis"),
    KindleProfile("kindle_scribe", "Kindle Scribe"),
    KindleProfile("kindle_voyage", "Kindle Voyage"),
    KindleProfile("kindle", "Kindle (basic)"),
    KindleProfile("kindle_dx", "Kindle DX"),
    KindleProfile("kindle_fire", "Kindle Fire"),
)

fun profileName(id: String) = KINDLE_PROFILES.firstOrNull { it.id == id }?.name ?: id
