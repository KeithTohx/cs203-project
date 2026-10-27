package csd.tripsense.itinerary;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
}
