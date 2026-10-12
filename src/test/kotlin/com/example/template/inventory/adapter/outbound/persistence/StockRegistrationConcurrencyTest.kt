package com.example.template.inventory.adapter.outbound.persistence

import com.example.template.inventory.application.port.input.StockUseCase
import com.example.template.inventory.application.port.output.StockItemRepository
import com.example.template.inventory.domain.stock.DuplicateSkuException
import com.example.template.inventory.domain.stock.Sku
import com.example.template.inventory.domain.stock.StockItem
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.datasource.url=jdbc:h2:mem:stock-registration-race;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"],
)
@ActiveProfiles("test")
@Import(StockRegistrationConcurrencyTest.RegistrationTestConfig::class)
class StockRegistrationConcurrencyTest {
    @Autowired
    private lateinit var stock: StockUseCase

    @Autowired
    private lateinit var probe: RegistrationProbe

    @Test
    fun `concurrent registration waits for the first commit and reports a duplicate`() {
        val sku = Sku("REGISTRATION-RACE")
        Executors.newFixedThreadPool(2).use { executor ->
            try {
                val first = executor.submit(Callable { stock.register(sku, 1) })
                assertThat(probe.firstInserted.await(5, TimeUnit.SECONDS)).isTrue()
                val second = executor.submit(Callable { runCatching { stock.register(sku, 2) } })
                assertThat(probe.secondEntered.await(5, TimeUnit.SECONDS)).isTrue()
                assertThatThrownBy { second.get(200, TimeUnit.MILLISECONDS) }.isInstanceOf(TimeoutException::class.java)

                probe.releaseFirst.countDown()

                assertThat(first.get(5, TimeUnit.SECONDS).onHand).isEqualTo(1)
                assertThat(second.get(5, TimeUnit.SECONDS).exceptionOrNull()).isInstanceOf(DuplicateSkuException::class.java)
            } finally {
                probe.releaseFirst.countDown()
            }
        }
        assertThat(stock.get(sku).onHand).isEqualTo(1)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class RegistrationTestConfig {
        @Bean
        @Primary
        fun registrationProbe(
            @Qualifier("stockItemRepository") delegate: StockItemRepository,
        ): RegistrationProbe = RegistrationProbe(delegate)
    }

    class RegistrationProbe(
        private val delegate: StockItemRepository,
    ) : StockItemRepository by delegate {
        val firstInserted = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        private val calls = AtomicInteger()

        override fun add(item: StockItem): StockItem {
            val attempt = calls.incrementAndGet()
            if (attempt == 2) secondEntered.countDown()
            val stored = delegate.add(item)
            if (attempt == 1) {
                firstInserted.countDown()
                check(releaseFirst.await(5, TimeUnit.SECONDS)) { "Timed out waiting to commit the first registration" }
            }
            return stored
        }
    }
}
