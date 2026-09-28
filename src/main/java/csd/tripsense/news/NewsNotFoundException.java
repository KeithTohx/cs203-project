package csd.tripsense.news;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * TLDR: This Java file defines a custom unchecked exception NewsNotFoundException thrown when a 
 * requested news article by ID is not found in the database.
*/


@ResponseStatus(HttpStatus.NOT_FOUND) // automatically default to an HTTP 404 status
public class NewsNotFoundException extends RuntimeException {

    public NewsNotFoundException(Long id) {
        super("News not found with id: " + id);
    }

    public NewsNotFoundException(String message) {
        super(message);
    }
}