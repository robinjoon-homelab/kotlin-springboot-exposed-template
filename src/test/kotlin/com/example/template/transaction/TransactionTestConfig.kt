package com.example.template.transaction

import com.example.template.application.port.input.TodoUseCase
import com.example.template.application.port.output.TodoRepository
import com.example.template.domain.Todo
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

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

        override fun save(todo: Todo): Todo {
            record("save")
            return delegate.save(todo)
        }

        override fun findById(id: UUID): Todo? {
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
