package fr.spiritbox.compta.services

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle

/**
 * Direction artistique « Partition », version claire, appliquée aux PDF.
 *
 * Les PDF restent toujours en clair. openhtmltopdf ne gère pas les variables CSS :
 * ces valeurs recopient le thème clair de compta-zik-frontend/docs/design/tokens.css
 * et doivent évoluer avec lui.
 */
object PdfTheme {
    const val PAPER = "#F4EFE6"
    const val CARD = "#FFFCF6"
    const val CARD_ALT = "#F8F4EC"
    const val LINE = "#DCD3C2"
    const val LINE_STRONG = "#13262C"
    const val INK = "#13262C"
    const val INK_SOFT = "#3A4B50"
    const val INK_MUTED = "#56676C"
    const val INK_FAINT = "#5A6A6E"
    const val PETROL = "#0B5563"
    const val BRASS = "#C08A1E"
    const val BRASS_TINT = "#F5E7C8"
    const val BRASS_TINT_INK = "#6B4806"
    const val WORDMARK_ACCENT = "#A8760F"

    const val FONT_DISPLAY = "Instrument Serif"
    const val FONT_UI = "Hanken Grotesk"
    const val FONT_MONO = "IBM Plex Mono"

    /** Polices embarquées (TTF statiques, licence SIL OFL, voir pdf-assets/fonts). */
    val fonts = listOf(
        PdfFont(FONT_DISPLAY, "InstrumentSerif-Regular.ttf", 400, FontStyle.NORMAL),
        PdfFont(FONT_DISPLAY, "InstrumentSerif-Italic.ttf", 400, FontStyle.ITALIC),
        PdfFont(FONT_UI, "HankenGrotesk-Regular.ttf", 400, FontStyle.NORMAL),
        PdfFont(FONT_UI, "HankenGrotesk-SemiBold.ttf", 600, FontStyle.NORMAL),
        PdfFont(FONT_UI, "HankenGrotesk-Bold.ttf", 700, FontStyle.NORMAL),
        PdfFont(FONT_MONO, "IBMPlexMono-Regular.ttf", 400, FontStyle.NORMAL),
        PdfFont(FONT_MONO, "IBMPlexMono-Medium.ttf", 500, FontStyle.NORMAL),
    )
}

data class PdfFont(val family: String, val file: String, val weight: Int, val style: FontStyle) {
    val resource get() = "/pdf-assets/fonts/$file"
}
