package com.example.template.inventory.application.service

import com.example.template.inventory.application.port.input.StockItemNotFoundException
import com.example.template.inventory.application.port.output.StockItemRepository
import com.example.template.inventory.domain.stock.InvalidStockException
import com.example.template.inventory.domain.stock.Sku
import com.example.template.inventory.domain.stock.StockItem
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

// The repository is a strict mock: a port call a test does not stub fails the test.
class StockServiceTest {
    private val sku = Sku("KB-001")
    private val repository = mockk<StockItemRepository>()
    private val service = StockService(repository)

    @Test
    fun `registration adds a new item`() {
        every { repository.add(any()) } answers { firstArg() }

        val registered = service.register(sku, 5)

        assertThat(registered).isEqualTo(StockItem.register(sku, 5))
        verify(exactly = 1) { repository.add(StockItem.register(sku, 5)) }
    }

    @Test
    fun `invalid registration does not add an item`() {
        assertThatThrownBy { service.register(sku, -1) }.isInstanceOf(InvalidStockException::class.java)

        verify { repository wasNot Called }
    }

    @Test
    fun `restock updates the item with the added quantity`() {
        every { repository.findBySkuForUpdate(sku) } returns StockItem.register(sku, 5)
        every { repository.update(any()) } answers { firstArg() }

        val restocked = service.restock(sku, 3)

        assertThat(restocked).isEqualTo(StockItem.restore(sku, 8))
        verify(exactly = 1) { repository.update(StockItem.restore(sku, 8)) }
    }

    @Test
    fun `invalid restock does not update the item`() {
        every { repository.findBySkuForUpdate(sku) } returns StockItem.register(sku, 5)

        assertThatThrownBy { service.restock(sku, 0) }.isInstanceOf(InvalidStockException::class.java)

        verify(exactly = 0) { repository.update(any()) }
    }

    @Test
    fun `restock of a missing item reports its sku and writes nothing`() {
        every { repository.findBySkuForUpdate(sku) } returns null

        assertThatThrownBy { service.restock(sku, 3) }
            .isInstanceOf(StockItemNotFoundException::class.java)
            .hasMessage("Stock item not found: KB-001")
        verify(exactly = 0) { repository.update(any()) }
    }

    @Test
    fun `get returns an item and reports a missing one with its sku`() {
        val item = StockItem.register(sku, 5)
        val missing = Sku("ZZ-404")
        every { repository.findBySku(sku) } returns item
        every { repository.findBySku(missing) } returns null

        assertThat(service.get(sku)).isEqualTo(item)
        assertThatThrownBy { service.get(missing) }
            .isInstanceOf(StockItemNotFoundException::class.java)
            .hasMessage("Stock item not found: ZZ-404")
    }
}
