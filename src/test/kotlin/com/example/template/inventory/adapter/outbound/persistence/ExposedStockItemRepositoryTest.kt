package com.example.template.inventory.adapter.outbound.persistence

import com.example.template.inventory.application.port.output.StockItemRepository
import com.example.template.inventory.domain.stock.DuplicateSkuException
import com.example.template.inventory.domain.stock.Sku
import com.example.template.inventory.domain.stock.StockItem
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import javax.sql.DataSource

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.datasource.url=jdbc:h2:mem:inventory-repository-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"],
)
@ActiveProfiles("test")
@Transactional
class ExposedStockItemRepositoryTest {
    @Autowired
    private lateinit var repository: StockItemRepository

    @Autowired
    private lateinit var dataSource: DataSource

    private val sku = Sku("KB-001")

    @BeforeEach
    fun clearDatabase() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.executeUpdate("DELETE FROM stock_items") }
        }
    }

    @Test
    fun `add returns the state that a later read restores`() {
        val added = repository.add(StockItem.register(sku, 5))

        assertThat(added).isEqualTo(StockItem.register(sku, 5))
        assertThat(repository.findBySku(sku)).isEqualTo(added)
        assertThat(repository.findBySku(Sku("KB-404"))).isNull()
    }

    // An upsert would succeed here. No query follows the failure because PostgreSQL aborts the transaction.
    @Test
    fun `adding a registered sku fails as a duplicate instead of overwriting`() {
        repository.add(StockItem.register(sku, 5))

        assertThatThrownBy { repository.add(StockItem.register(sku, 9)) }
            .isInstanceOf(DuplicateSkuException::class.java)
            .hasMessage("SKU already registered: KB-001")
    }

    @Test
    fun `update stores onHand of that item only`() {
        val other = repository.add(StockItem.register(Sku("MS-001"), 2))
        val stored = repository.add(StockItem.register(sku, 5))

        val changed = repository.update(stored.restock(4))

        assertThat(changed).isEqualTo(StockItem.restore(sku, 9))
        assertThat(repository.findBySku(sku)).isEqualTo(changed)
        assertThat(repository.findBySku(other.sku)).isEqualTo(other)
    }

    @Test
    fun `updating a missing item fails instead of inserting it`() {
        assertThatIllegalStateException().isThrownBy { repository.update(StockItem.register(sku, 5)) }

        assertThat(repository.findBySku(sku)).isNull()
    }

    @Test
    fun `locking read returns the stored item or null`() {
        val stored = repository.add(StockItem.register(sku, 5))

        assertThat(repository.findBySkuForUpdate(sku)).isEqualTo(stored)
        assertThat(repository.findBySkuForUpdate(Sku("KB-404"))).isNull()
    }
}
