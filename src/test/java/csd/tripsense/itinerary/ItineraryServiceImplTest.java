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
import org.springframework.test.util.ReflectionTestUtils;

import csd.tripsense.country.Country;
import csd.tripsense.user.User;

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

    // ---------------------------------------------------------------- helpers

    private Itinerary trip(Long id, Long ownerId, Long countryId, String name) {
        User owner = new User("user" + ownerId + "@example.com", "user" + ownerId, "hash", "USER");
        ReflectionTestUtils.setField(owner, "id", ownerId);

        Country country = new Country();
        country.setId(countryId);

        Itinerary itinerary = new Itinerary();
        itinerary.setId(id);
        itinerary.setUser(owner);
        itinerary.setCountry(country);
        itinerary.setName(name);
        return itinerary;
    }

    // ===== GET BY ID TESTS =====

    @Test
    @DisplayName("Should return the itinerary when the id exists")
    void getItineraryById_ShouldReturnItinerary_WhenFound() {
        // Arrange
        Itinerary skiTrip = trip(1L, 7L, 1L, "Japan Ski Trip");
        when(itineraryRepository.findById(1L)).thenReturn(Optional.of(skiTrip));

        // Act
        Itinerary actual = itineraryService.getItineraryById(1L);

        // Assert
        assertSame(skiTrip, actual);
    }

    @Test
    @DisplayName("Should throw not found when the id does not exist")
    void getItineraryById_ShouldThrow_WhenMissing() {
        // Arrange
        when(itineraryRepository.findById(99L)).thenReturn(Optional.empty());

        // Act + Assert
        assertThrows(ItineraryNotFoundException.class, () -> itineraryService.getItineraryById(99L));
    }

    // ===== GET BY USER TESTS =====

    @Test
    @DisplayName("Should return only the given user's itineraries")
    void getItinerariesByUser_ShouldReturnOnlyTheirs() {
        // Arrange
        Itinerary mine = trip(1L, 7L, 1L, "Japan Ski Trip");
        Itinerary theirs = trip(2L, 8L, 1L, "Tokyo Weekend");
        when(itineraryRepository.findAll()).thenReturn(List.of(mine, theirs));

        // Act
        List<Itinerary> actual = itineraryService.getItinerariesByUser(7L);

        // Assert
        assertEquals(List.of(mine), actual);
    }

    @Test
    @DisplayName("Should throw not found when the user has no itineraries")
    void getItinerariesByUser_ShouldThrow_WhenUserHasNone() {
        // Arrange
        when(itineraryRepository.findAll()).thenReturn(List.of(trip(2L, 8L, 1L, "Tokyo Weekend")));

        // Act + Assert
        assertThrows(ItineraryNotFoundException.class, () -> itineraryService.getItinerariesByUser(7L));
    }

    @Test
    @DisplayName("Should throw not found when there are no itineraries at all")
    void getItinerariesByUser_ShouldThrow_WhenNoItineraries() {
        // Arrange
        when(itineraryRepository.findAll()).thenReturn(List.of());

        // Act + Assert
        assertThrows(ItineraryNotFoundException.class, () -> itineraryService.getItinerariesByUser(7L));
    }

    @Test
    @DisplayName("Should find one of the user's own itineraries by id")
    void getItineraryByIdAndUserId_ShouldReturnItinerary_WhenOwned() {
        // Arrange
        Itinerary mine = trip(1L, 7L, 1L, "Japan Ski Trip");
        when(itineraryRepository.findAll()).thenReturn(List.of(mine, trip(2L, 8L, 1L, "Tokyo Weekend")));

        // Act
        Itinerary actual = itineraryService.getItineraryByIdAndUserId(1L, 7L);

        // Assert
        assertSame(mine, actual);
    }

    @Test
    @DisplayName("Should throw not found when the itinerary belongs to someone else")
    void getItineraryByIdAndUserId_ShouldThrow_WhenNotOwned() {
        // Arrange
        when(itineraryRepository.findAll()).thenReturn(List.of(
                trip(1L, 7L, 1L, "Japan Ski Trip"), trip(2L, 8L, 1L, "Tokyo Weekend")));

        // Act + Assert
        assertThrows(ItineraryNotFoundException.class, () -> itineraryService.getItineraryByIdAndUserId(2L, 7L));
    }

    // ===== GET BY COUNTRY TESTS =====

    @Test
    @DisplayName("Should return only the itineraries in the given country")
    void getItinerariesByCountry_ShouldFilterByCountry() {
        // Arrange
        Itinerary japan = trip(1L, 7L, 1L, "Japan Ski Trip");
        Itinerary singapore = trip(2L, 7L, 2L, "Singapore Weekend");
        when(itineraryRepository.findAll()).thenReturn(List.of(japan, singapore));

        // Act
        List<Itinerary> actual = itineraryService.getItinerariesByCountry(2L);

        // Assert
        assertEquals(List.of(singapore), actual);
    }

    @Test
    @DisplayName("Should throw not found when there are no itineraries at all")
    void getItinerariesByCountry_ShouldThrow_WhenNoItineraries() {
        // Arrange
        when(itineraryRepository.findAll()).thenReturn(List.of());

        // Act + Assert
        assertThrows(ItineraryNotFoundException.class, () -> itineraryService.getItinerariesByCountry(1L));
    }

    // ===== ADD, UPDATE AND DELETE TESTS =====

    @Test
    @DisplayName("Should save a new itinerary")
    void addItinerary_ShouldSave() {
        // Arrange
        Itinerary newTrip = trip(null, 7L, 1L, "Japan Ski Trip");
        when(itineraryRepository.save(newTrip)).thenReturn(newTrip);

        // Act
        Itinerary actual = itineraryService.addItinerary(newTrip);

        // Assert
        assertSame(newTrip, actual);
        verify(itineraryRepository, times(1)).save(newTrip);
    }

    @Test
    @DisplayName("Should copy the new details onto the stored itinerary, keeping its owner")
    void updateItinerary_ShouldCopyDetails_WhenFound() {
        // Arrange
        Itinerary stored = trip(1L, 7L, 1L, "Japan Ski Trip");
        User originalOwner = stored.getUser();
        Itinerary changes = trip(null, 8L, 2L, "Singapore Weekend");
        changes.setDateStart(LocalDateTime.of(2027, 1, 8, 0, 0));
        changes.setDateEnd(LocalDateTime.of(2027, 1, 10, 0, 0));
        when(itineraryRepository.findById(1L)).thenReturn(Optional.of(stored));
        when(itineraryRepository.save(stored)).thenReturn(stored);

        // Act
        Itinerary actual = itineraryService.updateItinerary(1L, changes);

        // Assert
        assertEquals("Singapore Weekend", actual.getName());
        assertEquals(2L, actual.getCountry().getId());
        assertEquals(LocalDateTime.of(2027, 1, 8, 0, 0), actual.getDateStart());
        assertEquals(LocalDateTime.of(2027, 1, 10, 0, 0), actual.getDateEnd());
        assertSame(originalOwner, actual.getUser());   // an update can never hand the trip to someone else
    }

    @Test
    @DisplayName("Should throw not found and save nothing when updating a missing itinerary")
    void updateItinerary_ShouldThrow_WhenMissing() {
        // Arrange
        when(itineraryRepository.findById(99L)).thenReturn(Optional.empty());

        // Act + Assert
        assertThrows(ItineraryNotFoundException.class,
                () -> itineraryService.updateItinerary(99L, trip(null, 7L, 1L, "Anything")));
        verify(itineraryRepository, never()).save(any(Itinerary.class));
    }

    @Test
    @DisplayName("Should delete an itinerary that exists")
    void deleteItinerary_ShouldDelete_WhenFound() {
        // Arrange
        when(itineraryRepository.existsById(1L)).thenReturn(true);

        // Act
        itineraryService.deleteItinerary(1L);

        // Assert
        verify(itineraryRepository, times(1)).deleteById(1L);
    }

    @Test
    @DisplayName("Should throw not found and delete nothing when the itinerary does not exist")
    void deleteItinerary_ShouldThrow_WhenMissing() {
        // Arrange
        when(itineraryRepository.existsById(99L)).thenReturn(false);

        // Act + Assert
        assertThrows(ItineraryNotFoundException.class, () -> itineraryService.deleteItinerary(99L));
        verify(itineraryRepository, never()).deleteById(anyLong());
    }
}
