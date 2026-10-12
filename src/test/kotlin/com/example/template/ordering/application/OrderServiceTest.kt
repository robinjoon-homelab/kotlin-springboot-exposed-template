package com.example.template.ordering.application

import com.example.template.ordering.application.port.input.OfferNotOnSaleException
import com.example.template.ordering.application.port.input.OrderNotFoundException
import com.example.template.ordering.application.service.OrderService
import com.example.template.ordering.domain.Sku
import com.example.template.ordering.domain.offer.Offer
import com.example.template.ordering.domain.offer.OfferId
import com.example.template.ordering.domain.offer.OfferStatus
import com.example.template.ordering.domain.order.InvalidOrderException
import com.example.template.ordering.domain.order.Order
import com.example.template.ordering.domain.order.OrderId
import com.example.template.ordering.domain.order.OrderLine
import com.example.template.ordering.domain.order.OrderStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class OrderServiceTest {
    private val now = Instant.parse("2026-10-10T09:00:00Z")
    private val operations = mutableListOf<String>()
    private val repository = FakeOrderRepository(operations)
    private val offers = FakeOfferRepository(operations)
    private val service = OrderService(repository, offers, Clock.fixed(now, ZoneOffset.UTC))
    private val keyboard = Offer.register(OfferId(UUID.randomUUID()), Sku("KB-001"))
    private val mouse = Offer.register(OfferId(UUID.randomUUID()), Sku("MS-002"))

    @Test
    fun `placement reads the offers once and then adds only the placed order`() {
        offers.seed(keyboard, mouse)
        val lines = listOf(OrderLine(Sku("MS-002"), 1), OrderLine(Sku("KB-001"), 3))

        val placed = service.place(lines)

        assertThat(placed).isEqualTo(Order.restore(placed.id, lines, OrderStatus.PLACED, now))
        assertThat(operations).containsExactly("offer.findBySkus[KB-001, MS-002]", "order.add")
        assertThat(repository.findById(placed.id)).isEqualTo(placed)
        assertThat(offers.findById(keyboard.id)).isEqualTo(keyboard)
        assertThat(offers.findById(mouse.id)).isEqualTo(mouse)
    }

    @Test
    fun `an invalid order reads no offers and adds nothing`() {
        offers.seed(keyboard)
        val line = OrderLine(Sku("KB-001"), 1)

        assertThatThrownBy { service.place(emptyList()) }.isInstanceOf(InvalidOrderException::class.java)
        assertThatThrownBy { service.place(listOf(line, line)) }.isInstanceOf(InvalidOrderException::class.java)
        assertThatThrownBy { service.place(List(11) { OrderLine(Sku("SKU-$it"), 1) }) }
            .isInstanceOf(InvalidOrderException::class.java)

        assertThat(operations).isEmpty()
    }

    @Test
    fun `a sku without an offer is not on sale and adds no order`() {
        offers.seed(keyboard)

        assertThatThrownBy { service.place(listOf(OrderLine(Sku("KB-001"), 1), OrderLine(Sku("ZZ-404"), 1))) }
            .isInstanceOf(OfferNotOnSaleException::class.java)
            .hasMessage("Not on sale: ZZ-404")

        assertThat(operations).containsExactly("offer.findBySkus[KB-001, ZZ-404]")
    }

    @Test
    fun `a discontinued offer is not on sale and stays unchanged`() {
        val discontinued = Offer.restore(mouse.id, mouse.sku, OfferStatus.DISCONTINUED)
        offers.seed(discontinued)

        assertThatThrownBy { service.place(listOf(OrderLine(Sku("MS-002"), 1))) }
            .isInstanceOf(OfferNotOnSaleException::class.java)
            .hasMessage("Not on sale: MS-002")

        assertThat(operations).containsExactly("offer.findBySkus[MS-002]")
        assertThat(offers.findById(mouse.id)).isEqualTo(discontinued)
    }

    @Test
    fun `every sku that is not on sale is reported together`() {
        offers.seed(keyboard, Offer.restore(mouse.id, mouse.sku, OfferStatus.DISCONTINUED))
        val lines = listOf(OrderLine(Sku("ZZ-404"), 1), OrderLine(Sku("KB-001"), 1), OrderLine(Sku("MS-002"), 1))

        assertThatThrownBy { service.place(lines) }
            .hasMessage("Not on sale: MS-002, ZZ-404")
            .isInstanceOfSatisfying(OfferNotOnSaleException::class.java) {
                assertThat(it.skus).containsExactlyInAnyOrder(Sku("MS-002"), Sku("ZZ-404"))
            }
    }

    @Test
    fun `each placement gets its own order identifier`() {
        offers.seed(keyboard)

        val first = service.place(listOf(OrderLine(Sku("KB-001"), 1)))
        val second = service.place(listOf(OrderLine(Sku("KB-001"), 1)))

        assertThat(first.id).isNotEqualTo(second.id)
    }

    @Test
    fun `get returns an order without locking or writing and reports a missing one`() {
        val existing = order(OrderStatus.PLACED)
        repository.seed(existing)
        val missingId = OrderId(UUID.randomUUID())

        assertThat(service.get(existing.id)).isEqualTo(existing)
        assertThatThrownBy { service.get(missingId) }
            .isInstanceOf(OrderNotFoundException::class.java)
            .hasMessage("Order not found: $missingId")
        assertThat(operations).isEmpty()
    }

    @Test
    fun `cancelling a placed order locks it and updates only the order`() {
        val placed = order(OrderStatus.PLACED)
        repository.seed(placed)
        offers.seed(keyboard)

        val cancelled = service.cancel(placed.id)

        assertThat(cancelled).isEqualTo(Order.restore(placed.id, placed.lines, OrderStatus.CANCELLED, placed.placedAt))
        assertThat(operations).containsExactly("order.findByIdForUpdate", "order.update")
        assertThat(repository.findById(placed.id)).isEqualTo(cancelled)
    }

    @Test
    fun `cancelling again returns the cancelled order without writing`() {
        val cancelled = order(OrderStatus.CANCELLED)
        repository.seed(cancelled)

        assertThat(service.cancel(cancelled.id)).isEqualTo(cancelled)
        assertThat(operations).containsExactly("order.findByIdForUpdate")
    }

    @Test
    fun `cancelling a missing order writes nothing`() {
        assertThatThrownBy { service.cancel(OrderId(UUID.randomUUID())) }.isInstanceOf(OrderNotFoundException::class.java)

        assertThat(operations).containsExactly("order.findByIdForUpdate")
    }

    private fun order(status: OrderStatus) =
        Order.restore(OrderId(UUID.randomUUID()), listOf(OrderLine(Sku("KB-001"), 3)), status, now.minusSeconds(60))
}
