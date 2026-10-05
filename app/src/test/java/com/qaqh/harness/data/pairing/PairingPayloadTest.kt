package com.qaqh.harness.data.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingPayloadTest {

    @Test
    fun `parses proposal JSON payload`() {
        val raw = """
            {"v":1,"kind":"qaqh-pair","base_url":"https://192.168.1.10:64413",
             "pairing_token":"pt-123","tls_fp":"sha256:abcd","host_name":"DEV-PC",
             "extra_future_field":true}
        """.trimIndent()
        val payload = PairingPayload.parse(raw) as PairingPayload.Pairing
        assertEquals("https://192.168.1.10:64413", payload.baseUrl)
        assertEquals("pt-123", payload.pairingToken)
        assertEquals("sha256:abcd", payload.tlsFingerprint)
        assertEquals("DEV-PC", payload.hostName)
    }

    @Test
    fun `rejects wrong kind`() {
        val raw = """{"v":1,"kind":"other","base_url":"https://x:1","pairing_token":"t"}"""
        try {
            PairingPayload.parse(raw)
            throw AssertionError("expected exception")
        } catch (e: PairingPayloadException) {
            assertTrue(e.message!!.contains("kind"))
        }
    }

    @Test
    fun `rejects unknown version`() {
        val raw = """{"v":2,"kind":"qaqh-pair","base_url":"https://x:1","pairing_token":"t"}"""
        try {
            PairingPayload.parse(raw)
            throw AssertionError("expected exception")
        } catch (e: PairingPayloadException) {
            assertTrue(e.message!!.contains("v=2"))
        }
    }

    @Test
    fun `parses dev fallback uri`() {
        val payload = PairingPayload.parse("qaqh://pair?v=1&endpoint=http%3A%2F%2F192.168.1.10%3A64413&token=abc123&proto=1") as PairingPayload.Direct
        assertEquals("http://192.168.1.10:64413", payload.baseUrl)
        assertEquals("abc123", payload.token)
    }

    @Test
    fun `rejects garbage`() {
        try {
            PairingPayload.parse("hello world")
            throw AssertionError("expected exception")
        } catch (e: PairingPayloadException) {
            assertNull(e.cause)
        }
    }
}
