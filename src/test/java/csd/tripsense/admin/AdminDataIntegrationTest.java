package csd.tripsense.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import csd.tripsense.activity.Activity;
import csd.tripsense.alert.Alert;
import csd.tripsense.country.Country;
import csd.tripsense.impact.Impact;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.news.News;
import csd.tripsense.recommendation.Action;
import csd.tripsense.recommendation.Recommendation;
import csd.tripsense.recommendation.RecommendationStatus;
import csd.tripsense.recommendation.Severity;
import csd.tripsense.user.Role;
import csd.tripsense.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

// the admin deletes against real sql. AdminDataServiceImplTest mocks the repositories, so it can
// only check the calls happen in the right order, not that the cascades really remove the alerts
// and impacts underneath, or that nothing is left pointing at a deleted row.
//
// same in memory sqlite setup as ImpactRepositoryTest. each test builds one trip with:
//   - an activity, with an impact on it that has an alert and a recommendation
//   - a whole trip impact, also with an alert and a recommendation
// and a second user's trip that must survive every delete
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:sqlite::memory:",
    "spring.datasource.hikari.maximum-pool-size=1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.sql.init.mode=never",
    "gdelt.ingest.enabled=false",
    "impact.eval.enabled=false",
    "impact.eval.new-itinerary.enabled=false"
})
@DisplayName("Admin console deletes against a real database")
class AdminDataIntegrationTest {

    @PersistenceContext private EntityManager em;
    @Autowired private AdminDataService adminData;

    private Country japan;
    private News news;

    private Itinerary trip;
    private Activity activity;
    private Impact activityImpact;
    private Alert activityAlert;
    private Recommendation activityRecommendation;
    private Impact tripImpact;
    private Alert tripAlert;
    private Recommendation tripRecommendation;

    private Itinerary otherTrip;
    private Impact otherImpact;
    private Recommendation otherRecommendation;

    @BeforeEach
    void setUp() {
        japan = new Country();
        japan.setName("Japan");
        save(japan);

        news = new News();
        news.setTitle("Heavy snow closes the lifts");
        save(news);

        User bob = save(newUser("bob"));
        trip = save(newTrip(bob, "Japan Ski Trip"));

        activity = new Activity();
        activity.setItinerary(trip);
        activity.setCountry(japan);
        activity.setName("Ski at Niseko");
        activity.setDateStart(LocalDateTime.of(2026, 12, 16, 9, 0));
        save(activity);

        activityImpact = save(newImpact(bob, trip, activity));
        activityAlert = save(new Alert(activityImpact));
        activityRecommendation = save(newRecommendation(activityImpact));

        tripImpact = save(newImpact(bob, trip, null));
        tripAlert = save(new Alert(tripImpact));
        tripRecommendation = save(newRecommendation(tripImpact));

        User carol = save(newUser("carol"));
        otherTrip = save(newTrip(carol, "Carol's Trip"));
        otherImpact = save(newImpact(carol, otherTrip, null));
        otherRecommendation = save(newRecommendation(otherImpact));

        // start every test with nothing cached, like a real request would. otherwise the
        // itinerary's in memory impacts list is empty and the cascades are never exercised
        em.clear();
    }

    // ---------- helpers ----------

    private <T> T save(T entity) {
        em.persist(entity);
        em.flush();
        return entity;
    }

    private static User newUser(String tag) {
        return new User(tag + "@email.com", tag, "hashed-password", Role.USER.name());
    }

    private Itinerary newTrip(User owner, String name) {
        Itinerary itinerary = new Itinerary();
        itinerary.setUser(owner);
        itinerary.setCountry(japan);
        itinerary.setName(name);
        return itinerary;
    }

    private Impact newImpact(User owner, Itinerary itinerary, Activity on) {
        Impact impact = new Impact();
        impact.setUser(owner);
        impact.setItinerary(itinerary);
        impact.setActivity(on);
        impact.setNews(news);
        impact.setImpacted(true);
        impact.setReason("lifts suspended");
        impact.setRejected(false);
        impact.setAlertEligible(true);
        return impact;
    }

    private static Recommendation newRecommendation(Impact impact) {
        Recommendation recommendation = new Recommendation();
        recommendation.setImpact(impact);
        recommendation.setSeverity(Severity.HIGH);
        recommendation.setAction(Action.CANCEL);
        recommendation.setStatus(RecommendationStatus.PENDING);
        recommendation.setCreatedAt(LocalDateTime.now());
        return recommendation;
    }

    // counts the row with plain sql. em.find is not enough: a recommendation whose impact is gone
    // is loaded through an inner join, so find says "not there" even though the orphan row is.
    // sqlite does not enforce foreign keys here either, so nothing else would catch it
    private boolean stillThere(Class<?> type, Long id) {
        em.flush();
        em.clear();

        String table = TABLES.get(type);
        String idColumn = type == Alert.class ? "alert_id" : "id";
        Number count = (Number) em.createNativeQuery("select count(*) from " + table + " where " + idColumn + " = ?")
                .setParameter(1, id)
                .getSingleResult();
        return count.longValue() > 0;
    }

    private static final Map<Class<?>, String> TABLES = Map.of(
            Itinerary.class, "itinerary",
            Activity.class, "activity",
            Impact.class, "impact",
            Alert.class, "alert",
            Recommendation.class, "recommendation");

    private void assertOtherUserUntouched() {
        assertThat(stillThere(Itinerary.class, otherTrip.getId())).isTrue();
        assertThat(stillThere(Impact.class, otherImpact.getId())).isTrue();
        assertThat(stillThere(Recommendation.class, otherRecommendation.getId())).isTrue();
    }

    // ---------- tests ----------

    @Test
    @DisplayName("Should remove an itinerary with every activity, impact, alert and recommendation under it")
    void deleteItinerary_RemovesEverythingUnderIt() {
        adminData.deleteItinerary(trip.getId());

        assertThat(stillThere(Itinerary.class, trip.getId())).isFalse();
        assertThat(stillThere(Activity.class, activity.getId())).isFalse();
        assertThat(stillThere(Impact.class, activityImpact.getId())).isFalse();
        assertThat(stillThere(Impact.class, tripImpact.getId())).isFalse();
        assertThat(stillThere(Alert.class, activityAlert.getId())).isFalse();
        assertThat(stillThere(Alert.class, tripAlert.getId())).isFalse();
        assertThat(stillThere(Recommendation.class, activityRecommendation.getId())).isFalse();
        assertThat(stillThere(Recommendation.class, tripRecommendation.getId())).isFalse();
        assertOtherUserUntouched();
    }

    @Test
    @DisplayName("Should remove an activity with its own impact, alert and recommendation, but keep the trip's")
    void deleteActivity_RemovesOnlyWhatIsOnTheActivity() {
        adminData.deleteActivity(activity.getId());

        assertThat(stillThere(Activity.class, activity.getId())).isFalse();
        assertThat(stillThere(Impact.class, activityImpact.getId())).isFalse();
        assertThat(stillThere(Alert.class, activityAlert.getId())).isFalse();
        assertThat(stillThere(Recommendation.class, activityRecommendation.getId())).isFalse();

        // the whole trip impact is not about this activity, so it stays
        assertThat(stillThere(Itinerary.class, trip.getId())).isTrue();
        assertThat(stillThere(Impact.class, tripImpact.getId())).isTrue();
        assertThat(stillThere(Alert.class, tripAlert.getId())).isTrue();
        assertThat(stillThere(Recommendation.class, tripRecommendation.getId())).isTrue();
        assertOtherUserUntouched();
    }

    @Test
    @DisplayName("Should remove an impact with its alert and recommendations, and keep the activity")
    void deleteImpact_RemovesItsAlertAndRecommendations() {
        adminData.deleteImpact(activityImpact.getId());

        assertThat(stillThere(Impact.class, activityImpact.getId())).isFalse();
        assertThat(stillThere(Alert.class, activityAlert.getId())).isFalse();
        assertThat(stillThere(Recommendation.class, activityRecommendation.getId())).isFalse();

        assertThat(stillThere(Activity.class, activity.getId())).isTrue();
        assertThat(stillThere(Impact.class, tripImpact.getId())).isTrue();
        assertOtherUserUntouched();
    }

    @Test
    @DisplayName("Should clear a trip's old verdicts, alerts and recommendations when it moves country")
    void updateItinerary_NewCountry_RemovesTheOldVerdicts() {
        Country france = new Country();
        france.setName("France");
        save(france);
        em.clear();

        adminData.updateItinerary(trip.getId(), new AdminItineraryRequest("Japan Ski Trip", france.getId(), null, null));

        assertThat(stillThere(Impact.class, activityImpact.getId())).isFalse();
        assertThat(stillThere(Impact.class, tripImpact.getId())).isFalse();
        assertThat(stillThere(Alert.class, activityAlert.getId())).isFalse();
        assertThat(stillThere(Alert.class, tripAlert.getId())).isFalse();
        assertThat(stillThere(Recommendation.class, activityRecommendation.getId())).isFalse();
        assertThat(stillThere(Recommendation.class, tripRecommendation.getId())).isFalse();

        // the trip and its plans stay, only the verdicts about them go
        assertThat(stillThere(Itinerary.class, trip.getId())).isTrue();
        assertThat(stillThere(Activity.class, activity.getId())).isTrue();
        assertOtherUserUntouched();
    }

    @Test
    @DisplayName("Should remove just the one recommendation")
    void deleteRecommendation_RemovesOnlyThatRow() {
        adminData.deleteRecommendation(activityRecommendation.getId());

        assertThat(stillThere(Recommendation.class, activityRecommendation.getId())).isFalse();
        assertThat(stillThere(Impact.class, activityImpact.getId())).isTrue();
        assertThat(stillThere(Alert.class, activityAlert.getId())).isTrue();
        assertOtherUserUntouched();
    }
}
