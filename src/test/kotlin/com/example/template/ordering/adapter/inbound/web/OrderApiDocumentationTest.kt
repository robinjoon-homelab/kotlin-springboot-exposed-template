package com.example.template.ordering.adapter.inbound.web

import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.patch
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post
import org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessRequest
import org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessResponse
import org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint
import org.springframework.restdocs.payload.FieldDescriptor
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.pathParameters
import org.springframework.restdocs.snippet.Snippet
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.RequestBuilder
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureRestDocs(uriScheme = "https", uriHost = "api.example.com", uriPort = 443)
@ActiveProfiles("test")
class OrderApiDocumentationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    // order_lines refers to orders, so the lines go first.
    @BeforeEach
    fun clearOrdering() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                listOf("order_lines", "orders", "offers").forEach { statement.executeUpdate("DELETE FROM $it") }
            }
        }
    }

    @Test
    fun `place returns a placed order with its lines in request order and its location`() {
        registerOffer("MS-001")
        registerOffer("KB-001")

        val result =
            place(linesBody("MS-001" to 1, "  KB-001  " to 3))
                .andExpect(status().isCreated)
                .andExpect(header().string("Location", startsWith("/api/orders/")))
                .andExpect(jsonPath("$.lines[0].sku").value("MS-001"))
                .andExpect(jsonPath("$.lines[1].sku").value("KB-001"))
                .andExpect(jsonPath("$.lines[1].quantity").value(3))
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andDo(
                    documented(
                        "orders-place",
                        requestFields(
                            fieldWithPath("lines").description("주문 라인 1~10개. 앞뒤 공백을 제거한 SKU가 같은 라인을 둘 이상 넣을 수 없습니다."),
                            fieldWithPath("lines[].sku").description(
                                "판매 중인 Offer의 SKU. 앞뒤 공백을 제거한 뒤 1~64자여야 하며 재고는 확인하지 않습니다.",
                            ),
                            fieldWithPath("lines[].quantity").description("주문 수량, 1~100"),
                        ),
                        responseHeaders(headerWithName("Location").description("생성된 주문의 조회 경로")),
                        responseFields(*orderFields()),
                    ),
                ).andReturn()

        val id = idFromLocation(result)
        assertThat(storedLines(id)).containsExactly("MS-001" to 1, "KB-001" to 3)
        mockMvc
            .perform(get("/api/orders/{id}", id))
            .andExpect(status().isOk)
            .andExpect(content().json(result.response.contentAsString, JsonCompareMode.STRICT))
    }

    @Test
    fun `get returns the stored order`() {
        val id = placedOrderId()

        mockMvc
            .perform(get("/api/orders/{id}", id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(id.toString()))
            .andExpect(jsonPath("$.lines[0].sku").value("KB-001"))
            .andExpect(jsonPath("$.lines[0].quantity").value(3))
            .andExpect(jsonPath("$.status").value("PLACED"))
            .andDo(documented("orders-get", orderIdParameter(), responseFields(*orderFields())))
    }

    @Test
    fun `cancel changes a placed order once and repeating it returns the same order`() {
        val id = placedOrderId()

        val firstResponse =
            mockMvc
                .perform(patch("/api/orders/{id}/cancel", id))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andDo(documented("orders-cancel", orderIdParameter(), responseFields(*orderFields())))
                .andReturn()
                .response.contentAsString

        mockMvc
            .perform(patch("/api/orders/{id}/cancel", id))
            .andExpect(status().isOk)
            .andExpect(content().json(firstResponse, JsonCompareMode.STRICT))
        assertThat(storedStatus(id)).isEqualTo("CANCELLED")
    }

    // Line rules are checked before offers are read, so these fail as bad requests even for SKUs without an offer.
    @Test
    fun `invalid orders return a documented bad request without persisting`() {
        registerOffer("KB-001")
        place(linesBody("KB-001" to 0))
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("Invalid order"))
            .andDo(documented("orders-invalid", responseFields(*problemFields())))

        val invalidRequests =
            listOf(
                linesBody(),
                linesBody(*(1..11).map { "KB-$it" to 1 }.toTypedArray()),
                linesBody("KB-001" to 1, " KB-001 " to 2),
                linesBody("KB-001" to 101),
                linesBody("   " to 1),
                linesBody("A".repeat(65) to 1),
                """{"lines":[null]}""",
                """{"lines":null}""",
                """{"lines":[{"quantity":1}]}""",
                """{"lines":[{"sku":"KB-001"}]}""",
                """{"lines":[{"sku":null,"quantity":1}]}""",
                """{"lines":[{"sku":"KB-001","quantity":null}]}""",
                """{"lines":[{"sku":"KB-001","quantity":"many"}]}""",
                """{"sku":"KB-001","quantity":1}""",
                "{",
            )
        invalidRequests.forEach { request ->
            place(request)
                .andExpect(status().isBadRequest)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        }
        assertThat(rowCount("orders")).isZero()
        assertThat(rowCount("order_lines")).isZero()
    }

    @Test
    fun `unknown or discontinued skus return a documented conflict without persisting`() {
        registerOffer("KB-001")
        discontinueOffer(registerOffer("MS-001"))

        place(linesBody("KB-001" to 1, "MS-001" to 1, "KB-404" to 1))
            .andExpect(status().isConflict)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.title").value("Offer not on sale"))
            .andExpect(
                jsonPath("$.detail").value(allOf(containsString("KB-404"), containsString("MS-001"), not(containsString("KB-001")))),
            ).andDo(documented("orders-not-on-sale", responseFields(*problemFields())))
        assertThat(rowCount("orders")).isZero()
        assertThat(rowCount("order_lines")).isZero()
    }

    @Test
    fun `discontinuing an offer keeps placed orders and blocks new ones`() {
        val offerId = registerOffer("KB-001")
        val placed = place(linesBody("KB-001" to 3)).andExpect(status().isCreated).andReturn()
        val orderId = idFromLocation(placed)

        discontinueOffer(offerId)

        mockMvc
            .perform(get("/api/orders/{id}", orderId))
            .andExpect(status().isOk)
            .andExpect(content().json(placed.response.contentAsString, JsonCompareMode.STRICT))
        place(linesBody("KB-001" to 1)).andExpect(status().isConflict)
        assertThat(rowCount("orders")).isEqualTo(1)
    }

    @Test
    fun `missing orders return a documented not found for get and cancel`() {
        val missing = UUID.fromString("00000000-0000-0000-0000-000000000000")

        expectNotFound(get("/api/orders/{id}", missing))
            .andDo(documented("orders-not-found", orderIdParameter(), responseFields(*problemFields())))
        expectNotFound(patch("/api/orders/{id}/cancel", missing))
        assertThat(rowCount("orders")).isZero()
    }

    @Test
    fun `malformed order id returns a problem bad request`() {
        listOf(get("/api/orders/{id}", "invalid-id"), patch("/api/orders/{id}/cancel", "invalid-id")).forEach { request ->
            mockMvc
                .perform(request)
                .andExpect(status().isBadRequest)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        }
    }

    private fun place(body: String): ResultActions =
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body))

    private fun linesBody(vararg lines: Pair<String, Int>): String =
        lines.joinToString(",", """{"lines":[""", "]}") { (sku, quantity) -> """{"sku":"$sku","quantity":$quantity}""" }

    private fun registerOffer(sku: String): UUID =
        idFromLocation(
            mockMvc
                .perform(post("/api/offers").contentType(MediaType.APPLICATION_JSON).content("""{"sku":"$sku"}"""))
                .andExpect(status().isCreated)
                .andReturn(),
        )

    private fun discontinueOffer(id: UUID) {
        mockMvc.perform(patch("/api/offers/{id}/discontinue", id)).andExpect(status().isOk)
    }

    private fun placedOrderId(): UUID {
        registerOffer("KB-001")
        return idFromLocation(place(linesBody("KB-001" to 3)).andExpect(status().isCreated).andReturn())
    }

    private fun idFromLocation(result: MvcResult): UUID =
        UUID.fromString(checkNotNull(result.response.getHeader("Location")).substringAfterLast('/'))

    private fun expectNotFound(request: RequestBuilder): ResultActions =
        mockMvc
            .perform(request)
            .andExpect(status().isNotFound)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.title").value("Order not found"))

    private fun storedStatus(id: UUID): String =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT status FROM orders WHERE id = ?").use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { result ->
                    assertThat(result.next()).isTrue()
                    result.getString("status")
                }
            }
        }

    private fun storedLines(id: UUID): List<Pair<String, Int>> =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement("SELECT sku, quantity FROM order_lines WHERE order_id = ? ORDER BY line_position")
                .use { statement ->
                    statement.setObject(1, id)
                    statement.executeQuery().use { result ->
                        buildList {
                            while (result.next()) {
                                add(result.getString("sku") to result.getInt("quantity"))
                            }
                        }
                    }
                }
        }

    private fun rowCount(table: String): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $table").use { result ->
                    assertThat(result.next()).isTrue()
                    result.getInt(1)
                }
            }
        }

    private fun documented(
        identifier: String,
        vararg snippets: Snippet,
    ) = document(identifier, preprocessRequest(prettyPrint()), preprocessResponse(prettyPrint()), *snippets)

    private fun orderIdParameter() = pathParameters(parameterWithName("id").description("주문의 UUID"))

    private fun orderFields(): Array<FieldDescriptor> =
        arrayOf(
            fieldWithPath("id").description("주문의 UUID"),
            fieldWithPath("lines").description("요청한 순서대로 저장된 주문 라인. 접수 후 바뀌지 않습니다."),
            fieldWithPath("lines[].sku").description("주문한 상품 SKU"),
            fieldWithPath("lines[].quantity").description("주문 수량"),
            fieldWithPath("status").description("PLACED(접수됨) 또는 CANCELLED(취소됨). 접수 직후에는 PLACED입니다."),
            fieldWithPath("placedAt").description("접수 시각, ISO 8601 UTC"),
        )

    private fun problemFields(): Array<FieldDescriptor> =
        arrayOf(
            fieldWithPath("type")
                .type(JsonFieldType.STRING)
                .optional()
                .description("오류 유형 URI. 생략되면 RFC 9457 기본값 about:blank입니다."),
            fieldWithPath("title").description("오류 요약"),
            fieldWithPath("status").description("HTTP 상태 코드"),
            fieldWithPath("detail").description("오류의 구체적인 원인"),
            fieldWithPath("instance").description("오류가 발생한 요청 경로"),
        )
}
