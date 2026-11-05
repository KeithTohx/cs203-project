package csd.tripsense.recommendation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import csd.tripsense.activity.Activity;
import csd.tripsense.activity.ActivityCategory;
import csd.tripsense.impact.Impact;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryNotFoundException;
import csd.tripsense.news.News;

// unit tests for RecommendationController
// the service is mocked and MockMvc is standalone, so only the http side is tested:
// request validation, status codes, and the flat json shape the frontend reads
@ExtendWith(MockitoExtension.class)
@DisplayName("RecommendationController Unit Tests")
class RecommendationControllerTest {

    @Mock
    private RecommendationService recommendationService;

    @InjectMocks
    private RecommendationController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // standalone = only this controller, no Spring context, no database. the resolver is
        // needed so @AuthenticationPrincipal binds instead of being treated as a request body,
        // nobody is logged in here so it hands the controller a null and the mocked service
        // decides what comes back
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    // ---------------------------------------------------------------- helpers

    private Recommendation skiRecommendation() {
        Itinerary itinerary = new Itinerary();
        itinerary.setId(1L);

        Activity skiDay = new Activity();
        skiDay.setId(10L);
        skiDay.setName("Ski at Niseko");

        News earthquake = new News();
        earthquake.setId(5L);
        earthquake.setTitle("Earthquake near Hokkaido");

        Impact impact = new Impact();
        impact.setId(100L);
        impact.setItinerary(itinerary);
        impact.setActivity(skiDay);
        impact.setNews(earthquake);
        impact.setReason("Lifts may stay shut");

        ActivityOption museum = new ActivityOption(501L, "Japan", "Sapporo", ActivityCategory.MUSEUM_GALLERY,
                "Sapporo Art Museum", "Chuo-ku, Sapporo", 43.06, 141.35);

        Recommendation recommendation = new Recommendation();
        recommendation.setId(1L);
        recommendation.setImpact(impact);
        recommendation.setSeverity(Severity.HIGH);
        recommendation.setAction(Action.REPLACE);
        recommendation.setAdvice("Spend the day at the art museum instead.");
        recommendation.setSuggestedOption(museum);
        return recommendation;
    }

    // ===== POST /api/recommendation/generate =====

    @Test
    @DisplayName("Should return the recommendations flattened for the frontend")
    void generate_ShouldReturnFlatJson_WhenItineraryIdGiven() throws Exception {
        // Arrange
        when(recommendationService.generate(1L)).thenReturn(List.of(skiRecommendation()));

        // Act + Assert
        mockMvc.perform(post("/api/recommendation/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itineraryId\": 1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].severity").value("HIGH"))
                .andExpect(jsonPath("$[0].action").value("REPLACE"))
                .andExpect(jsonPath("$[0].advice").value("Spend the day at the art museum instead."))
                .andExpect(jsonPath("$[0].impactId").value(100))
                .andExpect(jsonPath("$[0].reason").value("Lifts may stay shut"))
                .andExpect(jsonPath("$[0].itineraryId").value(1))
                .andExpect(jsonPath("$[0].activityName").value("Ski at Niseko"))
                .andExpect(jsonPath("$[0].newsTitle").value("Earthquake near Hokkaido"))
                .andExpect(jsonPath("$[0].optionName").value("Sapporo Art Museum"))
                .andExpect(jsonPath("$[0].optionArea").value("Sapporo"));
    }

    @Test
    @DisplayName("Should send nulls for the place when nothing was suggested")
    void generate_ShouldSendNullOption_WhenNothingSuggested() throws Exception {
        // Arrange
        Recommendation monitor = skiRecommendation();
        monitor.setSuggestedOption(null);
        monitor.setAction(Action.MONITOR);
        when(recommendationService.generate(1L)).thenReturn(List.of(monitor));

        // Act + Assert
        mockMvc.perform(post("/api/recommendation/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itineraryId\": 1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("MONITOR"))
                .andExpect(jsonPath("$[0].optionId").doesNotExist())
                .andExpect(jsonPath("$[0].optionName").doesNotExist());
    }

    @Test
    @DisplayName("Should return 400 and never call the service when itineraryId is missing")
    void generate_ShouldReturn400_WhenItineraryIdMissing() throws Exception {
        // Act + Assert
        mockMvc.perform(post("/api/recommendation/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(recommendationService, never()).generate(anyLong());
    }

    @Test
    @DisplayName("Should return 404 when the itinerary does not exist")
    void generate_ShouldReturn404_WhenItineraryNotFound() throws Exception {
        // Arrange
        when(recommendationService.generate(99L)).thenThrow(new ItineraryNotFoundException(99L));

        // Act + Assert
        mockMvc.perform(post("/api/recommendation/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itineraryId\": 99}"))
                .andExpect(status().isNotFound());
    }

    // ===== GET /api/itinerary/{id}/recommendation =====

    @Test
    @DisplayName("Should list the stored recommendations for an itinerary")
    void getByItinerary_ShouldReturnStored() throws Exception {
        // Arrange
        when(recommendationService.getByItinerary(eq(1L), eq(false), any()))
                .thenReturn(List.of(skiRecommendation()));

        // Act + Assert
        mockMvc.perform(get("/api/itinerary/1/recommendation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].activityId").value(10));
    }

    @Test
    @DisplayName("Should return an empty list when nothing has been generated yet")
    void getByItinerary_ShouldReturnEmptyList_WhenNoneStored() throws Exception {
        // Arrange
        when(recommendationService.getByItinerary(eq(1L), eq(false), any()))
                .thenReturn(List.of());

        // Act + Assert
        mockMvc.perform(get("/api/itinerary/1/recommendation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("Should pass includeDismissed through to the service")
    void getByItinerary_ShouldPassIncludeDismissed() throws Exception {
        // Arrange
        when(recommendationService.getByItinerary(eq(1L), eq(true), any()))
                .thenReturn(List.of(skiRecommendation()));

        // Act + Assert
        mockMvc.perform(get("/api/itinerary/1/recommendation?includeDismissed=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ===== PATCH /api/recommendation/{id} =====

    @Test
    @DisplayName("Should accept a recommendation and return the decision")
    void decide_ShouldReturnUpdated() throws Exception {
        // Arrange
        Recommendation accepted = skiRecommendation();
        accepted.setStatus(RecommendationStatus.ACCEPTED);
        accepted.setDecidedAt(LocalDateTime.of(2026, 10, 25, 9, 30));
        when(recommendationService.decide(eq(1L), eq(RecommendationStatus.ACCEPTED), any()))
                .thenReturn(accepted);

        // Act + Assert
        mockMvc.perform(patch("/api/recommendation/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ACCEPTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.decidedAt").exists());
    }

    @Test
    @DisplayName("Should return 400 and never call the service when status is missing")
    void decide_ShouldReturn400_WhenStatusMissing() throws Exception {
        // Act + Assert
        mockMvc.perform(patch("/api/recommendation/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(recommendationService, never()).decide(anyLong(), any(), any());
    }

    @Test
    @DisplayName("Should return 400 when status is not one we know")
    void decide_ShouldReturn400_WhenStatusUnknown() throws Exception {
        // the model never touches this, it is the traveller clicking, so a bad value is a bug
        mockMvc.perform(patch("/api/recommendation/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"MAYBE\"}"))
                .andExpect(status().isBadRequest());
    }

    // ===== POST /api/recommendation/{id}/regenerate =====

    @Test
    @DisplayName("Should return the fresh suggestion when asked for a different one")
    void regenerate_ShouldReturnNewRow() throws Exception {
        // Arrange
        Recommendation fresh = skiRecommendation();
        fresh.setId(9L);
        fresh.setStatus(RecommendationStatus.PENDING);
        when(recommendationService.regenerate(eq(1L), any())).thenReturn(fresh);

        // Act + Assert
        mockMvc.perform(post("/api/recommendation/1/regenerate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    // ===== GET /api/impact/{impactId}/recommendation =====

    @Test
    @DisplayName("Should list every attempt made for one impact")
    void getHistory_ShouldReturnEveryAttempt() throws Exception {
        // Arrange
        Recommendation dismissed = skiRecommendation();
        dismissed.setStatus(RecommendationStatus.DISMISSED);
        Recommendation live = skiRecommendation();
        live.setId(9L);
        live.setStatus(RecommendationStatus.PENDING);
        when(recommendationService.getHistory(eq(100L), any()))
                .thenReturn(List.of(dismissed, live));

        // Act + Assert
        mockMvc.perform(get("/api/impact/100/recommendation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("DISMISSED"))
                .andExpect(jsonPath("$[1].status").value("PENDING"));
    }
}
