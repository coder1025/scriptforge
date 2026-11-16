package dev.scriptforge.api;

import dev.scriptforge.domain.ForgeException;
import dev.scriptforge.domain.Json;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(ForgeException.class)
  ResponseEntity<Object> domain(ForgeException ex) {
    int status =
        switch (ex.code()) {
          case "NOT_FOUND" -> 404;
          case "FORBIDDEN", "READ_ONLY" -> 403;
          case "CONFLICT", "VERSION_CONFLICT" -> 409;
          case "CAPACITY" -> 429;
          default -> 400;
        };
    return ResponseEntity.status(status)
        .body(Json.object("error", ex.code(), "message", ex.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<Object> validation(MethodArgumentNotValidException ex) {
    return ResponseEntity.badRequest()
        .body(
            Json.object(
                "error",
                "INVALID_ARGUMENT",
                "message",
                ex.getBindingResult().getFieldErrors().stream()
                    .map(f -> f.getField() + ": " + f.getDefaultMessage())
                    .toList()
                    .toString()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Object> malformed() {
    return ResponseEntity.badRequest()
        .body(
            Json.object(
                "error",
                "INVALID_JSON",
                "message",
                "Request body must be valid JSON matching the API contract"));
  }

  @ExceptionHandler(DuplicateKeyException.class)
  ResponseEntity<Object> duplicate() {
    return ResponseEntity.status(409)
        .body(
            Json.object(
                "error",
                "CONFLICT",
                "message",
                "Concurrent write conflict; refresh before retrying"));
  }
}
