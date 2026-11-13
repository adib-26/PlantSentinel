package com.basintelligence.web;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.basintelligence.common.InvalidStateException;
import com.basintelligence.common.NotFoundException;

import jakarta.servlet.http.HttpServletResponse;

/** Friendly error page for the Thymeleaf UI. */
@ControllerAdvice(annotations = Controller.class)
public class WebExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    String notFound(NotFoundException e, Model model, HttpServletResponse response) {
        return page(HttpStatus.NOT_FOUND, e.getMessage(), model, response);
    }

    @ExceptionHandler({InvalidStateException.class, IllegalArgumentException.class})
    String rejected(RuntimeException e, Model model, HttpServletResponse response) {
        return page(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), model, response);
    }

    @ExceptionHandler(com.basintelligence.common.ServiceUnavailableException.class)
    String unavailable(RuntimeException e, Model model, HttpServletResponse response) {
        return page(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), model, response);
    }

    @ExceptionHandler({org.springframework.dao.DataAccessResourceFailureException.class,
            org.springframework.dao.TransientDataAccessException.class,
            org.springframework.transaction.CannotCreateTransactionException.class})
    String databaseDown(Model model, HttpServletResponse response) {
        return page(HttpStatus.SERVICE_UNAVAILABLE, "The database is temporarily unavailable. Try again in a minute.",
                model, response);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    String tooLarge(Model model, HttpServletResponse response) {
        return page(HttpStatus.PAYLOAD_TOO_LARGE, "The file is larger than the 10 MB upload limit.", model, response);
    }

    @ExceptionHandler(com.basintelligence.common.RateLimitExceededException.class)
    String tooManyRequests(com.basintelligence.common.RateLimitExceededException e, Model model, HttpServletResponse response) {
        response.setHeader(org.springframework.http.HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
        return page(HttpStatus.TOO_MANY_REQUESTS, e.getMessage(), model, response);
    }

    private static String page(HttpStatus status, String message, Model model, HttpServletResponse response) {
        response.setStatus(status.value());
        model.addAttribute("status", status.value());
        model.addAttribute("message", message);
        return "error";
    }
}
