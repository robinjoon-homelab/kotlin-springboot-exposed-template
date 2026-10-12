package com.example.template.transaction

import com.example.template.inventory.application.port.output.StockItemRepository
import com.example.template.inventory.domain.stock.StockItem
import com.example.template.ordering.application.port.output.OrderRepository
import com.example.template.ordering.domain.order.Order
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.concurrent.atomic.AtomicBoolean

@TestConfiguration(proxyBeanMethods = false)
class AggregateTransactionTestConfig {
    @Bean
    @Primary
    fun orderWriteProbe(
        @Qualifier("orderRepository") delegate: OrderRepository,
    ): OrderWriteProbe = OrderWriteProbe(delegate)

    @Bean
    @Primary
    fun stockWriteProbe(
        @Qualifier("stockItemRepository") delegate: StockItemRepository,
    ): StockWriteProbe = StockWriteProbe(delegate)
}

// Fail after the real SQL update so a fresh connection can distinguish rollback from a skipped write.
class OrderWriteProbe(
    private val delegate: OrderRepository,
) : OrderRepository by delegate {
    private val failNext = AtomicBoolean(false)

    fun failNextUpdate() = failNext.set(true)

    fun reset() = failNext.set(false)

    override fun update(order: Order): Order {
        val saved = delegate.update(order)
        if (failNext.compareAndSet(true, false)) throw InjectedAggregateWriteFailure()
        return saved
    }
}

class StockWriteProbe(
    private val delegate: StockItemRepository,
) : StockItemRepository by delegate {
    private val failNext = AtomicBoolean(false)

    fun failNextUpdate() = failNext.set(true)

    fun reset() = failNext.set(false)

    override fun update(item: StockItem): StockItem {
        val saved = delegate.update(item)
        if (failNext.compareAndSet(true, false)) throw InjectedAggregateWriteFailure()
        return saved
    }
}

class InjectedAggregateWriteFailure : RuntimeException("Injected failure after aggregate update")
