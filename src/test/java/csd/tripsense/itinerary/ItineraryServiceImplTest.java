package csd.tripsense.itinerary;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for ItineraryServiceImpl
 * The repository is mocked, so these tests check that the service delegates correctly.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ItineraryService Unit Tests")
class ItineraryServiceImplTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 25, 12, 0);

    @Mock
    private ItineraryRepository itineraryRepository;

    // without this mock, @InjectMocks would pass null into the constructor
    @Mock
    private ApplicationEventPublisher events;

    @InjectMocks
    private ItineraryServiceImpl itineraryService;

    // ===== COUNTRY IDS WITH ACTIVE ITINERARIES TESTS =====

    @Test
    @DisplayName("Should return the country ids that have an active itinerary")
    void getCountryIdsWithActiveItineraries_ShouldDelegateToRepository() {
        // Arrange
        List<Long> expectedIds = List.of(1L, 3L);
        when(itineraryRepository.findCountryIdsWithActiveItineraries(NOW)).thenReturn(expectedIds);

        // Act
        List<Long> actualIds = itineraryService.getCountryIdsWithActiveItineraries(NOW);

        // Assert
        assertNotNull(actualIds);
        assertEquals(expectedIds, actualIds);
        verify(itineraryRepository, times(1)).findCountryIdsWithActiveItineraries(NOW);
    }

    @Test
    @DisplayName("Should return an empty list, not throw, when no country has an active itinerary")
    void getCountryIdsWithActiveItineraries_ShouldReturnEmpty_WhenNone() {
        when(itineraryRepository.findCountryIdsWithActiveItineraries(NOW)).thenReturn(List.of());

        List<Long> actualIds = itineraryService.getCountryIdsWithActiveItineraries(NOW);

        assertNotNull(actualIds);
        assertTrue(actualIds.isEmpty());
        verify(itineraryRepository, times(1)).findCountryIdsWithActiveItineraries(NOW);
    }

    // ===== ACTIVE ITINERARIES BY COUNTRY TESTS =====

    @Test
    @DisplayName("Should return the active itineraries of a country")
    void getActiveItinerariesByCountry_ShouldDelegateToRepository() {
        Itinerary trip = new Itinerary();
        when(itineraryRepository.findActiveByCountry(1L, NOW)).thenReturn(List.of(trip));

        List<Itinerary> actualTrips = itineraryService.getActiveItinerariesByCountry(1L, NOW);

        assertNotNull(actualTrips);
        assertEquals(List.of(trip), actualTrips);
        verify(itineraryRepository, times(1)).findActiveByCountry(1L, NOW);
    }

    // ===== PENDING ITINERARIES FOR NEWS TESTS =====

    @Test
    @DisplayName("Should return the itineraries still pending for a news item")
    void getItinerariesPendingForNews_ShouldDelegateToRepository() {
        Itinerary trip = new Itinerary();
        when(itineraryRepository.findPendingForNews(5L, 1L, NOW)).thenReturn(List.of(trip));

        List<Itinerary> actualTrips = itineraryService.getItinerariesPendingForNews(5L, 1L, NOW);

        assertNotNull(actualTrips);
        assertEquals(List.of(trip), actualTrips);
        verify(itineraryRepository, times(1)).findPendingForNews(5L, 1L, NOW);
    }

    @Test
    @DisplayName("Should return an empty list, not throw, when nothing is pending")
    void getItinerariesPendingForNews_ShouldReturnEmpty_WhenNothingPending() {
        when(itineraryRepository.findPendingForNews(5L, 1L, NOW)).thenReturn(List.of());

        List<Itinerary> actualTrips = itineraryService.getItinerariesPendingForNews(5L, 1L, NOW);

        assertNotNull(actualTrips);
        assertTrue(actualTrips.isEmpty());
        verify(itineraryRepository, times(1)).findPendingForNews(5L, 1L, NOW);
    }

    // ===== CREATED EVENT TESTS =====

    @Test
    @DisplayName("Should publish a created event carrying the saved itinerary's id")
    void addItinerary_ShouldPublishCreatedEvent_WithTheSavedId() {
        // Arrange
        Itinerary saved = new Itinerary();
        saved.setId(5L);
        when(itineraryRepository.save(any(Itinerary.class))).thenReturn(saved);

        // Act
        Itinerary result = itineraryService.addItinerary(new Itinerary());

        // Assert
        assertSame(saved, result);
        // the id the database assigned, not the null the request came in with
        verify(events, times(1)).publishEvent(new ItineraryCreatedEvent(5L));
    }

    @Test
    @DisplayName("Should publish nothing when the itinerary could not be saved")
    void addItinerary_ShouldNotPublish_WhenSaveFails() {
        when(itineraryRepository.save(any(Itinerary.class))).thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> itineraryService.addItinerary(new Itinerary()));

        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("Should not publish a created event when an itinerary is updated")
    void updateItinerary_ShouldNotPublish() {
        Itinerary existing = new Itinerary();
        existing.setId(5L);
        when(itineraryRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(itineraryRepository.save(any(Itinerary.class))).thenAnswer(inv -> inv.getArgument(0));

        itineraryService.updateItinerary(5L, new Itinerary());

        verifyNoInteractions(events);   // edits are not re-evaluated, only creation is
    }
}