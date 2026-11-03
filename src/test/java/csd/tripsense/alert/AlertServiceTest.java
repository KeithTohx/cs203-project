package csd.tripsense.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import csd.tripsense.impact.Impact;
import csd.tripsense.impact.ImpactRepository;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.news.News;
import csd.tripsense.user.User;
import csd.tripsense.user.UserRepository;

// unit tests for listing alerts and marking them as read in AlertService
// the repositories are Mockito mocks, so the visibility rules in the JPQL queries and the cursor
// ordering are not covered here. AlertIntegrationTest runs those against a real database
@ExtendWith(MockitoExtension.class)
@DisplayName("AlertService Unit Tests")
class AlertServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private AlertRepository alertRepository;

    @Mock
    private ImpactRepository impactRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ApplicationEventPublisher events;

    @InjectMocks
    private AlertService alertService;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    private User traveller;
    private Alert newerAlert;
    private Alert olderAlert;

    @BeforeEach
    void setUp() {
        traveller = new User("traveller@example.com", "traveller", "long-enough-hash", "USER");
        ReflectionTestUtils.setField(traveller, "id", USER_ID);

        Itinerary skiTrip = new Itinerary();
        skiTrip.setId(10L);
        skiTrip.setName("Japan Ski Trip");
        skiTrip.setUser(traveller);

        News earthquake = new News();
        earthquake.setTitle("Earthquake near Niseko");
        earthquake.setSource("Test source");
        earthquake.setUrl("https://example.com/earthquake");

        newerAlert = alertFor(skiTrip, earthquake, 200L);
        olderAlert = alertFor(skiTrip, earthquake, 100L);
    }

    private Alert alertFor(Itinerary trip, News news, Long alertId) {
        Impact impact = new Impact();
        impact.setItinerary(trip);
        impact.setNews(news);
        impact.setImpacted(true);
        impact.setReason("Ski lifts are closed");

        Alert alert = new Alert(impact);
        ReflectionTestUtils.setField(alert, "id", alertId);
        return alert;
    }

    private void givenActiveAccount() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(traveller));
    }

    // ===== LIST ALERTS TESTS =====

    @Test
    @DisplayName("Should return the next cursor when older alerts exist")
    void list_ShouldReturnNextCursor_WhenOlderAlertsExist() {
        // Arrange
        givenActiveAccount();
        Pageable firstTwo = PageRequest.of(0, 2);
        boolean hasNext = true;
        var slice = new SliceImpl<>(List.of(newerAlert, olderAlert), firstTwo, hasNext);
        when(alertRepository.findForUser(eq(USER_ID), eq(false), isNull(), any(Pageable.class))).thenReturn(slice);
        when(alertRepository.countUnread(USER_ID)).thenReturn(5L);

        // Act
        AlertService.AlertPage page = alertService.list(USER_ID, false, null, 2);

        // Assert
        assertEquals(2, page.content().size());
        assertEquals(200L, page.content().get(0).id());
        assertTrue(page.hasMore());
        assertEquals(100L, page.nextBeforeId());
        assertEquals(5L, page.unreadCount());
    }

    @Test
    @DisplayName("Should return no next cursor when there are no older alerts")
    void list_ShouldReturnNoCursor_WhenNoOlderAlerts() {
        // Arrange
        givenActiveAccount();
        when(alertRepository.findForUser(eq(USER_ID), eq(false), eq(100L), any(Pageable.class)))
                .thenReturn(new SliceImpl<>(Collections.emptyList()));
        when(alertRepository.countUnread(USER_ID)).thenReturn(3L);

        // Act
        AlertService.AlertPage page = alertService.list(USER_ID, false, 100L, 20);

        // Assert
        assertTrue(page.content().isEmpty());
        assertFalse(page.hasMore());
        assertNull(page.nextBeforeId());
        assertEquals(3L, page.unreadCount());
    }

    @Test
    @DisplayName("Should cap the page size at 100 when the requested size is too large")
    void list_ShouldCapPageSizeAt100_WhenSizeTooLarge() {
        // Arrange
        givenActiveAccount();
        when(alertRepository.findForUser(eq(USER_ID), eq(true), isNull(), any(Pageable.class)))
                .thenReturn(new SliceImpl<>(Collections.emptyList()));

        // Act
        alertService.list(USER_ID, true, null, 500);

        // Assert
        verify(alertRepository).findForUser(eq(USER_ID), eq(true), isNull(), pageableCaptor.capture());
        assertEquals(100, pageableCaptor.getValue().getPageSize());
    }

    @Test
    @DisplayName("Should use a page size of 1 when the requested size is below 1")
    void list_ShouldUsePageSizeOfOne_WhenSizeBelowOne() {
        // Arrange
        givenActiveAccount();
        when(alertRepository.findForUser(eq(USER_ID), eq(true), isNull(), any(Pageable.class)))
                .thenReturn(new SliceImpl<>(Collections.emptyList()));

        // Act
        alertService.list(USER_ID, true, null, 0);

        // Assert
        verify(alertRepository).findForUser(eq(USER_ID), eq(true), isNull(), pageableCaptor.capture());
        assertEquals(1, pageableCaptor.getValue().getPageSize());
    }

    @Test
    @DisplayName("Should throw 401 when listing alerts for a disabled account")
    void list_ShouldThrowUnauthorized_WhenAccountDisabled() {
        // Arrange
        traveller.setEnabled(false);
        givenActiveAccount();

        // Act
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> alertService.list(USER_ID, false, null, 20));

        // Assert
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        verify(alertRepository, never()).findForUser(anyLong(), any(Boolean.class), any(), any(Pageable.class));
    }

    // ===== MARK ONE AS READ TESTS =====

    @Test
    @DisplayName("Should set readAt when the alert belongs to the user")
    void markRead_ShouldSetReadAt_WhenAlertOwned() {
        // Arrange
        givenActiveAccount();
        when(alertRepository.findOwned(200L, USER_ID)).thenReturn(Optional.of(newerAlert));
        when(alertRepository.save(newerAlert)).thenReturn(newerAlert);

        // Act
        AlertResponse response = alertService.markRead(200L, USER_ID);

        // Assert
        assertEquals(200L, response.id());
        assertNotNull(response.readAt());
    }

    @Test
    @DisplayName("Should keep the first readAt when the alert is already read")
    void markRead_ShouldKeepFirstReadAt_WhenAlertAlreadyRead() {
        // Arrange
        givenActiveAccount();
        Instant firstRead = Instant.parse("2026-01-01T00:00:00Z");
        ReflectionTestUtils.setField(newerAlert, "readAt", firstRead);
        when(alertRepository.findOwned(200L, USER_ID)).thenReturn(Optional.of(newerAlert));
        when(alertRepository.save(newerAlert)).thenReturn(newerAlert);

        // Act
        AlertResponse response = alertService.markRead(200L, USER_ID);

        // Assert
        assertEquals(firstRead, response.readAt());
    }

    @Test
    @DisplayName("Should throw AlertNotFoundException when the alert is not the user's")
    void markRead_ShouldThrowAlertNotFoundException_WhenAlertNotOwned() {
        // Arrange
        givenActiveAccount();
        when(alertRepository.findOwned(999L, USER_ID)).thenReturn(Optional.empty());

        // Act and Assert
        assertThrows(AlertNotFoundException.class, () -> alertService.markRead(999L, USER_ID));
        verify(alertRepository, never()).save(any(Alert.class));
    }

    // ===== MARK ALL AS READ TESTS =====

    @Test
    @DisplayName("Should mark every unread alert as read when the user has unread alerts")
    void markAllRead_ShouldMarkEveryAlert_WhenUserHasUnreadAlerts() {
        // Arrange
        givenActiveAccount();
        when(alertRepository.findUnreadForUser(USER_ID, null)).thenReturn(List.of(newerAlert, olderAlert));
        when(alertRepository.countUnread(USER_ID)).thenReturn(0L);

        // Act
        AlertService.UnreadCount result = alertService.markAllRead(USER_ID, null);

        // Assert
        assertNotNull(newerAlert.getReadAt());
        assertNotNull(olderAlert.getReadAt());
        assertEquals(0L, result.unreadCount());
    }

    @Test
    @DisplayName("Should throw 401 before touching any alerts when the account is unknown")
    void markAllRead_ShouldThrowUnauthorized_WhenAccountUnknown() {
        // Arrange
        when(userRepository.findById(2L)).thenReturn(Optional.empty());

        // Act
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> alertService.markAllRead(2L, null));

        // Assert
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        verify(alertRepository, never()).findUnreadForUser(any(), any());
    }
}
