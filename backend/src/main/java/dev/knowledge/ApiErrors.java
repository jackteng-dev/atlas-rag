package dev.knowledge;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

@RestControllerAdvice
public class ApiErrors {
    /** The platform's trace filter puts a trace id in the MDC; the legacy mode has none and keeps its original body. */
    private static Map<String, Object> body(String code, String message) {
        var body = new LinkedHashMap<String, Object>();
        body.put("code", code); body.put("message", message);
        var trace = MDC.get("traceId");
        if (trace != null) body.put("traceId", trace);
        return body;
    }
    @ExceptionHandler(ApiException.class) ResponseEntity<?> api(ApiException e) {
        return ResponseEntity.status(e.status).body(body(e.code, e.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,MissingServletRequestParameterException.class,MissingServletRequestPartException.class})
    ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(body("INVALID_REQUEST","Check required fields, limits and version parameters.")); }
    @ExceptionHandler(MaxUploadSizeExceededException.class) ResponseEntity<?> tooLarge(Exception e) {
        return ResponseEntity.status(413).body(body("FILE_TOO_LARGE","The uploaded file exceeds the configured size limit."));
    }
    @ExceptionHandler(NoResourceFoundException.class) ResponseEntity<?> missing(Exception e) {
        return ResponseEntity.status(404).body(body("NOT_FOUND","No such endpoint."));
    }
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class) ResponseEntity<?> mediaType(Exception e) {
        return ResponseEntity.status(415).body(body("UNSUPPORTED_MEDIA_TYPE","This endpoint does not accept that content type."));
    }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class) ResponseEntity<?> method(Exception e) {
        return ResponseEntity.status(405).body(body("METHOD_NOT_ALLOWED","This method is not supported here."));
    }
    @ExceptionHandler(Exception.class) ResponseEntity<?> unexpected(Exception e) {
        if (MDC.get("traceId") != null) LoggerFactory.getLogger(ApiErrors.class).error("Unhandled platform error", e);
        return ResponseEntity.internalServerError().body(body("INTERNAL_ERROR","Unexpected server error. No answer was produced."));
    }
}
