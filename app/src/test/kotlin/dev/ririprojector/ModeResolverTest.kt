package dev.ririprojector

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModeResolverTest {
    @Test
    fun `explicit extra wins over device type`() {
        assertThat(ModeResolver.resolve("sender", isTv = true)).isEqualTo(AppMode.SENDER)
        assertThat(ModeResolver.resolve("receiver", isTv = false)).isEqualTo(AppMode.RECEIVER)
    }

    @Test
    fun `extra is case-insensitive and trimmed`() {
        assertThat(ModeResolver.resolve(" Receiver ", isTv = false)).isEqualTo(AppMode.RECEIVER)
    }

    @Test
    fun `tv devices default to receiver`() {
        assertThat(ModeResolver.resolve(null, isTv = true)).isEqualTo(AppMode.RECEIVER)
    }

    @Test
    fun `other devices default to sender`() {
        assertThat(ModeResolver.resolve(null, isTv = false)).isEqualTo(AppMode.SENDER)
    }

    @Test
    fun `unknown extra falls back to device type`() {
        assertThat(ModeResolver.resolve("projector", isTv = true)).isEqualTo(AppMode.RECEIVER)
        assertThat(ModeResolver.resolve("", isTv = false)).isEqualTo(AppMode.SENDER)
    }

    @Test
    fun `fire tv feature alone marks a tv even without leanback`() {
        assertThat(
            ModeResolver.isTv(hasFireTvFeature = true, hasLeanbackFeature = false, isTelevisionUiMode = false),
        ).isTrue()
    }

    @Test
    fun `television ui mode alone marks a tv`() {
        assertThat(
            ModeResolver.isTv(hasFireTvFeature = false, hasLeanbackFeature = false, isTelevisionUiMode = true),
        ).isTrue()
    }

    @Test
    fun `tablet is not a tv`() {
        assertThat(
            ModeResolver.isTv(hasFireTvFeature = false, hasLeanbackFeature = false, isTelevisionUiMode = false),
        ).isFalse()
    }
}
