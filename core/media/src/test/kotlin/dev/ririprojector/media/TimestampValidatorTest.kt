package dev.ririprojector.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimestampValidatorTest {
    private val now = 5_000_000_000L

    @Test
    fun `accepts a fresh advancing timestamp`() {
        val v = TimestampValidator()
        assertThat(v.accept(true, 480, now - 10_000_000, now)).isTrue()
        assertThat(v.accept(true, 960, now + 10_000_000 - 10_000_000, now + 10_000_000)).isTrue()
        assertThat(v.rejected).isEqualTo(0)
    }

    @Test
    fun `rejects a failed call`() {
        val v = TimestampValidator()
        assertThat(v.accept(false, 480, now, now)).isFalse()
        assertThat(v.rejected).isEqualTo(1)
    }

    @Test
    fun `rejects a frozen or regressing position`() {
        val v = TimestampValidator()
        assertThat(v.accept(true, 480, now, now)).isTrue()
        assertThat(v.accept(true, 480, now + 20_000_000, now + 20_000_000)).isFalse() // frozen
        assertThat(v.accept(true, 400, now + 20_000_000, now + 20_000_000)).isFalse() // backwards
    }

    @Test
    fun `accepts the same sample repeated until it goes stale`() {
        val v = TimestampValidator(maxAgeNs = 200_000_000)
        assertThat(v.accept(true, 480, now, now)).isTrue()
        assertThat(v.accept(true, 480, now, now + 30_000_000)).isTrue()
        assertThat(v.accept(true, 480, now, now + 300_000_000)).isFalse()
    }

    @Test
    fun `rejects stale and future samples`() {
        val v = TimestampValidator(maxAgeNs = 200_000_000)
        assertThat(v.accept(true, 480, now - 250_000_000, now)).isFalse()
        assertThat(v.accept(true, 480, now + 1_000_000, now)).isFalse()
        assertThat(v.accept(true, 480, now - 199_000_000, now)).isTrue()
    }

    @Test
    fun `a rejected sample does not move the reference position`() {
        val v = TimestampValidator()
        assertThat(v.accept(true, 480, now, now)).isTrue()
        assertThat(v.accept(true, 2_000, now - 1_000_000_000, now)).isFalse() // stale
        assertThat(v.accept(true, 960, now, now)).isTrue()
    }

    @Test
    fun `reset starts over`() {
        val v = TimestampValidator()
        v.accept(true, 480, now, now)
        v.accept(false, 0, 0, now)
        v.reset()
        assertThat(v.rejected).isEqualTo(0)
        assertThat(v.accept(true, 0, now, now)).isTrue()
    }
}
