package com.example.template.todo.adapter.inbound.web

import com.example.template.todo.application.port.input.TodoNotFoundException
import com.example.template.todo.domain.todo.InvalidTodoTitleException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {
    @ExceptionHandler(InvalidTodoTitleException::class)
    fun handleInvalidTodoTitle(exception: InvalidTodoTitleException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, requireNotNull(exception.message)).apply {
            title = "Invalid title"
        }

    @ExceptionHandler(TodoNotFoundException::class)
    fun handleTodoNotFound(exception: TodoNotFoundException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, requireNotNull(exception.message)).apply {
            title = "Todo not found"
        }
}
