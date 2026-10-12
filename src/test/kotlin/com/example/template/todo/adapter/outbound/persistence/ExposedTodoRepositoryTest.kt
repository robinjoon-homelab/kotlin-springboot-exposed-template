package com.example.template.todo.adapter.outbound.persistence

import com.example.template.todo.application.port.output.TodoRepository
import com.example.template.todo.domain.todo.Todo
import com.example.template.todo.domain.todo.TodoId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.datasource.url=jdbc:h2:mem:repository-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"],
)
@ActiveProfiles("test")
@Transactional
class ExposedTodoRepositoryTest {
    @Autowired
    private lateinit var repository: TodoRepository

    @Autowired
    private lateinit var dataSource: DataSource

    @BeforeEach
    fun clearDatabase() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.executeUpdate("DELETE FROM todos") }
        }
    }

    @Test
    fun `add returns the same timestamp precision as a later read`() {
        val original = Todo.create(newId(), "Persist nanoseconds consistently", Instant.parse("2026-01-01T00:00:00.123456789Z"))

        val added = repository.add(original)

        assertThat(repository.findById(original.id)).isEqualTo(added)
        assertThat(added.id).isEqualTo(original.id)
        assertThat(added.title).isEqualTo(original.title)
        assertThat(added.completed).isFalse()
    }

    // An upsert would succeed here. No query follows the failure because PostgreSQL aborts the transaction.
    @Test
    fun `adding an existing identifier fails instead of overwriting`() {
        val original = repository.add(todo("00000000-0000-0000-0000-000000000001"))
        val duplicate = Todo.create(original.id, "Overwrite attempt", original.createdAt)

        assertThatThrownBy { repository.add(duplicate) }.isInstanceOf(SQLException::class.java)
    }

    @Test
    fun `update persists completion of the existing record`() {
        val original = repository.add(todo("00000000-0000-0000-0000-000000000001"))

        val completed = repository.update(original.complete())

        assertThat(completed.completed).isTrue()
        assertThat(completed.createdAt).isEqualTo(original.createdAt)
        assertThat(repository.findById(original.id)).isEqualTo(completed)
        assertThat(repository.findAll()).containsExactly(completed)
    }

    @Test
    fun `updating a missing todo fails instead of inserting it`() {
        val missing = todo("00000000-0000-0000-0000-000000000001")

        assertThatIllegalStateException().isThrownBy { repository.update(missing) }
        assertThat(repository.findAll()).isEmpty()
    }

    @Test
    fun `read returns the stored todo or null`() {
        val stored = repository.add(todo("00000000-0000-0000-0000-000000000001"))

        assertThat(repository.findById(stored.id)).isEqualTo(stored)
        assertThat(repository.findById(newId())).isNull()
    }

    @Test
    fun `list uses identifier order when creation timestamps match`() {
        val first = todo("00000000-0000-0000-0000-000000000001")
        val second = todo("00000000-0000-0000-0000-000000000002")
        repository.add(second)
        repository.add(first)

        assertThat(repository.findAll()).containsExactly(first, second)
        assertThat(repository.findById(newId())).isNull()
    }

    private fun newId() = TodoId(UUID.randomUUID())

    private fun todo(id: String) = Todo.create(TodoId(UUID.fromString(id)), "Persist the todo", Instant.parse("2026-01-01T00:00:00Z"))
}
