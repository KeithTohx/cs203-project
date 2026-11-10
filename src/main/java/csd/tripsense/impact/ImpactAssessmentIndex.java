package csd.tripsense.impact;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** SQLite startup upgrades for assessment uniqueness and immutable source identities. */
@Component
public class ImpactAssessmentIndex {
    public ImpactAssessmentIndex(EntityManagerFactory entityManagerFactory, JdbcTemplate database) {
        // The dependency ensures Hibernate has added the nullable assessment_key column first.
        entityManagerFactory.getMetamodel().entity(Impact.class);
        database.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_impact_assessment_key ON impact (assessment_key)");
        for (String table : new String[]{"itinerary", "activity"}) {
            var columns=database.queryForList("pragma table_info("+table+")");
            if (columns.stream().anyMatch(c -> "assessment_revision".equals(c.get("name"))))
                database.update("update "+table+" set revision=coalesce(assessment_revision,0) where revision is null");
            database.execute("create trigger if not exists initialize_"+table+"_identity_key "
                    + "after insert on "+table+" when new.identity_key is null begin update "+table
                    + " set identity_key=lower(hex(randomblob(16))) where id=new.id; end");
            database.update("update "+table+" set identity_key=lower(hex(randomblob(16))) where identity_key is null");
        }
        for (String table : new String[]{"itinerary", "activity", "impact"}) {
            // Backfill existing sources and cover raw SQL fixtures inserted after startup.
            database.execute("create trigger if not exists initialize_" + table + "_assessment_identity "
                    + "after insert on " + table + " when new.assessment_identity is null begin "
                    + "update " + table + " set assessment_identity=lower(hex(randomblob(16))) where id=new.id; end");
            database.update("update " + table + " set assessment_identity=lower(hex(randomblob(16))) "
                    + "where assessment_identity is null");
        }
    }
}
