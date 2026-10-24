package csd.tripsense.impact;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import csd.tripsense.activity.Activity;
import csd.tripsense.activity.ActivityService;
import csd.tripsense.chat.ChatService;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryService;
import csd.tripsense.news.News;
import csd.tripsense.news.NewsService;
import csd.tripsense.user.User;

// unit tests for rejecting impacts in ImpactServiceImpl. the repository and the model are mocked,
// so these check the rules around a rejection and not the database or the ai
@ExtendWith(MockitoExtension.class)
@DisplayName("ImpactServiceImpl Reject Unit Tests")
class ImpactServiceImplTest {

    @Mock
    private ImpactRepository impacts;

    @Mock
    private ChatService chatService;

    @Mock
    private ItineraryService itineraryService;

    @Mock
    private ActivityService activityService;

    @Mock
    private NewsService newsService;

    @InjectMocks
    private ImpactServiceImpl impactService;

    private User testUser;
    private Itinerary testItinerary;
    private Activity testActivity;
    private News testNews;

    @BeforeEach
    void setUp() {
        testUser = new User("john@example.com", "john", "password123", "USER");
        ReflectionTestUtils.setField(testUser, "id", 1L);   // User has no setter for its id

        testItinerary = new Itinerary();
        testItinerary.setId(1L);
        testItinerary.setUser(testUser);
        testItinerary.setName("Japan ski trip");

        testActivity = new Activity();
        testActivity.setId(1L);
        testActivity.setItinerary(testItinerary);
        testActivity.setName("Ski at Niseko");

        testNews = new News();
        testNews.setId(1L);
        testNews.setTitle("6.8 earthquake hits Hokkaido");
    }

    // an impacted verdict that already has a reason, so evaluate serves it from the database
    private Impact storedImpact(Long id, Activity activity, Boolean rejected) {
        Impact impact = new Impact();
        impact.setId(id);
        impact.setUser(testUser);
        impact.setNews(testNews);
        impact.setItinerary(testItinerary);
        impact.setActivity(activity);
        impact.setImpacted(true);
        impact.setReason("Some ski resorts are suspending lifts.");
        impact.setRejected(rejected);
        return impact;
    }

    // ===== SET REJECTED TESTS =====

    @Test
    @DisplayName("Should mark impact as rejected and save it")
    void setRejected_ShouldMarkRejected_WhenImpactExists() {
        // arrange
        Impact impact = storedImpact(1L, testActivity, null);
        when(impacts.findById(1L)).thenReturn(Optional.of(impact));
        when(impacts.save(any(Impact.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // act
        Impact result = impactService.setRejected(1L, true);

        // assert
        assertTrue(result.getRejected());
        verify(impacts).save(impact);
    }

    @Test
    @DisplayName("Should keep the model's verdict and reason when rejecting")
    void setRejected_ShouldKeepVerdictAndReason_WhenRejecting() {
        Impact impact = storedImpact(1L, testActivity, null);
        when(impacts.findById(1L)).thenReturn(Optional.of(impact));
        when(impacts.save(any(Impact.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Impact result = impactService.setRejected(1L, true);

        // restoring has to bring back exactly what the model said, so neither may change
        assertTrue(result.getImpacted());
        assertEquals("Some ski resorts are suspending lifts.", result.getReason());
    }

    @Test
    @DisplayName("Should clear the rejection when restoring")
    void setRejected_ShouldClearRejection_WhenRestoring() {
        Impact impact = storedImpact(1L, testActivity, true);
        when(impacts.findById(1L)).thenReturn(Optional.of(impact));
        when(impacts.save(any(Impact.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Impact result = impactService.setRejected(1L, false);

        assertFalse(result.getRejected());
    }

    @Test
    @DisplayName("Should throw ImpactNotFoundException when impact does not exist")
    void setRejected_ShouldThrow_WhenImpactDoesNotExist() {
        when(impacts.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ImpactNotFoundException.class, () -> impactService.setRejected(99L, true));
        verify(impacts, never()).save(any(Impact.class));
    }

    // ===== EVALUATE TESTS =====

    @Test
    @DisplayName("Should not ask the model again for a rejected impact")
    void evaluate_ShouldNotAskModel_WhenImpactWasRejected() {
        // arrange
        // the whole trip verdict is stored too, so no model call is expected at all
        Impact tripImpact = storedImpact(2L, null, false);
        tripImpact.setImpacted(false);
        Impact rejectedImpact = storedImpact(1L, testActivity, true);

        when(itineraryService.getItineraryById(1L)).thenReturn(testItinerary);
        when(newsService.getNewsById(1L)).thenReturn(testNews);
        when(activityService.getActivitiesByItinerary(1L)).thenReturn(List.of(testActivity));
        when(impacts.findAll()).thenReturn(List.of(tripImpact, rejectedImpact));

        // act
        EvaluateResponse response = impactService.evaluate(1L, 1L);

        // assert
        verify(chatService, never()).ask(anyString(), any());
        verify(impacts, never()).save(any(Impact.class));
        assertTrue(response.activities().get(0).rejected());
    }

    @Test
    @DisplayName("Should not count a rejected impact towards the overall verdict")
    void evaluate_ShouldNotBeImpacted_WhenOnlyImpactIsRejected() {
        Impact tripImpact = storedImpact(2L, null, false);
        tripImpact.setImpacted(false);
        Impact rejectedImpact = storedImpact(1L, testActivity, true);

        when(itineraryService.getItineraryById(1L)).thenReturn(testItinerary);
        when(newsService.getNewsById(1L)).thenReturn(testNews);
        when(activityService.getActivitiesByItinerary(1L)).thenReturn(List.of(testActivity));
        when(impacts.findAll()).thenReturn(List.of(tripImpact, rejectedImpact));

        EvaluateResponse response = impactService.evaluate(1L, 1L);

        // the activity is still reported as the model saw it, it just does not count
        assertTrue(response.activities().get(0).impacted());
        assertFalse(response.impacted());
    }

    @Test
    @DisplayName("Should still be impacted when another impact is not rejected")
    void evaluate_ShouldBeImpacted_WhenTripImpactIsNotRejected() {
        Impact tripImpact = storedImpact(2L, null, false);
        Impact rejectedImpact = storedImpact(1L, testActivity, true);

        when(itineraryService.getItineraryById(1L)).thenReturn(testItinerary);
        when(newsService.getNewsById(1L)).thenReturn(testNews);
        when(activityService.getActivitiesByItinerary(1L)).thenReturn(List.of(testActivity));
        when(impacts.findAll()).thenReturn(List.of(tripImpact, rejectedImpact));

        EvaluateResponse response = impactService.evaluate(1L, 1L);

        assertTrue(response.impacted());
    }

    // ===== FIND IMPACTED TESTS =====

    @Test
    @DisplayName("Should leave rejected impacts out of findImpacted")
    void findImpacted_ShouldExcludeRejected_WhenSomeAreRejected() {
        // arrange
        Impact rejected = storedImpact(1L, testActivity, true);
        Impact notRejected = storedImpact(2L, null, false);
        Impact olderRow = storedImpact(3L, null, null);   // from before the column existed
        when(impacts.findAll()).thenReturn(List.of(rejected, notRejected, olderRow));

        // act
        List<Impact> result = impactService.findImpacted(1L, 1L);

        // assert
        // the chatbot reads this list, so a rejected impact here would be brought up again
        assertEquals(List.of(notRejected, olderRow), result);
    }

    @Test
    @DisplayName("Should still count rejected impacts as evaluated")
    void countByItineraryIdAndUserId_ShouldIncludeRejected() {
        Impact rejected = storedImpact(1L, testActivity, true);
        when(impacts.findAll()).thenReturn(List.of(rejected));

        long count = impactService.countByItineraryIdAndUserId(1L, 1L);

        // otherwise the chatbot would say no news was evaluated, which is not true
        assertEquals(1, count);
    }
}
