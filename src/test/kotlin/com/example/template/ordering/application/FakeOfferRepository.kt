package com.example.template.ordering.application

import com.example.template.ordering.application.port.output.OfferRepository
import com.example.template.ordering.domain.Sku
import com.example.template.ordering.domain.offer.DuplicateOfferException
import com.example.template.ordering.domain.offer.Offer
import com.example.template.ordering.domain.offer.OfferId

// Records locking reads, writes, and SKU lookups with their sorted SKUs; reads by id are not recorded so
// assertions show the write path and the single eligibility read of order placement.
internal class FakeOfferRepository(
    private val operations: MutableList<String>,
) : OfferRepository {
    private val offers = linkedMapOf<OfferId, Offer>()

    fun seed(vararg initialOffers: Offer) {
        initialOffers.forEach { offers[it.id] = it }
    }

    // Mirrors the unique SKU key of the real table.
    override fun add(offer: Offer): Offer {
        if (offers.values.any { it.sku == offer.sku }) {
            throw DuplicateOfferException(offer.sku, IllegalStateException("unique sku violated"))
        }
        operations += "offer.add"
        offers[offer.id] = offer
        return offer
    }

    override fun update(offer: Offer): Offer {
        check(offer.id in offers) { "Missing offer: ${offer.id}" }
        operations += "offer.update"
        offers[offer.id] = offer
        return offer
    }

    override fun findById(id: OfferId): Offer? = offers[id]

    override fun findByIdForUpdate(id: OfferId): Offer? {
        operations += "offer.findByIdForUpdate"
        return offers[id]
    }

    override fun findBySkus(skus: Set<Sku>): List<Offer> {
        operations += "offer.findBySkus${skus.map { it.value }.sorted()}"
        return offers.values.filter { it.sku in skus }
    }
}
