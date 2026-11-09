package dev.knowledge;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ApiException.class) ResponseEntity<?> api(ApiException e) {
        return ResponseEntity.status(e.status).body(Map.of("code",e.code,"message",e.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,MissingServletRequestParameterException.class})
    ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST","message","Check required fields, limits and version parameters.")); }
    @ExceptionHandler(Exception.class) ResponseEntity<?> unexpected(Exception e) {
        return ResponseEntity.internalServerError().body(Map.of("code","INTERNAL_ERROR","message","Unexpected server error. No answer was produced."));
    }
}
