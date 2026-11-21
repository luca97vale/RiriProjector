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
    fun `vendor tv feature alone marks a tv even without leanback`() {
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
    fun `phone or tablet is not a tv`() {
        assertThat(
            ModeResolver.isTv(hasFireTvFeature = false, hasLeanbackFeature = false, isTelevisionUiMode = false),
        ).isFalse()
    }

    @Test
    fun `remembered choice beats device type`() {
        assertThat(ModeResolver.resolve(null, isTv = false, remembered = AppMode.RECEIVER)).isEqualTo(AppMode.RECEIVER)
        assertThat(ModeResolver.resolve(null, isTv = true, remembered = AppMode.SENDER)).isEqualTo(AppMode.SENDER)
    }

    @Test
    fun `explicit extra beats remembered choice`() {
        assertThat(
            ModeResolver.resolve("sender", isTv = false, remembered = AppMode.RECEIVER),
        ).isEqualTo(AppMode.SENDER)
    }

    @Test
    fun `unknown extra falls back to remembered choice`() {
        assertThat(ModeResolver.resolve("tv", isTv = false, remembered = AppMode.RECEIVER)).isEqualTo(AppMode.RECEIVER)
    }

    @Test
    fun `running sender session beats extra, remembered choice and tv`() {
        assertThat(
            ModeResolver.resolve("receiver", isTv = true, remembered = AppMode.RECEIVER, senderSessionActive = true),
        ).isEqualTo(AppMode.SENDER)
    }

    @Test
    fun `switch actions map to modes`() {
        fun target(action: String?) =
            ModeResolver.switchTarget(action, useAsReceiverAction = "R", useAsSenderAction = "S")
        assertThat(target("R")).isEqualTo(AppMode.RECEIVER)
        assertThat(target("S")).isEqualTo(AppMode.SENDER)
        assertThat(target("android.intent.action.MAIN")).isNull()
        assertThat(target(null)).isNull()
    }
}
