package csd.tripsense.impact;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND) // 404 Error
public class ImpactNotFoundException extends RuntimeException{
    private static final long serialVersionUID = 1L;

    public ImpactNotFoundException(Long id) {
        super("Could not find impact " + id);
    }
    
}
