package com.example.template.ordering.adapter.outbound.persistence

import com.example.template.ordering.application.port.output.OrderRepository
import com.example.template.ordering.domain.Sku
import com.example.template.ordering.domain.order.Order
import com.example.template.ordering.domain.order.OrderId
import com.example.template.ordering.domain.order.OrderLine
import com.example.template.ordering.domain.order.OrderStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

// Each test rolls back its transaction. No query follows an expected SQL failure because PostgreSQL aborts
// the transaction after a constraint violation.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.datasource.url=jdbc:h2:mem:order-repository-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"],
)
@ActiveProfiles("test")
@Transactional
class ExposedOrderRepositoryTest {
    @Autowired
    private lateinit var repository: OrderRepository

    private val placedAt = Instant.parse("2026-10-10T00:00:00.123456Z")

    // Deliberately not in SKU order, so only the stored position can restore it.
    private val lines = listOf(OrderLine(Sku("KB-002"), 3), OrderLine(Sku("KB-001"), 1), OrderLine(Sku("MS-001"), 100))

    @Test
    fun `every status round trips with its lines in placement order and its timestamp`() {
        OrderStatus.entries.forEach { status ->
            val order = order(status)

            assertThat(repository.add(order)).isEqualTo(order)
            assertThat(repository.findById(order.id)).isEqualTo(order)
        }
    }

    @Test
    fun `an order with the most lines keeps every line in placement order`() {
        val mostLines = (Order.MAX_LINES downTo 1).map { OrderLine(Sku("SKU-$it"), it) }
        val order = Order.place(OrderId(UUID.randomUUID()), mostLines, placedAt)

        assertThat(repository.add(order).lines).containsExactlyElementsOf(mostLines)
        assertThat(repository.findById(order.id)).isEqualTo(order)
    }

    @Test
    fun `orders sharing a sku keep their own lines`() {
        val first = repository.add(order(OrderStatus.PLACED))
        val secondLines = listOf(OrderLine(Sku("KB-001"), 7))
        val second = repository.add(Order.place(OrderId(UUID.randomUUID()), secondLines, placedAt))

        assertThat(repository.findById(first.id)?.lines).isEqualTo(lines)
        assertThat(repository.findById(second.id)?.lines).isEqualTo(secondLines)
    }

    @Test
    fun `update writes the status and keeps the placed lines`() {
        val placed = repository.add(order(OrderStatus.PLACED))
        val withOtherLines = Order.restore(placed.id, listOf(OrderLine(Sku("KB-999"), 99)), OrderStatus.CANCELLED, placedAt)

        assertThat(repository.update(withOtherLines)).isEqualTo(Order.restore(placed.id, lines, OrderStatus.CANCELLED, placedAt))
    }

    @Test
    fun `update of a missing order fails instead of inserting it`() {
        val missing = order(OrderStatus.CANCELLED)

        assertThatThrownBy { repository.update(missing) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Order to update does not exist: ${missing.id}")
        assertThat(repository.findById(missing.id)).isNull()
    }

    @Test
    fun `locking read returns the stored order and null for a missing one`() {
        val stored = repository.add(order(OrderStatus.PLACED))

        assertThat(repository.findByIdForUpdate(stored.id)).isEqualTo(stored)
        assertThat(repository.findByIdForUpdate(OrderId(UUID.randomUUID()))).isNull()
    }

    @Test
    fun `adding an existing id fails instead of overwriting`() {
        val stored = repository.add(order(OrderStatus.PLACED))

        assertThatThrownBy { repository.add(Order.restore(stored.id, lines, OrderStatus.CANCELLED, placedAt)) }
            .isInstanceOf(SQLException::class.java)
    }

    private fun order(status: OrderStatus) = Order.restore(OrderId(UUID.randomUUID()), lines, status, placedAt)
}
