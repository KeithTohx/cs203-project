package csd.tripsense.impact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import csd.tripsense.activity.Activity;
import csd.tripsense.alert.Alert;
import csd.tripsense.country.Country;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.news.News;
import csd.tripsense.user.Role;
import csd.tripsense.user.User;

// the only test in the project that runs real sql. every other test mocks the repository,
// so a wrong column or a bad join in findAwaitingAlerts would not show up until runtime
//
// in memory sqlite with one connection, same as SecurityRulesIntegrationTest. a second
// connection would get its own empty database
//
// @DataJpaTest would be the lighter slice but boot 4 moved it out of
// spring-boot-test-autoconfigure, so it is not on our classpath. @Transactional gives us the
// same per test rollback
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:sqlite::memory:",
    "spring.datasource.hikari.maximum-pool-size=1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.sql.init.mode=never",
    "gdelt.ingest.enabled=false",
    "impact.eval.enabled=false"
})
@DisplayName("ImpactRepository Query Tests")
class ImpactRepositoryTest {

    @PersistenceContext private EntityManager em;
    @Autowired private ImpactRepository impacts;

    private User user;
    private Country japan;
    private Itinerary itinerary;
    private News news;

    @BeforeEach
    void setUp() {
        japan = new Country();
        japan.setName("Japan");
        save(japan);

        user = save(newUser("karwei", true));

        itinerary = new Itinerary();
        itinerary.setUser(user);
        itinerary.setCountry(japan);
        itinerary.setName("Japan Ski Trip");
        save(itinerary);

        news = new News();
        news.setTitle("Heavy snow closes the lifts");
        save(news);
    }

    // ---------------------------------------------------------------- helpers

    // flush so the row gets its generated id and the next query can see it
    private <T> T save(T entity) {
        em.persist(entity);
        em.flush();
        return entity;
    }

    private User newUser(String tag, boolean enabled) {
        User u = new User(tag + "@email.com", tag, "hashed-password", Role.USER.name());
        u.setEnabled(enabled);
        return u;
    }

    // a row that passes every clause in findAwaitingAlerts, so each test can break exactly one
    // and prove that clause is the reason it drops out
    private Impact eligible() {
        Impact i = new Impact();
        i.setUser(user);
        i.setNews(news);
        i.setItinerary(itinerary);
        i.setImpacted(true);
        i.setReason("lifts suspended");
        i.setRejected(false);
        i.setAlertEligible(true);
        return i;
    }

    private List<Long> awaiting() {
        return impacts.findAwaitingAlerts(PageRequest.of(0, 10));
    }

    // the query checks enabled on the itinerary owner and not on impact.user, so a disabled
    // owner needs a trip of its own
    private Itinerary tripOwnedBy(String tag, boolean enabled) {
        Itinerary trip = new Itinerary();
        trip.setUser(save(newUser(tag, enabled)));
        trip.setCountry(japan);
        trip.setName("Trip for " + tag);
        return save(trip);
    }

    // ------------------------------------------------------- findAwaitingAlerts

    @Test
    @DisplayName("an impact that passes every clause is returned")
    void eligibleImpactIsReturned() {
        Long id = save(eligible()).getId();
        assertEquals(List.of(id), awaiting());
    }

    @Test
    @DisplayName("an impact not marked alert eligible is skipped")
    void notAlertEligibleIsSkipped() {
        Impact i = eligible();
        i.setAlertEligible(false);
        save(i);
        assertTrue(awaiting().isEmpty());
    }

    @Test
    @DisplayName("an impact the model cleared is skipped")
    void notImpactedIsSkipped() {
        Impact i = eligible();
        i.setImpacted(false);
        save(i);
        assertTrue(awaiting().isEmpty());
    }

    @Test
    @DisplayName("an impact the traveller rejected is skipped")
    void rejectedIsSkipped() {
        Impact i = eligible();
        i.setRejected(true);
        save(i);
        assertTrue(awaiting().isEmpty());
    }

    @Test
    @DisplayName("a null rejected flag counts as not rejected")
    void nullRejectedStillQualifies() {
        Impact i = eligible();
        i.setRejected(null);
        Long id = save(i).getId();
        assertEquals(List.of(id), awaiting());
    }

    @Test
    @DisplayName("an impact with no reason is skipped")
    void nullReasonIsSkipped() {
        Impact i = eligible();
        i.setReason(null);
        save(i);
        assertTrue(awaiting().isEmpty());
    }

    @Test
    @DisplayName("a reason of only spaces is skipped")
    void blankReasonIsSkipped() {
        Impact i = eligible();
        i.setReason("   ");
        save(i);
        assertTrue(awaiting().isEmpty());
    }

    @Test
    @DisplayName("a whole trip impact with no itinerary is skipped")
    void missingItineraryIsSkipped() {
        Impact i = eligible();
        i.setItinerary(null);
        save(i);
        assertTrue(awaiting().isEmpty());
    }

    @Test
    @DisplayName("an impact on a disabled owners trip is skipped")
    void disabledOwnerIsSkipped() {
        Impact i = eligible();
        i.setItinerary(tripOwnedBy("disabled", false));
        save(i);
        assertTrue(awaiting().isEmpty());
    }

    @Test
    @DisplayName("a null enabled flag counts as enabled")
    void nullEnabledOwnerStillQualifies() {
        Itinerary trip = tripOwnedBy("legacy", true);
        Impact i = eligible();
        i.setItinerary(trip);
        Long id = save(i).getId();

        // setEnabled takes a primitive so the api can never write null. data.sql seeds every
        // user without the column though, so null is what the real database actually holds
        em.createNativeQuery("update user set enabled = null where id = :id")
                .setParameter("id", trip.getUser().getId())
                .executeUpdate();
        em.clear();

        assertEquals(List.of(id), awaiting());
    }

    @Test
    @DisplayName("an impact that already has an alert is not offered again")
    void alreadyAlertedIsSkipped() {
        Impact i = save(eligible());
        save(new Alert(i));
        assertTrue(awaiting().isEmpty());
    }

    @Test
    @DisplayName("results come back oldest first and the page size is respected")
    void resultsAreOrderedAndPaged() {
        Long first = save(eligible()).getId();
        Long second = save(eligible()).getId();
        save(eligible());

        assertEquals(List.of(first, second), impacts.findAwaitingAlerts(PageRequest.of(0, 2)));
    }

    // -------------------------------------------------------- derived queries

    @Test
    @DisplayName("the trip level verdict is found and activity rows are ignored")
    void findsOnlyTheTripLevelVerdict() {
        Activity activity = new Activity();
        activity.setItinerary(itinerary);
        activity.setName("Ski at Niseko");
        save(activity);

        Long tripLevel = save(eligible()).getId();
        Impact onActivity = eligible();
        onActivity.setActivity(activity);
        save(onActivity);

        Optional<Impact> found = impacts.findFirstByNewsIdAndItineraryIdAndActivityIsNull(
                news.getId(), itinerary.getId());

        assertTrue(found.isPresent());
        assertEquals(tripLevel, found.get().getId());
    }

    @Test
    @DisplayName("only impacted rows come back for the itinerary")
    void onlyImpactedRowsComeBack() {
        Long impacted = save(eligible()).getId();
        Impact cleared = eligible();
        cleared.setImpacted(false);
        save(cleared);

        List<Impact> found = impacts.findByUserIdAndItineraryIdAndImpactedTrue(
                user.getId(), itinerary.getId());

        assertEquals(1, found.size());
        assertEquals(impacted, found.get(0).getId());
    }
}
