package com.example.template.ordering.application

import com.example.template.ordering.application.port.output.OrderRepository
import com.example.template.ordering.domain.order.Order
import com.example.template.ordering.domain.order.OrderId

// Records locking reads and writes; plain reads are not recorded so assertions show the write path.
internal class FakeOrderRepository(
    private val operations: MutableList<String>,
) : OrderRepository {
    private val orders = linkedMapOf<OrderId, Order>()

    fun seed(vararg initialOrders: Order) {
        initialOrders.forEach { orders[it.id] = it }
    }

    override fun add(order: Order): Order {
        check(order.id !in orders) { "Duplicate order: ${order.id}" }
        operations += "order.add"
        orders[order.id] = order
        return order
    }

    override fun update(order: Order): Order {
        check(order.id in orders) { "Missing order: ${order.id}" }
        operations += "order.update"
        orders[order.id] = order
        return order
    }

    override fun findById(id: OrderId): Order? = orders[id]

    override fun findByIdForUpdate(id: OrderId): Order? {
        operations += "order.findByIdForUpdate"
        return orders[id]
    }
}
