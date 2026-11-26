package dev.ririprojector.helper

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class SecondDisplayTest {
    @Test
    fun `density gives a 600 dp short side, at least 160 dpi`() {
        assertThat(SecondDisplay.densityDpi(1280, 720)).isEqualTo(192)
        assertThat(SecondDisplay.densityDpi(1920, 1080)).isEqualTo(288)
        assertThat(SecondDisplay.densityDpi(3840, 2160)).isEqualTo(576)
        assertThat(SecondDisplay.densityDpi(640, 360)).isEqualTo(160)
    }

    @Test
    fun `display is always landscape`() {
        assertThat(SecondDisplay.landscape(1080, 2424)).isEqualTo(2424 to 1080)
        assertThat(SecondDisplay.landscape(3840, 2160)).isEqualTo(3840 to 2160)
    }

    @Test
    fun `flags grow with the API level and never rotate or secure`() {
        val f30 = SecondDisplay.flags(30)
        val f33 = SecondDisplay.flags(33)
        val f34 = SecondDisplay.flags(34)

        assertThat(f30 and SecondDisplay.FLAG_TRUSTED).isEqualTo(0)
        assertThat(f33 and SecondDisplay.FLAG_OWN_DISPLAY_GROUP).isNotEqualTo(0)
        assertThat(f33 and SecondDisplay.FLAG_OWN_FOCUS).isEqualTo(0)
        assertThat(f34 and SecondDisplay.FLAG_OWN_FOCUS).isNotEqualTo(0)
        listOf(f30, f33, f34).forEach {
            assertThat(it and SecondDisplay.FLAG_DESTROY_CONTENT_ON_REMOVAL).isNotEqualTo(0)
            assertThat(it and (SecondDisplay.FLAG_ROTATES_WITH_CONTENT or SecondDisplay.FLAG_SECURE)).isEqualTo(0)
        }
    }

    @Test
    fun `start command quotes the apk path and keeps the debug package`() {
        val cmd = HelperCommand.start(
            "/data/app/~~a==/dev.ririprojector.debug-x==/base.apk",
            "dev.ririprojector.debug",
            0,
        )

        assertThat(
            cmd,
        ).startsWith("CLASSPATH='/data/app/~~a==/dev.ririprojector.debug-x==/base.apk' setsid app_process")
        assertThat(cmd).contains("dev.ririprojector.helper.HelperMain dev.ririprojector.debug 0")
        assertThat(cmd).endsWith("</dev/null >/dev/null 2>&1 &")
    }

    @Test
    fun `quote escapes single quotes and bad arguments are refused`() {
        assertThat(HelperCommand.quote("a'b")).isEqualTo("'a'\\''b'")
        assertThrows(IllegalArgumentException::class.java) { HelperCommand.start("/x", "evil; rm -rf /", 0) }
        assertThrows(IllegalArgumentException::class.java) { HelperCommand.start("/x", "dev.riri", -1) }
    }

    @Test
    fun `latency histogram reports p95 and max then resets`() {
        val h = LatencyHistogram()
        repeat(95) { h.add(2_500) }
        repeat(5) { h.add(40_000) }

        val (count, p95, max) = h.drain().toList()

        assertThat(count).isEqualTo(100)
        assertThat(p95).isEqualTo(3_000)
        assertThat(max).isEqualTo(40_000)
        assertThat(h.drain().toList()).containsExactly(0L, 0L, 0L).inOrder()
    }
}
