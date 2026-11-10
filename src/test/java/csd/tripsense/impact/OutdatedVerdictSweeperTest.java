package csd.tripsense.impact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

import csd.tripsense.alert.Alert;
import csd.tripsense.country.Country;
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

// against real sql, so the cascades to alerts and advice really happen. the sweep is called by hand,
// the one on start ran before these rows existed. one trip holds:
//   - a verdict from the old prompts (null version), with an alert and advice
//   - a verdict from the current prompts
//   - a seed row with no reason, never judged
@SpringBootTest
@Transactional
@RecordApplicationEvents
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:sqlite::memory:",
    "spring.datasource.hikari.maximum-pool-size=1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.sql.init.mode=never",
    "gdelt.ingest.enabled=false",
    "impact.eval.enabled=false",
    "impact.eval.new-itinerary.enabled=false"
})
@DisplayName("Clearing verdicts from older prompts")
class OutdatedVerdictSweeperTest {

    @PersistenceContext private EntityManager em;
    @Autowired private OutdatedVerdictSweeper sweeper;
    @Autowired private ApplicationEvents events;

    private Itinerary trip;
    private News oldArticle;
    private Impact oldVerdict;
    private Alert oldAlert;
    private Recommendation oldAdvice;
    private Impact currentVerdict;
    private Impact seedRow;

    @BeforeEach
    void setUp() {
        Country japan = new Country();
        japan.setName("Japan");
        save(japan);

        User bob = save(new User("bob@email.com", "bob", "hashed-password", Role.USER.name()));

        trip = new Itinerary();
        trip.setUser(bob);
        trip.setCountry(japan);
        trip.setName("Hokkaido ski trip");
        save(trip);

        oldArticle = save(newArticle("Taiwan warns of growing Chinese naval threat"));
        oldVerdict = save(newVerdict(bob, oldArticle, "The news mentions Japan", null));
        oldAlert = save(new Alert(oldVerdict));
        oldAdvice = save(newRecommendation(oldVerdict));

        News quake = save(newArticle("Strong quake hits Hokkaido"));
        currentVerdict = save(newVerdict(bob, quake, "Lifts closed after the quake", Impact.CURRENT_PROMPT_VERSION));

        News unjudged = save(newArticle("Snow expected in Sapporo"));
        seedRow = save(newVerdict(bob, unjudged, null, null));

        em.clear();
    }

    private <T> T save(T entity) {
        em.persist(entity);
        em.flush();
        return entity;
    }

    private static News newArticle(String title) {
        News article = new News();
        article.setTitle(title);
        return article;
    }

    private Impact newVerdict(User owner, News article, String reason, Long promptVersion) {
        Impact impact = new Impact();
        impact.setUser(owner);
        impact.setItinerary(trip);
        impact.setNews(article);
        impact.setImpacted(true);
        impact.setReason(reason);
        impact.setRejected(false);
        impact.setAlertEligible(true);
        impact.setPromptVersion(promptVersion);
        return impact;
    }

    private static Recommendation newRecommendation(Impact impact) {
        Recommendation recommendation = new Recommendation();
        recommendation.setImpact(impact);
        recommendation.setSeverity(Severity.HIGH);
        recommendation.setAction(Action.MONITOR);
        recommendation.setStatus(RecommendationStatus.PENDING);
        recommendation.setCreatedAt(java.time.LocalDateTime.now());
        return recommendation;
    }

    // plain sql, see AdminDataIntegrationTest.stillThere
    private boolean stillThere(String table, String idColumn, Long id) {
        em.flush();
        em.clear();
        Number count = (Number) em.createNativeQuery("select count(*) from " + table + " where " + idColumn + " = ?")
                .setParameter(1, id)
                .getSingleResult();
        return count.longValue() > 0;
    }

    @Test
    @DisplayName("Should clear a verdict from older prompts with its alert and advice")
    void sweep_ClearsOldVerdict() {
        int cleared = sweeper.sweep();

        assertThat(cleared).isEqualTo(1);
        assertThat(stillThere("impact", "id", oldVerdict.getId())).isFalse();
        assertThat(stillThere("alert", "alert_id", oldAlert.getId())).isFalse();
        assertThat(stillThere("recommendation", "id", oldAdvice.getId())).isFalse();
    }

    @Test
    @DisplayName("Should keep verdicts from the current prompts, and rows that were never judged")
    void sweep_KeepsCurrentAndUnjudged() {
        sweeper.sweep();

        assertThat(stillThere("impact", "id", currentVerdict.getId())).isTrue();
        assertThat(stillThere("impact", "id", seedRow.getId())).isTrue();
    }

    @Test
    @DisplayName("Should hand the cleared articles over to be judged again")
    void sweep_PublishesClearedArticles() {
        sweeper.sweep();

        assertThat(events.stream(VerdictsClearedEvent.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.newsIdsByTrip()).containsEntry(trip.getId(), Set.of(oldArticle.getId()));
                    assertThat(event.newsIdsByActivity()).isEmpty();
                });
    }

    @Test
    @DisplayName("Should do nothing the second time, once everything is current")
    void sweep_Twice_SecondFindsNothing() {
        sweeper.sweep();

        assertThat(sweeper.sweep()).isZero();
    }
}
