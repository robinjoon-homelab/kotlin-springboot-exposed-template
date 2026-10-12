package com.example.template.ordering.adapter.outbound.persistence

import com.example.template.ordering.application.port.output.OfferRepository
import com.example.template.ordering.domain.Sku
import com.example.template.ordering.domain.offer.DuplicateOfferException
import com.example.template.ordering.domain.offer.Offer
import com.example.template.ordering.domain.offer.OfferId
import com.example.template.ordering.domain.offer.OfferStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.sql.SQLException
import java.util.UUID

// Each test rolls back its transaction. No query follows an expected SQL failure because PostgreSQL aborts
// the transaction after a constraint violation.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.datasource.url=jdbc:h2:mem:offer-repository-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"],
)
@ActiveProfiles("test")
@Transactional
class ExposedOfferRepositoryTest {
    @Autowired
    private lateinit var repository: OfferRepository

    private val sku = Sku("KB-001")

    @Test
    fun `every status round trips and a missing id reads as null`() {
        OfferStatus.entries.forEach { status ->
            val offer = Offer.restore(newId(), Sku("KB-$status"), status)

            assertThat(repository.add(offer)).isEqualTo(offer)
            assertThat(repository.findById(offer.id)).isEqualTo(offer)
        }
        assertThat(repository.findById(newId())).isNull()
    }

    @Test
    fun `adding a registered sku under another id fails as a duplicate and keeps the cause`() {
        repository.add(Offer.register(newId(), sku))

        assertThatThrownBy { repository.add(Offer.register(newId(), sku)) }
            .isInstanceOf(DuplicateOfferException::class.java)
            .hasMessage("Offer already registered for SKU: KB-001")
            .hasCauseInstanceOf(SQLException::class.java)
    }

    @Test
    fun `update writes the status and keeps the registered sku`() {
        val stored = repository.add(Offer.register(newId(), sku))
        val withOtherSku = Offer.restore(stored.id, Sku("KB-999"), OfferStatus.DISCONTINUED)

        assertThat(repository.update(withOtherSku)).isEqualTo(Offer.restore(stored.id, sku, OfferStatus.DISCONTINUED))
    }

    @Test
    fun `updating a missing offer fails instead of inserting it`() {
        val missing = Offer.register(newId(), sku)

        assertThatIllegalStateException().isThrownBy { repository.update(missing) }
        assertThat(repository.findById(missing.id)).isNull()
    }

    @Test
    fun `locking read returns the stored offer or null`() {
        val stored = repository.add(Offer.register(newId(), sku))

        assertThat(repository.findByIdForUpdate(stored.id)).isEqualTo(stored)
        assertThat(repository.findByIdForUpdate(newId())).isNull()
    }

    @Test
    fun `findBySkus returns the offers of the requested skus in any status`() {
        val onSale = repository.add(Offer.register(newId(), Sku("KB-001")))
        val discontinued = repository.add(Offer.restore(newId(), Sku("KB-002"), OfferStatus.DISCONTINUED))
        repository.add(Offer.register(newId(), Sku("KB-003")))

        assertThat(repository.findBySkus(setOf(Sku("KB-001"), Sku("KB-002"), Sku("KB-404"))))
            .containsExactlyInAnyOrder(onSale, discontinued)
        assertThat(repository.findBySkus(emptySet())).isEmpty()
    }

    private fun newId() = OfferId(UUID.randomUUID())
}
