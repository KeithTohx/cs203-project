package csd.tripsense.recommendation;

import java.util.List;

import org.junit.jupiter.api.Test;

import csd.tripsense.activity.ActivityCategory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// the two guards that stop a bad model answer getting into the database, no spring and no mocks
class RecommendationGuardsTest {

    private final ActivityOption offered = new ActivityOption(1L, "Japan", "Sapporo",
            ActivityCategory.MUSEUM_GALLERY, "Sapporo Art Museum", "Sapporo, Japan",
            43.0618, 141.3545);

    @Test
    void picksAnOptionWeOffered() {
        assertEquals(offered, RecommendationServiceImpl.pick(1L, List.of(offered)));
    }

    @Test
    void dropsAnOptionWeNeverOffered() {
        // the model inventing an id is the failure this whole design exists to catch
        assertNull(RecommendationServiceImpl.pick(999L, List.of(offered)));
    }

    @Test
    void dropsANullOptionId() {
        assertNull(RecommendationServiceImpl.pick(null, List.of(offered)));
    }

    @Test
    void readsAnEnumTheModelSentAsMessyText() {
        assertEquals(Severity.HIGH,
                RecommendationServiceImpl.parse(Severity.class, " high ", Severity.MEDIUM));
    }

    @Test
    void fallsBackWhenTheModelMakesUpAnEnum() {
        assertEquals(Action.MONITOR,
                RecommendationServiceImpl.parse(Action.class, "PANIC", Action.MONITOR));
        assertEquals(Action.MONITOR,
                RecommendationServiceImpl.parse(Action.class, null, Action.MONITOR));
    }
}
