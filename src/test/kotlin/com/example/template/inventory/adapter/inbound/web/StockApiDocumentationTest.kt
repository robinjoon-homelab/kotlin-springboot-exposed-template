package com.example.template.inventory.adapter.inbound.web

import org.assertj.core.api.Assertions.assertThat
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
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureRestDocs(uriScheme = "https", uriHost = "api.example.com", uriPort = 443)
@ActiveProfiles("test")
class StockApiDocumentationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @BeforeEach
    fun clearDatabase() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.executeUpdate("DELETE FROM stock_items") }
        }
    }

    @Test
    fun `register returns a location and persists the item`() {
        val result =
            register("""{"sku":"KB-001","onHand":5}""")
                .andExpect(status().isCreated)
                .andExpect(header().string("Location", "/api/inventory/items/KB-001"))
                .andExpect(content().json("""{"sku":"KB-001","onHand":5}""", JsonCompareMode.STRICT))
                .andDo(
                    documented(
                        "inventory-items-register",
                        requestFields(
                            fieldWithPath("sku").description(
                                "1~64자 SKU. 영문자·숫자로 시작하고 영문자·숫자·`.`·`_`·`-`만 사용합니다. 공백을 제거하지 않습니다.",
                            ),
                            fieldWithPath("onHand").description("0 이상의 보유 수량"),
                        ),
                        responseHeaders(headerWithName("Location").description("등록된 재고 품목의 조회 경로")),
                        responseFields(*stockItemFields()),
                    ),
                ).andReturn()

        assertStoredOnHand("KB-001", 5)
        mockMvc
            .perform(get("/api/inventory/items/{sku}", "KB-001"))
            .andExpect(status().isOk)
            .andExpect(content().json(result.response.contentAsString, JsonCompareMode.STRICT))
    }

    @Test
    fun `get returns the sku and onHand`() {
        register("""{"sku":"KB-001","onHand":5}""").andExpect(status().isCreated)

        mockMvc
            .perform(get("/api/inventory/items/{sku}", "KB-001"))
            .andExpect(status().isOk)
            .andExpect(content().json("""{"sku":"KB-001","onHand":5}""", JsonCompareMode.STRICT))
            .andDo(documented("inventory-items-get", skuParameter(), responseFields(*stockItemFields())))
    }

    @Test
    fun `restock adds to onHand on every request`() {
        register("""{"sku":"KB-001","onHand":5}""").andExpect(status().isCreated)

        restock("KB-001", """{"quantity":3}""")
            .andExpect(status().isOk)
            .andExpect(content().json("""{"sku":"KB-001","onHand":8}""", JsonCompareMode.STRICT))
            .andDo(
                documented(
                    "inventory-items-restock",
                    skuParameter(),
                    requestFields(fieldWithPath("quantity").description("추가할 수량. 1 이상이며 합계가 2147483647을 넘을 수 없습니다.")),
                    responseFields(*stockItemFields()),
                ),
            )
        restock("KB-001", """{"quantity":3}""").andExpect(status().isOk).andExpect(jsonPath("$.onHand").value(11))
        assertStoredOnHand("KB-001", 11)
    }

    @Test
    fun `invalid sku returns a documented bad request`() {
        register("""{"sku":"KB 001","onHand":5}""")
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.title").value("Invalid stock request"))
            .andDo(documented("inventory-items-invalid", responseFields(*problemFields())))
        assertItemCount(0)
    }

    @Test
    fun `invalid registrations are rejected without persisting`() {
        val invalidRequests =
            listOf(
                """{"sku":"KB-001","onHand":-1}""",
                """{"sku":" KB-001","onHand":5}""",
                """{"sku":"${"A".repeat(65)}","onHand":5}""",
                """{"sku":"","onHand":5}""",
                """{"onHand":5}""",
                """{"sku":"KB-001"}""",
                """{"sku":null,"onHand":5}""",
                """{"sku":"KB-001","onHand":null}""",
                "{",
            )

        invalidRequests.forEach { request ->
            register(request)
                .andExpect(status().isBadRequest)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        }
        assertItemCount(0)
    }

    @Test
    fun `invalid restocks are rejected without changing onHand`() {
        register("""{"sku":"KB-001","onHand":5}""").andExpect(status().isCreated)

        listOf("""{"quantity":0}""", """{"quantity":-1}""", """{"quantity":2147483643}""", "{}", "{").forEach { request ->
            restock("KB-001", request).andExpect(status().isBadRequest)
        }
        assertStoredOnHand("KB-001", 5)
    }

    @Test
    fun `largest onHand is accepted`() {
        register("""{"sku":"KB-001","onHand":2147483642}""").andExpect(status().isCreated)

        restock("KB-001", """{"quantity":5}""").andExpect(status().isOk).andExpect(jsonPath("$.onHand").value(Int.MAX_VALUE))
    }

    @Test
    fun `missing item returns a documented not found`() {
        mockMvc
            .perform(get("/api/inventory/items/{sku}", "KB-404"))
            .andExpect(status().isNotFound)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.title").value("Stock item not found"))
            .andDo(documented("inventory-items-not-found", skuParameter(), responseFields(*problemFields())))
    }

    @Test
    fun `restock of a missing item does not create it`() {
        restock("KB-404", """{"quantity":1}""").andExpect(status().isNotFound).andExpect(jsonPath("$.status").value(404))

        assertItemCount(0)
    }

    @Test
    fun `invalid sku in the path returns bad request`() {
        mockMvc.perform(get("/api/inventory/items/{sku}", "KB 001")).andExpect(status().isBadRequest)
        restock("-KB", """{"quantity":1}""").andExpect(status().isBadRequest)
    }

    @Test
    fun `duplicate sku returns a documented conflict and keeps the first item`() {
        register("""{"sku":"KB-001","onHand":5}""").andExpect(status().isCreated)

        register("""{"sku":"KB-001","onHand":9}""")
            .andExpect(status().isConflict)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.title").value("SKU already registered"))
            .andDo(documented("inventory-items-duplicate", responseFields(*problemFields())))
        assertStoredOnHand("KB-001", 5)
        assertItemCount(1)
    }

    private fun register(body: String): ResultActions =
        mockMvc.perform(post("/api/inventory/items").contentType(MediaType.APPLICATION_JSON).content(body))

    private fun restock(
        sku: String,
        body: String,
    ): ResultActions =
        mockMvc.perform(post("/api/inventory/items/{sku}/restock", sku).contentType(MediaType.APPLICATION_JSON).content(body))

    private fun assertStoredOnHand(
        sku: String,
        expected: Int,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT on_hand FROM stock_items WHERE sku = ?").use { statement ->
                statement.setString(1, sku)
                statement.executeQuery().use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getInt("on_hand")).isEqualTo(expected)
                    assertThat(result.next()).isFalse()
                }
            }
        }
    }

    private fun assertItemCount(expected: Int) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM stock_items").use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getInt(1)).isEqualTo(expected)
                }
            }
        }
    }

    private fun documented(
        identifier: String,
        vararg snippets: Snippet,
    ) = document(identifier, preprocessRequest(prettyPrint()), preprocessResponse(prettyPrint()), *snippets)

    private fun skuParameter() = pathParameters(parameterWithName("sku").description("재고 품목의 SKU"))

    private fun stockItemFields(): Array<FieldDescriptor> =
        arrayOf(
            fieldWithPath("sku").description("재고 품목의 SKU"),
            fieldWithPath("onHand").description("보유 수량"),
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
