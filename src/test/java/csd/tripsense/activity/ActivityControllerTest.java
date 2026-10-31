package csd.tripsense.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import csd.tripsense.auth.CurrentUser;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryAccess;
import csd.tripsense.itinerary.ItineraryNotFoundException;
import csd.tripsense.itinerary.ItineraryService;
import csd.tripsense.user.User;
import csd.tripsense.user.UserRepository;

// unit tests for ActivityController, as the owner of the trip
// other users' data is covered by ActivityOwnershipTest, so this checks the endpoints work for the
// owner and give the right status when something is missing or the request is wrong.
// ItineraryAccess is the real one, so the ownership rules run exactly as in the app
@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityController Unit Tests")
class ActivityControllerTest {

    @Mock
    private ActivityService activityService;

    @Mock
    private ItineraryService itineraryService;

    @Captor
    private ArgumentCaptor<Activity> activityCaptor;

    private MockMvc mockMvc;

    private final CurrentUser owner = new CurrentUser(1L, "traveller@example.com", "traveller", null, "USER", true);

    private Itinerary skiTrip;
    private Activity skiDay;

    @BeforeEach
    void setUp() {
        ItineraryAccess access = new ItineraryAccess(mock(UserRepository.class));
        ActivityController controller = new ActivityController(activityService, itineraryService, access);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(owner, null, owner.getAuthorities()));

        User account = new User("traveller@example.com", "traveller", "hash", "USER");
        ReflectionTestUtils.setField(account, "id", 1L);

        skiTrip = new Itinerary();
        skiTrip.setId(10L);
        skiTrip.setName("Japan Ski Trip");
        skiTrip.setUser(account);

        skiDay = new Activity();
        skiDay.setId(20L);
        skiDay.setName("Ski at Niseko");
        skiDay.setAddress("Niseko, Hokkaido, Japan");
        skiDay.setCategory(ActivityCategory.SKI_SNOW);
        skiDay.setItinerary(skiTrip);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ===== GET =====

    @Test
    @DisplayName("Should return one activity with its itinerary flattened to an id and name")
    void getActivityById_ShouldReturnActivity_WhenOwned() throws Exception {
        // Arrange
        when(activityService.getActivityById(20L)).thenReturn(skiDay);

        // Act + Assert
        mockMvc.perform(get("/api/activity/20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(20))
                .andExpect(jsonPath("$.name").value("Ski at Niseko"))
                .andExpect(jsonPath("$.category").value("SKI_SNOW"))
                .andExpect(jsonPath("$.itineraryId").value(10))
                .andExpect(jsonPath("$.itineraryName").value("Japan Ski Trip"));
    }

    @Test
    @DisplayName("Should return 404 when the activity does not exist")
    void getActivityById_ShouldReturn404_WhenMissing() throws Exception {
        // Arrange
        when(activityService.getActivityById(99L)).thenThrow(new ActivityNotFoundException(99L));

        // Act + Assert
        mockMvc.perform(get("/api/activity/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Should list the activities of an itinerary")
    void getActivitiesByItinerary_ShouldReturnList_WhenOwned() throws Exception {
        // Arrange
        when(itineraryService.getItineraryById(10L)).thenReturn(skiTrip);
        when(activityService.getActivitiesByItinerary(10L)).thenReturn(List.of(skiDay));

        // Act + Assert
        mockMvc.perform(get("/api/itinerary/10/activity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Ski at Niseko"));
    }

    @Test
    @DisplayName("Should return 404 when listing the activities of an itinerary that does not exist")
    void getActivitiesByItinerary_ShouldReturn404_WhenItineraryMissing() throws Exception {
        // Arrange
        when(itineraryService.getItineraryById(99L)).thenThrow(new ItineraryNotFoundException(99L));

        // Act + Assert
        mockMvc.perform(get("/api/itinerary/99/activity"))
                .andExpect(status().isNotFound());
    }

    // ===== POST =====

    @Test
    @DisplayName("Should add an activity to the owner's itinerary")
    void addActivity_ShouldSave_WhenItineraryOwned() throws Exception {
        // Arrange
        when(itineraryService.getItineraryById(10L)).thenReturn(skiTrip);
        when(activityService.addActivity(any(Activity.class))).thenAnswer(invocation -> {
            Activity saved = invocation.getArgument(0);
            saved.setId(21L);
            return saved;
        });

        // Act + Assert
        mockMvc.perform(post("/api/activity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "name": "Sapporo Art Museum",
                                    "category": "MUSEUM_GALLERY",
                                    "itinerary": { "id": 10 }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(21))
                .andExpect(jsonPath("$.itineraryId").value(10));

        verify(activityService).addActivity(activityCaptor.capture());
        Activity sent = activityCaptor.getValue();
        assertEquals("Sapporo Art Museum", sent.getName());
        assertEquals(skiTrip, sent.getItinerary());
    }

    @Test
    @DisplayName("Should not keep the id the client sent on a new activity")
    void addActivity_ShouldClearClientId() throws Exception {
        // Arrange
        when(itineraryService.getItineraryById(10L)).thenReturn(skiTrip);
        when(activityService.addActivity(any(Activity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        mockMvc.perform(post("/api/activity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\": 20, \"name\": \"Overwrite attempt\", \"itinerary\": {\"id\": 10}}"))
                .andExpect(status().isOk());

        // Assert: an id from the client would overwrite an existing activity, so it must be dropped
        verify(activityService).addActivity(activityCaptor.capture());
        assertNull(activityCaptor.getValue().getId());
    }

    @Test
    @DisplayName("Should return 400 when the activity names no itinerary")
    void addActivity_ShouldReturn400_WhenItineraryMissing() throws Exception {
        // Act + Assert
        mockMvc.perform(post("/api/activity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Sapporo Art Museum\"}"))
                .andExpect(status().isBadRequest());

        verify(activityService, never()).addActivity(any(Activity.class));
    }

    @Test
    @DisplayName("Should return 404 when adding to an itinerary that does not exist")
    void addActivity_ShouldReturn404_WhenItineraryNotFound() throws Exception {
        // Arrange
        when(itineraryService.getItineraryById(99L)).thenThrow(new ItineraryNotFoundException(99L));

        // Act + Assert
        mockMvc.perform(post("/api/activity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Museum\", \"itinerary\": {\"id\": 99}}"))
                .andExpect(status().isNotFound());

        verify(activityService, never()).addActivity(any(Activity.class));
    }

    // ===== PUT =====

    @Test
    @DisplayName("Should update an activity and keep it on the same itinerary")
    void updateActivity_ShouldSaveChanges_WhenOwned() throws Exception {
        // Arrange
        when(activityService.getActivityById(20L)).thenReturn(skiDay);
        when(activityService.updateActivity(eq(20L), any(Activity.class))).thenAnswer(invocation -> {
            Activity changes = invocation.getArgument(1);
            changes.setId(20L);
            return changes;
        });

        // Act + Assert
        mockMvc.perform(put("/api/activity/20")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Ski at Rusutsu\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ski at Rusutsu"))
                .andExpect(jsonPath("$.itineraryId").value(10));

        verify(activityService).updateActivity(eq(20L), activityCaptor.capture());
        assertEquals(skiTrip, activityCaptor.getValue().getItinerary());
    }

    @Test
    @DisplayName("Should return 400 when moving an activity to another of the owner's itineraries")
    void updateActivity_ShouldReturn400_WhenMovingBetweenItineraries() throws Exception {
        // Arrange: the second trip is also the owner's, but activities still cannot move
        Itinerary beachTrip = new Itinerary();
        beachTrip.setId(11L);
        beachTrip.setUser(skiTrip.getUser());
        when(activityService.getActivityById(20L)).thenReturn(skiDay);
        when(itineraryService.getItineraryById(11L)).thenReturn(beachTrip);

        // Act + Assert
        mockMvc.perform(put("/api/activity/20")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Ski at Niseko\", \"itinerary\": {\"id\": 11}}"))
                .andExpect(status().isBadRequest());

        verify(activityService, never()).updateActivity(anyLong(), any(Activity.class));
    }

    @Test
    @DisplayName("Should return 404 when updating an activity that does not exist")
    void updateActivity_ShouldReturn404_WhenMissing() throws Exception {
        // Arrange
        when(activityService.getActivityById(99L)).thenThrow(new ActivityNotFoundException(99L));

        // Act + Assert
        mockMvc.perform(put("/api/activity/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Anything\"}"))
                .andExpect(status().isNotFound());
    }

    // ===== DELETE =====

    @Test
    @DisplayName("Should delete an activity the user owns")
    void deleteActivity_ShouldDelete_WhenOwned() throws Exception {
        // Arrange
        when(activityService.getActivityById(20L)).thenReturn(skiDay);

        // Act + Assert
        mockMvc.perform(delete("/api/activity/20"))
                .andExpect(status().isOk());

        verify(activityService).deleteActivity(20L);
    }

    @Test
    @DisplayName("Should return 404 and delete nothing when the activity does not exist")
    void deleteActivity_ShouldReturn404_WhenMissing() throws Exception {
        // Arrange
        when(activityService.getActivityById(99L)).thenThrow(new ActivityNotFoundException(99L));

        // Act + Assert
        mockMvc.perform(delete("/api/activity/99"))
                .andExpect(status().isNotFound());

        verify(activityService, never()).deleteActivity(anyLong());
    }
}
