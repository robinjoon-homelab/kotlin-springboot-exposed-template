package com.example.template.ordering.adapter.inbound.web

import org.assertj.core.api.Assertions.assertThat
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
class OfferApiDocumentationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @BeforeEach
    fun clearOffers() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.executeUpdate("DELETE FROM offers") }
        }
    }

    @Test
    fun `register trims the sku and returns an offer on sale with its location`() {
        val result =
            register("""{"sku":"  BOOK-001  "}""")
                .andExpect(status().isCreated)
                .andExpect(header().string("Location", startsWith("/api/offers/")))
                .andExpect(jsonPath("$.sku").value("BOOK-001"))
                .andExpect(jsonPath("$.status").value("ON_SALE"))
                .andDo(
                    documented(
                        "offers-register",
                        requestFields(
                            fieldWithPath("sku").description(
                                "판매할 상품 SKU. 앞뒤 공백을 제거한 뒤 1~64자여야 하며 이미 등록된 SKU는 다시 등록할 수 없습니다. 재고는 확인하지 않습니다.",
                            ),
                        ),
                        responseHeaders(headerWithName("Location").description("등록된 Offer의 조회 경로")),
                        responseFields(*offerFields()),
                    ),
                ).andReturn()

        val id = offerId(result)
        assertThat(storedStatus(id)).isEqualTo("ON_SALE")
        mockMvc
            .perform(get("/api/offers/{id}", id))
            .andExpect(status().isOk)
            .andExpect(content().json(result.response.contentAsString, JsonCompareMode.STRICT))
    }

    @Test
    fun `get returns the stored offer`() {
        val id = registeredOfferId("BOOK-001")

        mockMvc
            .perform(get("/api/offers/{id}", id))
            .andExpect(status().isOk)
            .andExpect(content().json("""{"id":"$id","sku":"BOOK-001","status":"ON_SALE"}""", JsonCompareMode.STRICT))
            .andDo(documented("offers-get", offerIdParameter(), responseFields(*offerFields())))
    }

    @Test
    fun `discontinue ends the sale once and repeating it returns the same offer`() {
        val id = registeredOfferId("BOOK-001")

        val firstResponse =
            mockMvc
                .perform(patch("/api/offers/{id}/discontinue", id))
                .andExpect(status().isOk)
                .andExpect(content().json("""{"id":"$id","sku":"BOOK-001","status":"DISCONTINUED"}""", JsonCompareMode.STRICT))
                .andDo(documented("offers-discontinue", offerIdParameter(), responseFields(*offerFields())))
                .andReturn()
                .response.contentAsString

        mockMvc
            .perform(patch("/api/offers/{id}/discontinue", id))
            .andExpect(status().isOk)
            .andExpect(content().json(firstResponse, JsonCompareMode.STRICT))
        assertThat(storedStatus(id)).isEqualTo("DISCONTINUED")
    }

    @Test
    fun `duplicate sku returns a documented conflict and keeps the first offer`() {
        val first = registeredOfferId("BOOK-001")

        register("""{"sku":" BOOK-001 "}""")
            .andExpect(status().isConflict)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.title").value("Offer already registered"))
            .andDo(documented("offers-duplicate", responseFields(*problemFields())))
        assertThat(offerCount()).isEqualTo(1)
        assertThat(storedStatus(first)).isEqualTo("ON_SALE")
    }

    @Test
    fun `missing offers return a documented not found for get and discontinue`() {
        val missing = UUID.fromString("00000000-0000-0000-0000-000000000000")

        expectNotFound(get("/api/offers/{id}", missing))
            .andDo(documented("offers-not-found", offerIdParameter(), responseFields(*problemFields())))
        expectNotFound(patch("/api/offers/{id}/discontinue", missing))
        assertThat(offerCount()).isZero()
    }

    @Test
    fun `invalid registrations are rejected without persisting`() {
        register("""{"sku":"   "}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.title").value("Invalid offer"))
            .andDo(documented("offers-invalid", responseFields(*problemFields())))

        val invalidRequests = listOf("""{"sku":""}""", """{"sku":"${"A".repeat(65)}"}""", """{"sku":null}""", "{}", "{")
        invalidRequests.forEach { request ->
            register(request)
                .andExpect(status().isBadRequest)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        }
        assertThat(offerCount()).isZero()
    }

    @Test
    fun `malformed offer id returns a problem bad request`() {
        listOf(get("/api/offers/{id}", "BOOK-001"), patch("/api/offers/{id}/discontinue", "BOOK-001")).forEach { request ->
            mockMvc
                .perform(request)
                .andExpect(status().isBadRequest)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        }
    }

    private fun register(body: String): ResultActions =
        mockMvc.perform(post("/api/offers").contentType(MediaType.APPLICATION_JSON).content(body))

    private fun registeredOfferId(sku: String): UUID = offerId(register("""{"sku":"$sku"}""").andExpect(status().isCreated).andReturn())

    private fun offerId(result: MvcResult): UUID =
        UUID.fromString(checkNotNull(result.response.getHeader("Location")).substringAfterLast('/'))

    private fun expectNotFound(request: RequestBuilder): ResultActions =
        mockMvc
            .perform(request)
            .andExpect(status().isNotFound)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.title").value("Offer not found"))

    private fun storedStatus(id: UUID): String =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT status FROM offers WHERE id = ?").use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { result ->
                    assertThat(result.next()).isTrue()
                    result.getString("status")
                }
            }
        }

    private fun offerCount(): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM offers").use { result ->
                    assertThat(result.next()).isTrue()
                    result.getInt(1)
                }
            }
        }

    private fun documented(
        identifier: String,
        vararg snippets: Snippet,
    ) = document(identifier, preprocessRequest(prettyPrint()), preprocessResponse(prettyPrint()), *snippets)

    private fun offerIdParameter() = pathParameters(parameterWithName("id").description("Offer의 UUID"))

    private fun offerFields(): Array<FieldDescriptor> =
        arrayOf(
            fieldWithPath("id").description("Offer의 UUID"),
            fieldWithPath("sku").description("판매 대상 SKU. 등록 후 바뀌지 않습니다."),
            fieldWithPath("status").description("ON_SALE(판매 중) 또는 DISCONTINUED(판매 종료). 판매 종료는 되돌릴 수 없습니다."),
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
