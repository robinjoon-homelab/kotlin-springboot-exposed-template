package com.example.template.ordering.application

import com.example.template.ordering.application.port.input.OfferNotFoundException
import com.example.template.ordering.application.port.output.OfferRepository
import com.example.template.ordering.application.service.OfferService
import com.example.template.ordering.domain.Sku
import com.example.template.ordering.domain.offer.DuplicateOfferException
import com.example.template.ordering.domain.offer.Offer
import com.example.template.ordering.domain.offer.OfferId
import com.example.template.ordering.domain.offer.OfferStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

// The repository is a strict mock: a port call a test does not stub fails the test. Rejecting a stored duplicate SKU
// belongs to the repository and is verified against H2 in ExposedOfferRepositoryTest.
class OfferServiceTest {
    private val sku = Sku("BOOK-001")
    private val repository = mockk<OfferRepository>()
    private val service = OfferService(repository)

    @Test
    fun `registration adds an offer on sale under a new id`() {
        val added = mutableListOf<Offer>()
        every { repository.add(capture(added)) } answers { firstArg() }

        val first = service.register(sku)
        val second = service.register(Sku("BOOK-002"))

        assertThat(added).containsExactly(first, second)
        assertThat(first).isEqualTo(Offer.restore(first.id, sku, OfferStatus.ON_SALE))
        assertThat(first.id).isNotEqualTo(second.id)
    }

    @Test
    fun `a duplicate sku rejected by the repository reaches the caller`() {
        every { repository.add(any()) } throws DuplicateOfferException(sku, IllegalStateException("unique sku violated"))

        assertThatThrownBy { service.register(sku) }
            .isInstanceOf(DuplicateOfferException::class.java)
            .hasMessage("Offer already registered for SKU: BOOK-001")
    }

    @Test
    fun `get returns an offer and reports a missing one`() {
        val existing = Offer.register(OfferId(UUID.randomUUID()), sku)
        val missingId = OfferId(UUID.randomUUID())
        every { repository.findById(existing.id) } returns existing
        every { repository.findById(missingId) } returns null

        assertThat(service.get(existing.id)).isEqualTo(existing)
        assertThatThrownBy { service.get(missingId) }
            .isInstanceOf(OfferNotFoundException::class.java)
            .hasMessage("Offer not found: $missingId")
    }

    @Test
    fun `discontinuing an offer on sale updates it as discontinued`() {
        val onSale = Offer.register(OfferId(UUID.randomUUID()), sku)
        val expected = Offer.restore(onSale.id, sku, OfferStatus.DISCONTINUED)
        every { repository.findByIdForUpdate(onSale.id) } returns onSale
        every { repository.update(any()) } answers { firstArg() }

        val discontinued = service.discontinue(onSale.id)

        assertThat(discontinued).isEqualTo(expected)
        verify(exactly = 1) { repository.update(expected) }
    }

    @Test
    fun `discontinuing again returns the discontinued offer without writing`() {
        val discontinued = Offer.restore(OfferId(UUID.randomUUID()), sku, OfferStatus.DISCONTINUED)
        every { repository.findByIdForUpdate(discontinued.id) } returns discontinued

        assertThat(service.discontinue(discontinued.id)).isEqualTo(discontinued)
        verify(exactly = 0) { repository.update(any()) }
    }

    @Test
    fun `discontinuing a missing offer writes nothing`() {
        val missingId = OfferId(UUID.randomUUID())
        every { repository.findByIdForUpdate(missingId) } returns null

        assertThatThrownBy { service.discontinue(missingId) }
            .isInstanceOf(OfferNotFoundException::class.java)
            .hasMessage("Offer not found: $missingId")
        verify(exactly = 0) { repository.update(any()) }
    }
}
