package dev.ririprojector.net.devlog

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

class DevLogTest {
    @Before
    fun setUp() {
        DevLog.enabled = false
        DevLog.log.clear()
    }

    @After
    fun tearDown() {
        DevLog.enabled = false
        DevLog.log.clear()
    }

    @Test
    fun `disabled never invokes the message lambda and records nothing`() {
        var calls = 0
        DevLog.info("T") { (calls++).toString() }
        DevLog.warn("T") { (calls++).toString() }
        DevLog.error("T") { (calls++).toString() }
        DevLog.infoAt(0, "T") { (calls++).toString() }
        assertThat(calls).isEqualTo(0)
        assertThat(DevLog.events.value).isEmpty()
    }

    @Test
    fun `enabled records each level`() {
        DevLog.enabled = true
        DevLog.info("T") { "i" }
        DevLog.warn("T") { "w" }
        DevLog.error("T") { "e" }
        assertThat(DevLog.events.value.map { it.level })
            .containsExactly(DevLevel.INFO, DevLevel.WARN, DevLevel.ERROR).inOrder()
    }

    @Test
    fun `infoAt records the line when enabled`() {
        DevLog.enabled = true
        DevLog.begin("start")
        DevLog.infoAt(DevLog.nowMs(), "T") { "stamped" }
        assertThat(DevLog.events.value.map { it.message }).containsExactly("start", "stamped").inOrder()
    }

    @Test
    fun `disabling clears the log`() {
        DevLog.enabled = true
        DevLog.info("T") { "x" }
        DevLog.enabled = false
        assertThat(DevLog.events.value).isEmpty()
    }

    @Test
    fun `enabling twice does not clear`() {
        DevLog.enabled = true
        DevLog.info("T") { "x" }
        DevLog.enabled = true
        assertThat(DevLog.events.value).hasSize(1)
    }

    @Test
    fun `begin beginUnlessRecent and touch are no ops when disabled`() {
        DevLog.begin("a")
        DevLog.beginUnlessRecent("b", 1_000)
        DevLog.touch()
        assertThat(DevLog.events.value).isEmpty()
    }

    @Test
    fun `begin records when enabled`() {
        DevLog.enabled = true
        DevLog.begin("a")
        assertThat(DevLog.events.value.map { it.message }).containsExactly("a")
    }

    @Test
    fun `format reflects the recorded lines`() {
        DevLog.enabled = true
        DevLog.begin("a")
        assertThat(DevLog.format()).isEqualTo("+0.000 s  I  [Session] a")
    }
}
