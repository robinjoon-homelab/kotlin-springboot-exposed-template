package com.example.template.inventory.application.service

import com.example.template.inventory.application.port.input.StockItemNotFoundException
import com.example.template.inventory.application.port.output.StockItemRepository
import com.example.template.inventory.domain.stock.InvalidStockException
import com.example.template.inventory.domain.stock.Sku
import com.example.template.inventory.domain.stock.StockItem
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class StockServiceTest {
    private val sku = Sku("KB-001")
    private val repository = FakeStockItemRepository()
    private val service = StockService(repository)

    @Test
    fun `registration adds a new item`() {
        val registered = service.register(sku, 5)

        assertThat(registered).isEqualTo(StockItem.register(sku, 5))
        assertThat(repository.operations).containsExactly("add")
    }

    @Test
    fun `invalid registration does not add an item`() {
        assertThatThrownBy { service.register(sku, -1) }.isInstanceOf(InvalidStockException::class.java)

        assertThat(repository.operations).isEmpty()
    }

    @Test
    fun `restock locks the item before updating it`() {
        repository.seed(StockItem.register(sku, 5))

        val restocked = service.restock(sku, 3)

        assertThat(restocked.onHand).isEqualTo(8)
        assertThat(repository.operations).containsExactly("findBySkuForUpdate", "update")
    }

    @Test
    fun `invalid restock does not update the item`() {
        repository.seed(StockItem.register(sku, 5))

        assertThatThrownBy { service.restock(sku, 0) }.isInstanceOf(InvalidStockException::class.java)

        assertThat(repository.operations).containsExactly("findBySkuForUpdate")
    }

    @Test
    fun `restock and get report a missing item with its sku`() {
        assertThatThrownBy { service.restock(sku, 3) }
            .isInstanceOf(StockItemNotFoundException::class.java)
            .hasMessage("Stock item not found: KB-001")
        assertThatThrownBy { service.get(sku) }
            .isInstanceOf(StockItemNotFoundException::class.java)
            .hasMessage("Stock item not found: KB-001")

        assertThat(repository.operations).containsExactly("findBySkuForUpdate")
    }

    @Test
    fun `get reads without locking or writing`() {
        val item = StockItem.register(sku, 5)
        repository.seed(item)

        assertThat(service.get(sku)).isEqualTo(item)
        assertThat(repository.operations).isEmpty()
    }

    // Records locking reads and writes; plain reads are not recorded so assertions show the write path.
    private class FakeStockItemRepository : StockItemRepository {
        private val items = linkedMapOf<Sku, StockItem>()
        val operations = mutableListOf<String>()

        fun seed(item: StockItem) {
            items[item.sku] = item
        }

        override fun add(item: StockItem): StockItem {
            check(item.sku !in items) { "Duplicate item: ${item.sku}" }
            operations += "add"
            items[item.sku] = item
            return item
        }

        override fun update(item: StockItem): StockItem {
            check(item.sku in items) { "Missing item: ${item.sku}" }
            operations += "update"
            items[item.sku] = item
            return item
        }

        override fun findBySku(sku: Sku): StockItem? = items[sku]

        override fun findBySkuForUpdate(sku: Sku): StockItem? {
            operations += "findBySkuForUpdate"
            return items[sku]
        }
    }
}
