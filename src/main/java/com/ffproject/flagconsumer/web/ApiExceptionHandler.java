package com.ffproject.flagconsumer.web;

import com.ffproject.flagconsumer.flag.FlagNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the two expected API failures into small, stable JSON bodies instead of
 * Spring's default error page.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MissingTenantException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse missingTenant(MissingTenantException exception) {
        return new ErrorResponse("tenant_required", exception.getMessage());
    }

    @ExceptionHandler(FlagNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse flagNotFound(FlagNotFoundException exception) {
        return new ErrorResponse("flag_not_found", exception.getMessage());
    }

    /** Shape of every error body: {@code {"error":"code","message":"..."}}. */
    public record ErrorResponse(String error, String message) {
    }
}
