package com.example.template.transaction

import com.example.template.application.port.input.TodoUseCase
import com.example.template.transaction.TransactionTestConfig.RecordingTodoRepository
import com.example.template.transaction.TransactionTestConfig.TransactionScenario
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import javax.sql.DataSource

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.datasource.url=jdbc:h2:mem:transaction-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"],
)
@ActiveProfiles("test")
@Import(TransactionTestConfig::class)
class ExposedSpringTransactionTest {
    @Autowired
    private lateinit var useCase: TodoUseCase

    @Autowired
    private lateinit var scenario: TransactionScenario

    @Autowired
    private lateinit var recordingRepository: RecordingTodoRepository

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var context: ApplicationContext

    @BeforeEach
    fun clearDatabase() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.executeUpdate("DELETE FROM todos") }
        }
        recordingRepository.clear()
    }

    @AfterEach
    fun transactionStateIsReleased() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
        assertThat(TransactionManager.currentOrNull()).isNull()
    }

    @Test
    fun `Exposed is the only Spring transaction manager and use cases are proxied`() {
        val managers = context.getBeansOfType(PlatformTransactionManager::class.java)

        assertThat(managers).containsOnlyKeys("springTransactionManager")
        assertThat(managers.values.single()).isInstanceOf(SpringTransactionManager::class.java)
        assertThat(AopUtils.isAopProxy(useCase)).isTrue()
        assertThat(AopUtils.isAopProxy(scenario)).isTrue()
    }

    @Test
    fun `a write use case commits before returning to its caller`() {
        useCase.create("Committed write")

        assertThat(storedTitles()).containsExactly("Committed write")
        assertWriteTransaction("save")
    }

    @Test
    fun `query use cases run inside read only Spring and Exposed transactions`() {
        val todo = useCase.create("Read the committed write")
        recordingRepository.clear()

        assertThat(useCase.get(todo.id)).isEqualTo(todo)
        assertThat(useCase.list()).containsExactly(todo)

        val observations = recordingRepository.observations()
        assertThat(observations.map { it.operation }).containsExactly("findById", "findAll")
        assertThat(observations).allSatisfy { observation ->
            assertThat(observation.active).isTrue()
            assertThat(observation.readOnly).isTrue()
            assertThat(observation.exposedReadOnly).isTrue()
        }
    }

    @Test
    fun `completion keeps its internal query and write in one writable transaction`() {
        val todo = useCase.create("Complete one transaction")
        recordingRepository.clear()

        val completed = useCase.complete(todo.id)

        assertThat(completed.completed).isTrue()
        assertWriteTransaction("findById", "save")
        assertThat(storedTitles()).containsExactly(todo.title)
    }

    @Test
    fun `required use cases join one outer transaction and commit both writes`() {
        scenario.createTwo()

        assertWriteTransaction("save", "save")
        assertThat(storedTitles()).containsExactly("First write", "Second write")
    }

    @Test
    fun `failure after two successful writes rolls back the whole outer transaction`() {
        assertThatThrownBy { scenario.createTwoThenFail() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Fail after both writes")

        assertWriteTransaction("save", "save")
        assertThat(storedTitles()).isEmpty()
    }

    private fun assertWriteTransaction(vararg operations: String) {
        val observations = recordingRepository.observations()
        assertThat(observations.map { it.operation }).containsExactly(*operations)
        assertThat(observations.map { it.transactionId }.distinct()).hasSize(1)
        assertThat(observations).allSatisfy { observation ->
            assertThat(observation.active).isTrue()
            assertThat(observation.readOnly).isFalse()
            assertThat(observation.exposedReadOnly).isFalse()
        }
    }

    // These connections are opened after the proxy returns, outside any test-managed transaction.
    private fun storedTitles(): List<String> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT title FROM todos ORDER BY title").use { result ->
                    generateSequence { if (result.next()) result.getString("title") else null }.toList()
                }
            }
        }
}
