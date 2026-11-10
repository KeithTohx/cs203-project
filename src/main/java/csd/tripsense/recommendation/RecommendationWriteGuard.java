package csd.tripsense.recommendation;

import csd.tripsense.impact.Impact;
import java.util.function.Supplier;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The source verdict must still be the same row when a slow AI draft is saved. */
@Service
public class RecommendationWriteGuard {
    private final JdbcTemplate sql;
    public RecommendationWriteGuard(JdbcTemplate sql) { this.sql=sql; }
    @Transactional
    public Recommendation saveIfCurrent(Impact impact, Supplier<Recommendation> save) {
        int current = sql.update("update impact set assessment_identity=assessment_identity "
                + "where id=? and assessment_identity=?", impact.getId(), impact.getAssessmentIdentity());
        if (current != 1) throw new OptimisticLockingFailureException("Assessment changed while advice was generated");
        return save.get();
    }
}
