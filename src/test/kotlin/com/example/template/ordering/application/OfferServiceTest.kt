package com.example.template.ordering.application

import com.example.template.ordering.application.port.input.OfferNotFoundException
import com.example.template.ordering.application.service.OfferService
import com.example.template.ordering.domain.Sku
import com.example.template.ordering.domain.offer.DuplicateOfferException
import com.example.template.ordering.domain.offer.Offer
import com.example.template.ordering.domain.offer.OfferId
import com.example.template.ordering.domain.offer.OfferStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class OfferServiceTest {
    private val sku = Sku("BOOK-001")
    private val operations = mutableListOf<String>()
    private val repository = FakeOfferRepository(operations)
    private val service = OfferService(repository)

    @Test
    fun `registration adds an offer on sale under a new id`() {
        val registered = service.register(sku)

        assertThat(registered).isEqualTo(Offer.restore(registered.id, sku, OfferStatus.ON_SALE))
        assertThat(operations).containsExactly("offer.add")
        assertThat(repository.findById(registered.id)).isEqualTo(registered)
    }

    @Test
    fun `each registration gets its own offer identifier`() {
        val first = service.register(sku)
        val second = service.register(Sku("BOOK-002"))

        assertThat(first.id).isNotEqualTo(second.id)
    }

    @Test
    fun `a sku that already has an offer is rejected and the first offer stays`() {
        val first = service.register(sku)

        assertThatThrownBy { service.register(sku) }
            .isInstanceOf(DuplicateOfferException::class.java)
            .hasMessage("Offer already registered for SKU: BOOK-001")
        assertThat(operations).containsExactly("offer.add")
        assertThat(repository.findById(first.id)).isEqualTo(first)
    }

    @Test
    fun `get returns an offer without locking or writing and reports a missing one`() {
        val existing = Offer.register(OfferId(UUID.randomUUID()), sku)
        repository.seed(existing)
        val missingId = OfferId(UUID.randomUUID())

        assertThat(service.get(existing.id)).isEqualTo(existing)
        assertThatThrownBy { service.get(missingId) }
            .isInstanceOf(OfferNotFoundException::class.java)
            .hasMessage("Offer not found: $missingId")
        assertThat(operations).isEmpty()
    }

    @Test
    fun `discontinuing an offer on sale locks it and updates it`() {
        val onSale = Offer.register(OfferId(UUID.randomUUID()), sku)
        repository.seed(onSale)

        val discontinued = service.discontinue(onSale.id)

        assertThat(discontinued).isEqualTo(Offer.restore(onSale.id, sku, OfferStatus.DISCONTINUED))
        assertThat(operations).containsExactly("offer.findByIdForUpdate", "offer.update")
        assertThat(repository.findById(onSale.id)).isEqualTo(discontinued)
    }

    @Test
    fun `discontinuing again returns the discontinued offer without writing`() {
        val discontinued = Offer.restore(OfferId(UUID.randomUUID()), sku, OfferStatus.DISCONTINUED)
        repository.seed(discontinued)

        assertThat(service.discontinue(discontinued.id)).isEqualTo(discontinued)
        assertThat(operations).containsExactly("offer.findByIdForUpdate")
    }

    @Test
    fun `discontinuing a missing offer writes nothing`() {
        assertThatThrownBy { service.discontinue(OfferId(UUID.randomUUID())) }
            .isInstanceOf(OfferNotFoundException::class.java)

        assertThat(operations).containsExactly("offer.findByIdForUpdate")
    }
}
