package com.example.template.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.factory.PasswordEncoderFactories

class AdminPasswordTest {
    private val encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    @Test
    fun `a value naming its storage format is kept`() {
        assertThat(storedPassword("{bcrypt}abc", encoder)).isEqualTo("{bcrypt}abc")
    }

    @Test
    fun `a plain value is encoded`() {
        val stored = storedPassword("plain", encoder)

        assertThat(stored).isNotEqualTo("plain")
        assertThat(encoder.matches("plain", stored)).isTrue()
    }

    @Test
    fun `empty braces do not name a storage format`() {
        val stored = storedPassword("{}x", encoder)

        assertThat(stored).isNotEqualTo("{}x")
        assertThat(encoder.matches("{}x", stored)).isTrue()
    }
}
