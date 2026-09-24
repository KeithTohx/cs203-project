package csd.tripsense;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for TripSense.
 *
 * The application watches a feed of real-world disruption events and works out,
 * for one traveller's itinerary at a time, whether any of them actually matter.
 */
@SpringBootApplication
public class TripSenseApplication {

    private static final Logger log = LoggerFactory.getLogger(TripSenseApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(TripSenseApplication.class, args);

        log.info("TripSense started. API docs: http://localhost:8080/swagger-ui.html");
    }

}
