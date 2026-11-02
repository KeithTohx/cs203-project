package csd.tripsense.alert;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class AlertNotFoundException extends RuntimeException {
    public AlertNotFoundException(Long id) { super("Alert with ID: " + id + " not found"); }
}
