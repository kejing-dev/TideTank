package io.embodit.tidetank.domain

enum class Language { SYSTEM, ZH, EN }

/** App settings (design dataModel.settings). */
data class Settings(
    val language: Language = Language.SYSTEM,
    /** Event sounds on (the tank page [静音] and settings ② switch the same flag, D15). */
    val sound: Boolean = true,
    /** 0 low, 1 mid (default), 2 high (design volume 0.35 / 0.7 / 1.0). */
    val volume: Int = 1,
    /** Pump / bubble bed, off by default (B24). */
    val pump: Boolean = false,
    val reducedMotion: Boolean = false,
    /** The first-run how-to card (S01) has been closed. */
    val firstRunDone: Boolean = false,
) {
    val muted: Boolean get() = !sound

    companion object {
        val VOLUME = floatArrayOf(0.35f, 0.70f, 1.0f)
        val VOLUME_PCT = intArrayOf(35, 70, 100)
    }
}
