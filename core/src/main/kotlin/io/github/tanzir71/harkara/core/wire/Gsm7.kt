package io.github.tanzir71.harkara.core.wire

/**
 * GSM 03.38 7-bit default alphabet and SMS part counting. One character outside this alphabet (one Bangla
 * letter, an em dash, a curly quote) switches the whole SMS to UCS-2, which fits only 70 characters.
 */
object Gsm7 {
    /** The basic table, minus the escape character (0x1B), which never appears in text. */
    private const val BASIC =
        "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?" +
            "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"

    /** Extension table characters: each costs two septets (escape + code). */
    private const val EXTENSION = "\u000c^{}\\[~]|€"

    const val SINGLE_PART_SEPTETS = 160
    const val MULTI_PART_SEPTETS = 153
    const val SINGLE_PART_UCS2 = 70
    const val MULTI_PART_UCS2 = 67

    private val basic: Set<Char> = BASIC.toSet()
    private val extension: Set<Char> = EXTENSION.toSet()

    fun isBasic(c: Char): Boolean = c in basic

    /** True when [text] can be sent in the GSM 7-bit alphabet (basic or extension table). */
    fun isGsm7(text: String): Boolean = text.all { it in basic || it in extension }

    /** Septets [text] takes in GSM-7, or -1 when it needs UCS-2. */
    fun septets(text: String): Int {
        var n = 0
        for (c in text) {
            n += when (c) {
                in basic -> 1
                in extension -> 2
                else -> return -1
            }
        }
        return n
    }

    /**
     * Parts the SMS app will split [text] into: GSM-7 fits 160 septets in one part, else 153 per part;
     * UCS-2 fits 70 UTF-16 units in one part, else 67 per part.
     */
    fun parts(text: String): Int {
        if (text.isEmpty()) return 1
        val septets = septets(text)
        return if (septets >= 0) {
            if (septets <= SINGLE_PART_SEPTETS) 1 else (septets + MULTI_PART_SEPTETS - 1) / MULTI_PART_SEPTETS
        } else {
            val units = text.length
            if (units <= SINGLE_PART_UCS2) 1 else (units + MULTI_PART_UCS2 - 1) / MULTI_PART_UCS2
        }
    }
}
