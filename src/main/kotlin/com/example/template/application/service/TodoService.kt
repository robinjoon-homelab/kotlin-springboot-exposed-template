package com.example.template.application.service

import com.example.template.application.port.input.TodoNotFoundException
import com.example.template.application.port.input.TodoUseCase
import com.example.template.application.port.output.TodoRepository
import com.example.template.domain.Todo
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Transactional(readOnly = true)
class TodoService(
    private val repository: TodoRepository,
    private val clock: Clock,
) : TodoUseCase {
    @Transactional
    override fun create(title: String): Todo =
        repository.save(
            Todo(
                id = UUID.randomUUID(),
                title = title.trim(),
                createdAt = Instant.now(clock),
            ),
        )

    override fun get(id: UUID): Todo = repository.findById(id) ?: throw TodoNotFoundException(id)

    override fun list(): List<Todo> = repository.findAll()

    @Transactional
    override fun complete(id: UUID): Todo = repository.save(get(id).complete())
}
