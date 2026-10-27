package csd.tripsense;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "gdelt.ingest.enabled=false", "impact.eval.enabled=false",
        "spring.datasource.url=jdbc:sqlite::memory:", "spring.datasource.hikari.maximum-pool-size=1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.bootstrap-admin.email=", "app.bootstrap-admin.username=", "app.bootstrap-admin.password="
})
class TripSenseApplicationTests {

    @org.springframework.beans.factory.annotation.Autowired
    private csd.tripsense.user.UserRepository users;

    @Test
    void defaultConfigurationDoesNotSeedDemoAccounts() {
        org.assertj.core.api.Assertions.assertThat(users.count()).isZero();
    }

    @Test
    void contextLoads() {
    }
}