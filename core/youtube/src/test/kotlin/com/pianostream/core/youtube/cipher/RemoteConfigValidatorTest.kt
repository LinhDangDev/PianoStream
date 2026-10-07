package com.pianostream.core.youtube.cipher

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** F2/U3: schema allow-list, identifier regex, size cap — fail closed. */
class RemoteConfigValidatorTest {

    private fun valid(sig: String = "abcSig", n: String = "abcN") = """
        {"version":1,"configs":{"h1":{"sig":"$sig","nClass":"$n","sts":"20000","aliases":{"x":"y"}}}}
    """.trimIndent().toByteArray()

    @Test
    fun `unsigned but schema-valid config is accepted`() {
        val r = RemoteConfigValidator.validate(valid())
        assertThat(r).isNotNull()
        assertThat(r!!.configs["h1"]!!.sig).isEqualTo("abcSig")
        assertThat(r.configs["h1"]!!.nClass).isEqualTo("abcN")
        assertThat(r.configs["h1"]!!.sts).isEqualTo("20000")
        assertThat(r.configs["h1"]!!.aliases).containsEntry("x", "y")
    }

    @Test
    fun `unknown top-level keys are ignored`() {
        val payload = """
            {"version":1,"evil":"payload","configs":{"h":{"sig":"s","nClass":"n"}}}
        """.toByteArray()
        assertThat(RemoteConfigValidator.validate(payload)).isNotNull()
    }

    @Test
    fun `missing configs key rejected`() {
        assertThat(RemoteConfigValidator.validate("""{"version":1}""".toByteArray()))
            .isNull()
    }

    @Test
    fun `identifier regex rejects hostile sig`() {
        assertThat(RemoteConfigValidator.validate(valid(sig = "evil();drop"))).isNull()
        assertThat(RemoteConfigValidator.validate(valid(sig = "a".repeat(65)))).isNull()
        assertThat(RemoteConfigValidator.validate(valid(sig = "with space"))).isNull()
        assertThat(RemoteConfigValidator.validate(valid(sig = ""))).isNull()
    }

    @Test
    fun `identifier regex rejects hostile nClass and sts`() {
        val badSts = """
            {"configs":{"h":{"sig":"s","nClass":"n","sts":"x;eval(1)"}}}
        """.toByteArray()
        assertThat(RemoteConfigValidator.validate(badSts)).isNull()
    }

    @Test
    fun `oversized payload rejected`() {
        val big = ByteArray(RemoteConfigValidator.MAX_BYTES + 1)
        assertThat(RemoteConfigValidator.validate(big)).isNull()
    }

    @Test
    fun `malformed json rejected`() {
        assertThat(RemoteConfigValidator.validate("not json".toByteArray())).isNull()
        assertThat(RemoteConfigValidator.validate("{}".toByteArray())).isNull()
    }

    @Test
    fun `alias key and value must match the identifier regex`() {
        val bad = """
            {"configs":{"h":{"sig":"s","nClass":"n","aliases":{"a.b":"x"}}}}
        """.toByteArray()
        assertThat(RemoteConfigValidator.validate(bad)).isNull()
    }

    @Test
    fun `hostile hash keys rejected`() {
        val bad = """
            {"configs":{"../../etc":{"sig":"s","nClass":"n"}}}
        """.toByteArray()
        assertThat(RemoteConfigValidator.validate(bad)).isNull()
    }
}
