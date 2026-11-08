package com.example.template.adapter.inbound.web

import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.resource.NoResourceFoundException

private const val API_PATH_PREFIX = "/api/"

// Declining via `produces` is not an option: text/html is compatible with */*, so API clients would receive HTML.
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class UiNotFoundExceptionHandler {
    @ExceptionHandler(NoResourceFoundException::class)
    fun handle(
        exception: NoResourceFoundException,
        request: HttpServletRequest,
    ): Any =
        if (acceptsHtmlExplicitly(request) && !request.requestURI.startsWith(API_PATH_PREFIX)) {
            ModelAndView("error/404", HttpStatus.NOT_FOUND)
        } else {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(exception.body)
        }

    private fun acceptsHtmlExplicitly(request: HttpServletRequest): Boolean =
        request.getHeaders("Accept").toList().any { header ->
            MediaType.parseMediaTypes(header).any { it.equalsTypeAndSubtype(MediaType.TEXT_HTML) }
        }
}
