package csd.tripsense.activity;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND) // 404 Error
public class ActivityNotFoundException extends RuntimeException {
    
    public ActivityNotFoundException(Long id) {
        super("Activity with ID: " + id + " not found");
    }

    public ActivityNotFoundException(String mesage) {
        super(mesage);
    }
}
