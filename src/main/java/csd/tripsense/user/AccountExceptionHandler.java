package csd.tripsense.user;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

@RestControllerAdvice
public class AccountExceptionHandler {
    @ExceptionHandler(AccountRuleException.class)
    public ResponseEntity<Map<String, String>> handle(AccountRuleException exception) {
        HttpStatus status = exception.getKind() == AccountRuleException.Kind.CONFLICT
                ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("message", exception.getMessage()));
    }
}
