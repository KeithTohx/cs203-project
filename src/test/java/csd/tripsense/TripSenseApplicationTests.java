package csd.tripsense;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "gdelt.ingest.enabled=false", "impact.eval.enabled=false",
        "spring.datasource.url=jdbc:sqlite::memory:", "spring.datasource.hikari.maximum-pool-size=1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class TripSenseApplicationTests {

    @Test
    void contextLoads() {
    }
}