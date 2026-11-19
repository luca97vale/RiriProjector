package dev.ririprojector.feature.receiver

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AspectFitTest {
    @Test
    fun `portrait stream is pillarboxed on a landscape TV`() {
        assertThat(AspectFrameLayout.fit(1920, 1080, 672.0 / 1080)).isEqualTo(672 to 1080)
    }

    @Test
    fun `wider stream is letterboxed`() {
        assertThat(AspectFrameLayout.fit(1920, 1080, 2.0)).isEqualTo(1920 to 960)
    }

    @Test
    fun `matching ratio fills the screen`() {
        assertThat(AspectFrameLayout.fit(1920, 1080, 16.0 / 9)).isEqualTo(1920 to 1080)
    }
}
