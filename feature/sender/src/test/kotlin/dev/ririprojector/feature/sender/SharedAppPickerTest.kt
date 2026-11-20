package dev.ririprojector.feature.sender

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SharedAppPickerTest {
    private val excluded = setOf("dev.ririprojector.debug", "com.android.systemui", "com.sec.android.app.launcher")

    @Test
    fun `picks the app brought to the front after the consent dialog`() {
        val events =
            listOf(
                ResumeEvent(900, "com.spotify.music"), // before the dialog
                ResumeEvent(1_000, "dev.ririprojector.debug"),
                ResumeEvent(1_500, "com.android.systemui"), // the app chooser
                ResumeEvent(2_000, "com.google.android.youtube"),
            )
        assertThat(
            SharedAppPicker.pick(events, sinceMs = 1_000, untilMs = 5_000, excluded),
        ).isEqualTo("com.google.android.youtube")
    }

    @Test
    fun `ignores this app resuming after the chosen one`() {
        val events =
            listOf(ResumeEvent(2_000, "com.google.android.youtube"), ResumeEvent(2_100, "dev.ririprojector.debug"))
        assertThat(SharedAppPicker.pick(events, 1_000, 5_000, excluded)).isEqualTo("com.google.android.youtube")
    }

    @Test
    fun `none when only excluded or older apps resumed`() {
        val events =
            listOf(
                ResumeEvent(500, "com.google.android.youtube"),
                ResumeEvent(1_200, "com.sec.android.app.launcher"),
                ResumeEvent(1_300, "com.android.systemui"),
            )
        assertThat(SharedAppPicker.pick(events, 1_000, 5_000, excluded)).isNull()
        assertThat(SharedAppPicker.pick(emptyList(), 1_000, 5_000, excluded)).isNull()
    }

    @Test
    fun `an app opened after the window is not taken for the shared one`() {
        // Entire screen shared, then the user opens YouTube later: still the entire screen.
        val events =
            listOf(ResumeEvent(1_200, "dev.ririprojector.debug"), ResumeEvent(9_000, "com.google.android.youtube"))
        assertThat(SharedAppPicker.pick(events, 1_000, 5_000, excluded)).isNull()
    }
}
