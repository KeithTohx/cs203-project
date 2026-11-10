package csd.tripsense.admin;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// without this a bad form comes back as "Validation failed for object=...", which the console
// cannot show to anyone. only covers the admin console, so other endpoints keep their usual body
@RestControllerAdvice(assignableTypes = AdminDataController.class)
public class AdminDataExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleInvalidBody(MethodArgumentNotValidException exception) {
        FieldError firstError = exception.getBindingResult().getFieldError();

        String message = firstError == null
                ? "the request is not valid"
                : firstError.getDefaultMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", message));
    }
}
