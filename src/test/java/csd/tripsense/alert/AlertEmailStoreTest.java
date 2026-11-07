package csd.tripsense.alert;

import csd.tripsense.impact.EvaluationBatchRepository;
import csd.tripsense.impact.Impact;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.news.News;
import csd.tripsense.user.User;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AlertEmailStoreTest {
    @Test void initialSettleWindowDoesNotQueryEvaluationProgressOrCreateDeliveryIdentity() {
        var alerts = mock(AlertRepository.class);
        var groups = mock(AlertEmailGroupRepository.class);
        var evaluations = mock(EvaluationBatchRepository.class);
        var store = new AlertEmailStore(alerts, groups, evaluations, 120, 30, 30);
        var trip = new Itinerary(); trip.setId(10L);
        trip.setUser(new User("owner@example.com", "owner", "hash", "USER"));
        var news = new News(); news.setId(20L);
        var impact = new Impact(); impact.setItinerary(trip); impact.setNews(news);
        var alert = new Alert(impact); ReflectionTestUtils.setField(alert, "id", 1L);
        when(alerts.findById(1L)).thenReturn(Optional.of(alert));
        when(alerts.findEmailGroup(10L, 20L)).thenReturn(List.of(alert));

        assertThat(store.claim(1L, alert.getCreatedAt().plusSeconds(1))).isEmpty();

        verifyNoInteractions(evaluations, groups);
        verify(alerts).deferEmail(1L, 0, EmailStatus.PENDING, alert.getCreatedAt().plusSeconds(30));
    }
}
