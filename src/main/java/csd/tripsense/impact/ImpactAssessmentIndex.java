package csd.tripsense.impact;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** SQLite's Hibernate dialect may omit unique-index DDL; enforce it after JPA schema creation. */
@Component
public class ImpactAssessmentIndex {
    public ImpactAssessmentIndex(EntityManagerFactory entityManagerFactory, JdbcTemplate database) {
        // The dependency ensures Hibernate has added the nullable assessment_key column first.
        entityManagerFactory.getMetamodel().entity(Impact.class);
        database.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_impact_assessment_key ON impact (assessment_key)");
    }
}
