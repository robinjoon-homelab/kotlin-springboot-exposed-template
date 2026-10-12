package com.example.template.ordering.domain.offer

import com.example.template.ordering.domain.Sku
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class OfferTest {
    private val id = OfferId(UUID.fromString("0b6f1c0e-8a1d-4c55-9a3b-2f3e4d5c6b7a"))
    private val sku = Sku("BOOK-001")
    private val onSale = Offer.register(id, sku)

    @Test
    fun `registration starts on sale with its id and sku`() {
        assertThat(onSale).isEqualTo(Offer.restore(id, sku, OfferStatus.ON_SALE))
    }

    @Test
    fun `discontinuing keeps the id and sku and leaves the original on sale`() {
        val discontinued = onSale.discontinue()

        assertThat(discontinued).isEqualTo(Offer.restore(id, sku, OfferStatus.DISCONTINUED))
        assertThat(onSale.status).isEqualTo(OfferStatus.ON_SALE)
    }

    @Test
    fun `discontinuing again changes nothing because discontinuation is final`() {
        assertThat(Offer.restore(id, sku, OfferStatus.DISCONTINUED).discontinue()).isNull()
    }
}
