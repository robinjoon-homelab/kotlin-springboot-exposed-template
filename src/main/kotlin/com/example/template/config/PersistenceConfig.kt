package com.example.template.config

import com.example.template.adapter.outbound.persistence.ExposedTodoRepository
import com.example.template.application.port.output.TodoRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class PersistenceConfig {
    @Bean
    fun todoRepository(): TodoRepository = ExposedTodoRepository()
}
