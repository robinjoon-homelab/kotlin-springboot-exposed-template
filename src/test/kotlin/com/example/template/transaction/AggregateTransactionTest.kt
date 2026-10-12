package com.example.template.transaction

import com.example.template.inventory.application.port.input.StockUseCase
import com.example.template.inventory.domain.stock.Sku
import com.example.template.ordering.application.port.input.OfferNotOnSaleException
import com.example.template.ordering.application.port.input.OfferUseCase
import com.example.template.ordering.application.port.input.OrderUseCase
import com.example.template.ordering.domain.offer.OfferStatus
import com.example.template.ordering.domain.order.OrderLine
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import com.example.template.ordering.domain.Sku as OrderingSku

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.datasource.url=jdbc:h2:mem:aggregate-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"],
)
@ActiveProfiles("test")
@Import(AggregateTransactionTestConfig::class)
class AggregateTransactionTest {
    @Autowired
    private lateinit var orders: OrderUseCase

    @Autowired
    private lateinit var offers: OfferUseCase

    @Autowired
    private lateinit var stocks: StockUseCase

    @Autowired
    private lateinit var orderWrites: OrderWriteProbe

    @Autowired
    private lateinit var stockWrites: StockWriteProbe

    @Autowired
    private lateinit var dataSource: DataSource

    @BeforeEach
    fun clearDatabase() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM order_lines")
                statement.executeUpdate("DELETE FROM orders")
                statement.executeUpdate("DELETE FROM offers")
                statement.executeUpdate("DELETE FROM stock_items")
            }
        }
        orderWrites.reset()
        stockWrites.reset()
    }

    @AfterEach
    fun noTransactionLeaks() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
        assertThat(TransactionManager.currentOrNull()).isNull()
    }

    @Test
    fun `order placement and cancellation commit independently of inventory`() {
        stocks.register(Sku(SKU), 5)
        offers.register(OrderingSku.of(SKU))
        offers.register(OrderingSku.of("customer reference"))
        val order = orders.place(listOf(line(SKU, 100)))
        val customerReference = orders.place(listOf(line("customer reference", 1)))

        assertThat(storedOrderStatus(order.id.value)).isEqualTo("PLACED")
        assertThat(storedOrderStatus(customerReference.id.value)).isEqualTo("PLACED")

        orders.cancel(order.id)
        orders.cancel(order.id)

        assertThat(storedOrderStatus(order.id.value)).isEqualTo("CANCELLED")
        assertThat(storedStock()).isEqualTo(5)
    }

    @Test
    fun `failure after a cancellation write rolls back the order`() {
        offers.register(OrderingSku.of(SKU))
        val order = orders.place(listOf(line(SKU, 2)))
        orderWrites.failNextUpdate()

        assertThatThrownBy { orders.cancel(order.id) }.isInstanceOf(InjectedAggregateWriteFailure::class.java)

        assertThat(storedOrderStatus(order.id.value)).isEqualTo("PLACED")
        orders.cancel(order.id)
        assertThat(storedOrderStatus(order.id.value)).isEqualTo("CANCELLED")
    }

    @Test
    fun `failure after a restock write rolls back inventory`() {
        stocks.register(Sku(SKU), 5)
        stockWrites.failNextUpdate()

        assertThatThrownBy { stocks.restock(Sku(SKU), 2) }.isInstanceOf(InjectedAggregateWriteFailure::class.java)

        assertThat(storedStock()).isEqualTo(5)
        stocks.restock(Sku(SKU), 2)
        assertThat(storedStock()).isEqualTo(7)
    }

    @Test
    fun `concurrent restocks retain every committed increment`() {
        stocks.register(Sku(SKU), 5)
        val workers = 4
        val start = CyclicBarrier(workers)

        Executors.newFixedThreadPool(workers).use { executor ->
            val writes =
                (1..workers).map {
                    executor.submit {
                        start.await(5, TimeUnit.SECONDS)
                        stocks.restock(Sku(SKU), 2)
                    }
                }
            writes.forEach { it.get(10, TimeUnit.SECONDS) }
        }

        assertThat(storedStock()).isEqualTo(13)
    }

    @Test
    fun `a failed child insert rolls back the complete order aggregate`() {
        offers.register(OrderingSku.of(SKU))
        offers.register(OrderingSku.of("ROLLBACK-CHILD"))
        executeSql("ALTER TABLE order_lines ADD CONSTRAINT reject_test_sku CHECK (sku <> 'ROLLBACK-CHILD')")

        try {
            assertThatThrownBy { orders.place(listOf(line(SKU, 2), line("ROLLBACK-CHILD", 3))) }
                .isInstanceOf(ExposedSQLException::class.java)

            assertThat(storedCount("orders")).isZero()
            assertThat(storedCount("order_lines")).isZero()
            assertThat(storedCount("offers")).isEqualTo(2)
        } finally {
            executeSql("ALTER TABLE order_lines DROP CONSTRAINT reject_test_sku")
        }

        orders.place(listOf(line(SKU, 2), line("ROLLBACK-CHILD", 3)))
        assertThat(storedCount("orders")).isEqualTo(1)
        assertThat(storedCount("order_lines")).isEqualTo(2)
    }

    @Test
    fun `offer discontinuation leaves existing orders and stock unchanged`() {
        stocks.register(Sku(SKU), 5)
        val offer = offers.register(OrderingSku.of(SKU))
        val order = orders.place(listOf(line(SKU, 2)))

        offers.discontinue(offer.id)

        assertThat(offers.get(offer.id).status).isEqualTo(OfferStatus.DISCONTINUED)
        assertThat(orders.get(order.id)).isEqualTo(order)
        assertThat(storedStock()).isEqualTo(5)
        assertThatThrownBy { orders.place(listOf(line(SKU, 1))) }.isInstanceOf(OfferNotOnSaleException::class.java)
        assertThat(storedCount("orders")).isEqualTo(1)
    }

    private fun line(
        sku: String,
        quantity: Int,
    ): OrderLine = OrderLine(OrderingSku.of(sku), quantity)

    private fun executeSql(sql: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.executeUpdate(sql) }
        }
    }

    private fun storedCount(table: String): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $table").use { result ->
                    check(result.next())
                    result.getInt(1)
                }
            }
        }

    private fun storedOrderStatus(id: UUID): String =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT status FROM orders WHERE id = ?").use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { result ->
                    check(result.next())
                    result.getString("status")
                }
            }
        }

    private fun storedStock(): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT on_hand FROM stock_items WHERE sku = ?").use { statement ->
                statement.setString(1, SKU)
                statement.executeQuery().use { result ->
                    check(result.next())
                    result.getInt("on_hand")
                }
            }
        }

    private companion object {
        const val SKU = "KB-001"
    }
}
