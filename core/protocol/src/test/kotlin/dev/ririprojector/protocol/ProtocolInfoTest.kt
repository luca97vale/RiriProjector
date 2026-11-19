package dev.ririprojector.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProtocolInfoTest {
    @Test
    fun `protocol version fits in the one-byte header field`() {
        assertThat(ProtocolInfo.PROTOCOL_VERSION).isIn(1..255)
    }

    @Test
    fun `service type is a valid DNS-SD TCP service`() {
        assertThat(ProtocolInfo.SERVICE_TYPE).matches("_[a-z0-9-]{1,15}\\._tcp")
    }
}
