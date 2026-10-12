package com.example.template.transaction

import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

class AggregateMigrationTest {
    // Keep the pool alive across migration and verification, as in the application.
    private val dataSource =
        HikariDataSource().apply {
            jdbcUrl = "jdbc:h2:mem:migration-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
            username = "sa"
            password = ""
            maximumPoolSize = 2
        }

    @AfterEach
    fun closeDatabase() = dataSource.close()

    @Test
    fun `V4 preserves existing orders as one child line without inventing offers`() {
        flyway().target("3").load().migrate()
        execute(
            "INSERT INTO todos (id, title, completed, created_at) " +
                "VALUES ('$TODO_ID', 'Existing task', TRUE, CURRENT_TIMESTAMP)",
        )
        execute(
            "INSERT INTO orders (id, sku, quantity, status, placed_at) " +
                "VALUES ('$ORDER_ID', 'customer reference / preorder', 3, 'CANCELLED', CURRENT_TIMESTAMP)",
        )

        val migration = flyway().load().migrate()

        assertThat(migration.migrationsExecuted).isEqualTo(1)
        assertThat(values("SELECT title FROM todos WHERE id = '$TODO_ID' AND completed = TRUE"))
            .containsExactly("Existing task")
        assertThat(values("SELECT status FROM orders WHERE id = '$ORDER_ID'"))
            .containsExactly("CANCELLED")
        assertThat(values("SELECT sku FROM order_lines WHERE order_id = '$ORDER_ID' AND line_position = 0 AND quantity = 3"))
            .containsExactly("customer reference / preorder")
        assertThat(values("SELECT sku FROM offers")).isEmpty()
        assertThat(values("SELECT column_name FROM information_schema.columns WHERE table_name = 'ORDERS'"))
            .containsExactlyInAnyOrder("ID", "STATUS", "PLACED_AT")
    }

    private fun flyway() = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")

    private fun execute(sql: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.executeUpdate(sql) }
        }
    }

    private fun values(sql: String): List<String> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    generateSequence { if (result.next()) result.getString(1) else null }.toList()
                }
            }
        }

    private companion object {
        const val TODO_ID = "00000000-0000-0000-0000-000000000001"
        const val ORDER_ID = "00000000-0000-0000-0000-000000000002"
    }
}
