package dev.ririprojector.net

import com.google.common.truth.Truth.assertThat
import dev.ririprojector.net.ReconnectPolicy.Decision.FINAL
import dev.ririprojector.net.ReconnectPolicy.Decision.RETRY
import dev.ririprojector.protocol.ByeReason
import org.junit.Test

class ReconnectPolicyTest {
    private fun classify(
        bye: ByeReason? = null,
        incompatible: Boolean = false,
        encoderFailed: Boolean = false,
        encoderFailures: Int = 0,
    ) = ReconnectPolicy.classify(bye, incompatible, encoderFailed, encoderFailures)

    @Test
    fun `receiver user stop, incompatibility and revoked projection are final`() {
        assertThat(classify(ByeReason.USER_STOPPED)).isEqualTo(FINAL)
        assertThat(classify(ByeReason.INCOMPATIBLE)).isEqualTo(FINAL)
        assertThat(classify(ByeReason.PROJECTION_REVOKED)).isEqualTo(FINAL)
        assertThat(classify(incompatible = true)).isEqualTo(FINAL)
    }

    @Test
    fun `receiver backgrounded, busy, error and network failures are retried`() {
        assertThat(classify(ByeReason.NORMAL)).isEqualTo(RETRY)
        assertThat(classify(ByeReason.BUSY)).isEqualTo(RETRY)
        assertThat(classify(ByeReason.ERROR)).isEqualTo(RETRY)
        assertThat(classify(null)).isEqualTo(RETRY)
    }

    @Test
    fun `encoder failures are retried at most 3 times`() {
        assertThat(classify(encoderFailed = true, encoderFailures = 3)).isEqualTo(RETRY)
        assertThat(classify(encoderFailed = true, encoderFailures = 4)).isEqualTo(FINAL)
    }
}
