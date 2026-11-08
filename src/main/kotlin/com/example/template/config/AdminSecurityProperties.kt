package com.example.template.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app.admin")
class AdminSecurityProperties(
    val username: String = "",
    val password: String = "",
) {
    val enabled: Boolean
        get() = username.isNotBlank() && password.isNotBlank()
}
