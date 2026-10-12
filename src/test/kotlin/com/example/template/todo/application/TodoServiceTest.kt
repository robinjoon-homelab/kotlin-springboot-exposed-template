package com.example.template.todo.application

import com.example.template.todo.application.port.input.TodoNotFoundException
import com.example.template.todo.application.port.output.TodoRepository
import com.example.template.todo.application.service.TodoService
import com.example.template.todo.domain.todo.Todo
import com.example.template.todo.domain.todo.TodoId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class TodoServiceTest {
    private val now = Instant.parse("2026-10-05T00:00:00Z")
    private val repository = FakeTodoRepository()
    private val service = TodoService(repository, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `creation trims the title and adds an incomplete todo with the clock time`() {
        val created = service.create("  Write the API docs  ")

        assertThat(created.title).isEqualTo("Write the API docs")
        assertThat(created.completed).isFalse()
        assertThat(created.createdAt).isEqualTo(now)
        assertThat(repository.operations).containsExactly("add")
        assertThat(repository.findById(created.id)).isEqualTo(created)
    }

    @Test
    fun `invalid creation does not persist a todo`() {
        assertThatIllegalArgumentException().isThrownBy { service.create(" \t\n ") }

        assertThat(repository.operations).isEmpty()
        assertThat(repository.findAll()).isEmpty()
    }

    @Test
    fun `get returns an existing todo without locking or writing it`() {
        val existing = todo("Read the docs")
        repository.seed(existing)

        assertThat(service.get(existing.id)).isEqualTo(existing)
        assertThat(repository.operations).isEmpty()
    }

    @Test
    fun `get reports a missing todo with its plain identifier`() {
        val missingId = TodoId(UUID.randomUUID())

        assertThatThrownBy { service.get(missingId) }
            .isInstanceOf(TodoNotFoundException::class.java)
            .hasMessage("Todo not found: ${missingId.value}")
        assertThat(repository.operations).isEmpty()
    }

    @Test
    fun `completion updates the stored todo and leaves its original state unchanged`() {
        val original = todo("Read the docs")
        repository.seed(original)

        val completed = service.complete(original.id)

        assertThat(completed).isEqualTo(Todo.restore(original.id, original.title, completed = true, original.createdAt))
        assertThat(repository.operations).containsExactly("update")
        assertThat(repository.findById(original.id)).isEqualTo(completed)
        assertThat(original.completed).isFalse()
    }

    @Test
    fun `completing an already completed todo does not write again`() {
        val completed = Todo.restore(TodoId(UUID.randomUUID()), "Read the docs", completed = true, now)
        repository.seed(completed)

        assertThat(service.complete(completed.id)).isEqualTo(completed)
        assertThat(repository.operations).isEmpty()
    }

    @Test
    fun `completion of a missing todo does not write anything`() {
        val missingId = TodoId(UUID.randomUUID())

        assertThatThrownBy { service.complete(missingId) }
            .isInstanceOf(TodoNotFoundException::class.java)
            .hasMessage("Todo not found: ${missingId.value}")
        assertThat(repository.operations).isEmpty()
    }

    @Test
    fun `list returns all stored todos without writing them`() {
        val first = todo("Read the docs")
        val second = Todo.restore(TodoId(UUID.randomUUID()), "Try the API", completed = true, now)
        repository.seed(first, second)

        assertThat(service.list()).containsExactly(first, second)
        assertThat(repository.operations).isEmpty()
    }

    @Test
    fun `list is empty when no todos exist`() {
        assertThat(service.list()).isEmpty()
    }

    private fun todo(title: String) = Todo.create(TodoId(UUID.randomUUID()), title, now)

    // Records writes separately from reads so a repeated completion can prove it does not write again.
    private class FakeTodoRepository : TodoRepository {
        private val todos = linkedMapOf<TodoId, Todo>()
        val operations = mutableListOf<String>()

        fun seed(vararg initialTodos: Todo) {
            initialTodos.forEach { todos[it.id] = it }
        }

        override fun add(todo: Todo): Todo {
            check(todo.id !in todos) { "Duplicate todo: ${todo.id}" }
            operations += "add"
            todos[todo.id] = todo
            return todo
        }

        override fun update(todo: Todo): Todo {
            check(todo.id in todos) { "Missing todo: ${todo.id}" }
            operations += "update"
            todos[todo.id] = todo
            return todo
        }

        override fun findById(id: TodoId): Todo? = todos[id]

        override fun findAll(): List<Todo> = todos.values.toList()
    }
}
