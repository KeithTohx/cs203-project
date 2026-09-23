package csd.tripsense;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.servers.Server;

/**
 * Describes the API for the generated Swagger UI at /swagger-ui.html.
 *
 * This class has no methods on purpose - springdoc reads the annotation and
 * builds the documentation page from it.
 */
@OpenAPIDefinition(
    info = @Info(
        title = "TripSense API",
        version = "0.1.0",
        description = "CS203 G3T2. Create travel itineraries, pull in disruption "
                    + "events, and ask an AI model whether a given event actually "
                    + "affects a given trip.",
        contact = @Contact(
            name = "CS203 G3T2",
            email = "g3t2@smu.edu.sg"),
        license = @License(
            name = "Apache License 2.0",
            url = "NA")),
    servers = @Server(url = "http://localhost:8080")
)
public class OpenAPIConfig {

}
