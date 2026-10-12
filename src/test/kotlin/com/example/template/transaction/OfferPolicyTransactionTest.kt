package com.example.template.transaction

import com.example.template.ordering.application.port.input.OfferUseCase
import com.example.template.ordering.application.port.input.OrderUseCase
import com.example.template.ordering.application.port.output.OfferRepository
import com.example.template.ordering.domain.Sku
import com.example.template.ordering.domain.offer.Offer
import com.example.template.ordering.domain.offer.OfferStatus
import com.example.template.ordering.domain.order.OrderLine
import com.example.template.ordering.domain.order.OrderStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.datasource.url=jdbc:h2:mem:offer-policy-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"],
)
@ActiveProfiles("test")
@Import(OfferPolicyTransactionTest.PolicyTestConfig::class)
class OfferPolicyTransactionTest {
    @Autowired
    private lateinit var orders: OrderUseCase

    @Autowired
    private lateinit var offers: OfferUseCase

    @Autowired
    private lateinit var probe: OfferReadProbe

    @Test
    fun `discontinuation after the unlocked eligibility read does not invalidate that order`() {
        val sku = Sku.of("READ-SNAPSHOT")
        val offer = offers.register(sku)

        Executors.newSingleThreadExecutor().use { executor ->
            try {
                val placement = executor.submit(Callable { orders.place(listOf(OrderLine(sku, 2))) })
                assertThat(probe.readCompleted.await(5, TimeUnit.SECONDS)).isTrue()

                assertThat(offers.discontinue(offer.id).status).isEqualTo(OfferStatus.DISCONTINUED)
                probe.releaseRead.countDown()

                val order = placement.get(5, TimeUnit.SECONDS)
                assertThat(orders.get(order.id).status).isEqualTo(OrderStatus.PLACED)
                assertThat(offers.get(offer.id).status).isEqualTo(OfferStatus.DISCONTINUED)
            } finally {
                probe.releaseRead.countDown()
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class PolicyTestConfig {
        @Bean
        @Primary
        fun offerReadProbe(
            @Qualifier("offerRepository") delegate: OfferRepository,
        ): OfferReadProbe = OfferReadProbe(delegate)
    }

    // Pause after the real query so another transaction can discontinue the offer before Order is saved.
    class OfferReadProbe(
        private val delegate: OfferRepository,
    ) : OfferRepository by delegate {
        val readCompleted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)

        override fun findBySkus(skus: Set<Sku>): List<Offer> {
            val snapshot = delegate.findBySkus(skus)
            readCompleted.countDown()
            check(releaseRead.await(10, TimeUnit.SECONDS)) { "Timed out waiting to discontinue the offer" }
            return snapshot
        }
    }
}
