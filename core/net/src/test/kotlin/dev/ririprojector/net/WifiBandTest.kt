package dev.ririprojector.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WifiBandTest {
    @Test
    fun `maps channel frequencies to their band`() {
        assertThat(WifiBand.of(2_412)).isEqualTo(WifiBand.GHZ_2_4)
        assertThat(WifiBand.of(2_484)).isEqualTo(WifiBand.GHZ_2_4)
        assertThat(WifiBand.of(5_180)).isEqualTo(WifiBand.GHZ_5)
        assertThat(WifiBand.of(5_825)).isEqualTo(WifiBand.GHZ_5)
        assertThat(WifiBand.of(5_955)).isEqualTo(WifiBand.GHZ_6)
    }

    @Test
    fun `unknown frequencies have no band`() {
        assertThat(WifiBand.of(-1)).isNull()
        assertThat(WifiBand.of(0)).isNull()
        assertThat(WifiBand.of(60_480)).isNull()
        assertThat(WifiBand.describe(-1)).isNull()
    }

    @Test
    fun `describe shows band and channel frequency`() {
        assertThat(WifiBand.describe(2_432)).isEqualTo("2.4 GHz (2432 MHz)")
        assertThat(WifiBand.describe(5_220)).isEqualTo("5 GHz (5220 MHz)")
    }
}
