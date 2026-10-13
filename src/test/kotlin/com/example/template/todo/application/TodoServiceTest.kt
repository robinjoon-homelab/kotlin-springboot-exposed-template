package com.example.template.todo.application

import com.example.template.todo.application.port.input.TodoNotFoundException
import com.example.template.todo.application.port.output.TodoRepository
import com.example.template.todo.application.service.TodoService
import com.example.template.todo.domain.todo.Todo
import com.example.template.todo.domain.todo.TodoId
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

// The repository is a strict mock: a port call a test does not stub fails the test.
class TodoServiceTest {
    private val now = Instant.parse("2026-10-05T00:00:00Z")
    private val repository = mockk<TodoRepository>()
    private val service = TodoService(repository, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `creation trims the title and adds an incomplete todo with the clock time`() {
        val added = slot<Todo>()
        every { repository.add(capture(added)) } answers { firstArg() }

        val created = service.create("  Write the API docs  ")

        assertThat(added.captured).isEqualTo(Todo.restore(added.captured.id, "Write the API docs", completed = false, now))
        assertThat(created).isEqualTo(added.captured)
    }

    @Test
    fun `invalid creation does not persist a todo`() {
        assertThatIllegalArgumentException().isThrownBy { service.create(" \t\n ") }

        verify { repository wasNot Called }
    }

    @Test
    fun `get returns an existing todo and reports a missing one with its plain identifier`() {
        val existing = todo("Read the docs")
        val missingId = TodoId(UUID.randomUUID())
        every { repository.findById(existing.id) } returns existing
        every { repository.findById(missingId) } returns null

        assertThat(service.get(existing.id)).isEqualTo(existing)
        assertThatThrownBy { service.get(missingId) }
            .isInstanceOf(TodoNotFoundException::class.java)
            .hasMessage("Todo not found: ${missingId.value}")
    }

    @Test
    fun `completion updates the todo and leaves its original state unchanged`() {
        val original = todo("Read the docs")
        val expected = Todo.restore(original.id, original.title, completed = true, original.createdAt)
        every { repository.findById(original.id) } returns original
        every { repository.update(any()) } answers { firstArg() }

        val completed = service.complete(original.id)

        assertThat(completed).isEqualTo(expected)
        verify(exactly = 1) { repository.update(expected) }
        assertThat(original.completed).isFalse()
    }

    @Test
    fun `completing an already completed todo does not write again`() {
        val completed = Todo.restore(TodoId(UUID.randomUUID()), "Read the docs", completed = true, now)
        every { repository.findById(completed.id) } returns completed

        assertThat(service.complete(completed.id)).isEqualTo(completed)
        verify(exactly = 0) { repository.update(any()) }
    }

    @Test
    fun `completion of a missing todo does not write anything`() {
        val missingId = TodoId(UUID.randomUUID())
        every { repository.findById(missingId) } returns null

        assertThatThrownBy { service.complete(missingId) }
            .isInstanceOf(TodoNotFoundException::class.java)
            .hasMessage("Todo not found: ${missingId.value}")
        verify(exactly = 0) { repository.update(any()) }
    }

    private fun todo(title: String) = Todo.create(TodoId(UUID.randomUUID()), title, now)
}
