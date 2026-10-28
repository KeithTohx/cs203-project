package csd.tripsense.recommendation;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
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
        // standalone = only this controller, no Spring context, no database
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
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
        when(recommendationService.getByItinerary(1L)).thenReturn(List.of(skiRecommendation()));

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
        when(recommendationService.getByItinerary(1L)).thenReturn(List.of());

        // Act + Assert
        mockMvc.perform(get("/api/itinerary/1/recommendation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
