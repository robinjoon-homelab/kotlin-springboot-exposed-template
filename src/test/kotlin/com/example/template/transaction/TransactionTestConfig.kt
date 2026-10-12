package com.example.template.transaction

import com.example.template.todo.application.port.input.TodoUseCase
import com.example.template.todo.application.port.output.TodoRepository
import com.example.template.todo.domain.todo.Todo
import com.example.template.todo.domain.todo.TodoId
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager

@TestConfiguration(proxyBeanMethods = false)
class TransactionTestConfig {
    @Bean
    @Primary
    fun recordingTodoRepository(
        @Qualifier("todoRepository") delegate: TodoRepository,
    ): RecordingTodoRepository = RecordingTodoRepository(delegate)

    @Bean
    fun transactionScenario(useCase: TodoUseCase): TransactionScenario = TransactionScenario(useCase)

    @Transactional
    class TransactionScenario(
        private val useCase: TodoUseCase,
    ) {
        fun createTwo() {
            useCase.create("First write")
            useCase.create("Second write")
        }

        fun createTwoThenFail() {
            useCase.create("First write")
            useCase.create("Second write")
            error("Fail after both writes")
        }
    }

    class RecordingTodoRepository(
        private val delegate: TodoRepository,
    ) : TodoRepository {
        private val recorded = mutableListOf<TransactionObservation>()

        override fun add(todo: Todo): Todo {
            record("add")
            return delegate.add(todo)
        }

        override fun update(todo: Todo): Todo {
            record("update")
            return delegate.update(todo)
        }

        override fun findById(id: TodoId): Todo? {
            record("findById")
            return delegate.findById(id)
        }

        override fun findAll(): List<Todo> {
            record("findAll")
            return delegate.findAll()
        }

        fun observations(): List<TransactionObservation> = recorded.toList()

        fun clear() = recorded.clear()

        private fun record(operation: String) {
            val transaction = TransactionManager.current()
            recorded +=
                TransactionObservation(
                    operation = operation,
                    active = TransactionSynchronizationManager.isActualTransactionActive(),
                    readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                    exposedReadOnly = transaction.readOnly,
                    transactionId = transaction.transactionId,
                )
        }
    }

    data class TransactionObservation(
        val operation: String,
        val active: Boolean,
        val readOnly: Boolean,
        val exposedReadOnly: Boolean,
        val transactionId: String,
    )
}
