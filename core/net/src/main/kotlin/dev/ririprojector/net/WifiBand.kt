package dev.ririprojector.net

/** Wi-Fi band of a channel centre frequency, for the receiver's stats overlay (#23). */
enum class WifiBand(val label: String) {
    GHZ_2_4("2.4 GHz"),
    GHZ_5("5 GHz"),
    GHZ_6("6 GHz"),
    ;

    companion object {
        /** Null for an unknown or non-Wi-Fi frequency (e.g. Ethernet, or Wi-Fi off: -1). */
        fun of(frequencyMhz: Int): WifiBand? = when (frequencyMhz) {
            in 2_400..2_500 -> GHZ_2_4
            in 4_900..5_899 -> GHZ_5
            in 5_925..7_125 -> GHZ_6
            else -> null
        }

        /** "5 GHz (5220 MHz)", or null when the band is unknown. */
        fun describe(frequencyMhz: Int): String? = of(frequencyMhz)?.let { "${it.label} ($frequencyMhz MHz)" }
    }
}
