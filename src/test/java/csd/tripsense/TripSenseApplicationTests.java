package csd.tripsense;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "gdelt.ingest.enabled=false")
class TripSenseApplicationTests {

    @Test
    void contextLoads() {
    }
}