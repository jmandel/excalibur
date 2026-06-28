package dev.exe.kindleconverter.data

/** Kindle device profiles calibre supports, with human names for the picker. */
data class KindleProfile(val id: String, val name: String)

// "Paperwhite" alone is ambiguous — it spans many generations and two screen
// sizes — so each option names recognizable models plus year and screen, even
// where several map to the same underlying calibre output profile.
val KINDLE_PROFILES = listOf(
    KindleProfile("kindle_oasis", "Paperwhite 2024 / 12th gen · 7″"),
    KindleProfile("kindle_pw3", "Paperwhite 2015–2021 · 6″"),
    KindleProfile("kindle_scribe", "Scribe · 10.2″"),
    KindleProfile("kindle_voyage", "Voyage 2014 · 6″"),
    KindleProfile("kindle_pw", "Paperwhite 2012–2013 · 6″"),
    KindleProfile("kindle", "Basic Kindle · 6″"),
    KindleProfile("kindle_dx", "Kindle DX · 9.7″"),
)

fun profileName(id: String) = KINDLE_PROFILES.firstOrNull { it.id == id }?.name ?: id
