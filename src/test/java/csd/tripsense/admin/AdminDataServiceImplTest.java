package csd.tripsense.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import csd.tripsense.activity.Activity;
import csd.tripsense.activity.ActivityCreatedEvent;
import csd.tripsense.activity.ActivityRepository;
import csd.tripsense.alert.Alert;
import csd.tripsense.alert.ImpactEvaluatedEvent;
import csd.tripsense.country.Country;
import csd.tripsense.country.CountryRepository;
import csd.tripsense.impact.Impact;
import csd.tripsense.impact.ImpactRepository;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryCreatedEvent;
import csd.tripsense.itinerary.ItineraryRepository;
import csd.tripsense.news.News;
import csd.tripsense.news.NewsRepository;
import csd.tripsense.recommendation.Action;
import csd.tripsense.recommendation.ActivityOptionRepository;
import csd.tripsense.recommendation.Recommendation;
import csd.tripsense.recommendation.RecommendationRepository;
import csd.tripsense.recommendation.RecommendationStatus;
import csd.tripsense.recommendation.Severity;
import csd.tripsense.user.Role;
import csd.tripsense.user.User;
import csd.tripsense.user.UserNotFoundException;
import csd.tripsense.user.UserRepository;
import csd.tripsense.user.UserService;
import csd.tripsense.user.preference.UserPreference;
import csd.tripsense.user.preference.UserPreferenceRepository;

class AdminDataServiceImplTest {

    private UserRepository users;
    private UserService userService;
    private ItineraryRepository itineraries;
    private ActivityRepository activities;
    private ImpactRepository impacts;
    private RecommendationRepository recommendations;
    private CountryRepository countries;
    private NewsRepository news;
    private ActivityOptionRepository options;
    private UserPreferenceRepository preferences;
    private ApplicationEventPublisher events;
    private AdminDataServiceImpl service;

    private User owner;
    private User someoneElse;
    private Country japan;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        userService = mock(UserService.class);
        itineraries = mock(ItineraryRepository.class);
        activities = mock(ActivityRepository.class);
        impacts = mock(ImpactRepository.class);
        recommendations = mock(RecommendationRepository.class);
        countries = mock(CountryRepository.class);
        news = mock(NewsRepository.class);
        options = mock(ActivityOptionRepository.class);
        preferences = mock(UserPreferenceRepository.class);
        events = mock(ApplicationEventPublisher.class);
        service = new AdminDataServiceImpl(users, userService, itineraries, activities, impacts,
                recommendations, countries, news, options, preferences, events);

        owner = account(4L, "bob");
        someoneElse = account(5L, "carol");
        when(users.findById(4L)).thenReturn(Optional.of(owner));
        when(users.findById(5L)).thenReturn(Optional.of(someoneElse));

        japan = new Country();
        japan.setId(1L);
        japan.setName("Japan");
        when(countries.findById(1L)).thenReturn(Optional.of(japan));

        // save hands back what it was given, like the real repositories do
        when(itineraries.save(any(Itinerary.class))).thenAnswer(call -> {
            Itinerary itinerary = call.getArgument(0);
            itinerary.setId(10L);
            return itinerary;
        });
        when(activities.save(any(Activity.class))).thenAnswer(call -> {
            Activity activity = call.getArgument(0);
            activity.setId(20L);
            return activity;
        });
        when(impacts.save(any(Impact.class))).thenAnswer(call -> {
            Impact impact = call.getArgument(0);
            impact.setId(70L);
            return impact;
        });
        when(recommendations.save(any(Recommendation.class))).thenAnswer(call -> call.getArgument(0));
    }

    // ---------- helpers ----------

    private static User account(Long id, String name) {
        User user = new User(name + "@example.com", name, "hash", "USER");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Itinerary savedTrip(Long id, User tripOwner) {
        Itinerary trip = new Itinerary();
        trip.setId(id);
        trip.setUser(tripOwner);
        trip.setCountry(japan);
        trip.setName("Japan Ski Trip");
        when(itineraries.findById(id)).thenReturn(Optional.of(trip));
        return trip;
    }

    private Activity savedActivity(Long id, Itinerary trip) {
        Activity activity = new Activity();
        activity.setId(id);
        activity.setItinerary(trip);
        activity.setName("Ski at Niseko");
        when(activities.findById(id)).thenReturn(Optional.of(activity));
        return activity;
    }

    private News savedNews(Long id) {
        News article = new News();
        article.setId(id);
        article.setTitle("Earthquake near Hokkaido");
        when(news.findById(id)).thenReturn(Optional.of(article));
        return article;
    }

    private Impact savedImpact(Long id) {
        Impact impact = new Impact();
        impact.setId(id);
        impact.setUser(owner);
        when(impacts.findById(id)).thenReturn(Optional.of(impact));
        return impact;
    }

    private static void assertBadRequest(Throwable thrown) {
        assertThat(thrown).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ---------- accounts ----------

    @Test
    @DisplayName("Should register an admin account through the normal path, then give it the admin role")
    void createUser_AdminRole_RegistersThenPromotes() {
        User registered = account(9L, "tester");
        when(userService.register(any(User.class))).thenReturn(registered);
        when(users.saveAndFlush(registered)).thenReturn(registered);

        User created = service.createUser(new CreateUserRequest("tester", "tester@example.com", "long-password", Role.ADMIN));

        verify(userService).register(any(User.class));   // so duplicates and hashing work like registering
        assertThat(created.getRole()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("Should leave a USER account as registered")
    void createUser_UserRole_IsNotSavedAgain() {
        User registered = account(9L, "tester");
        when(userService.register(any(User.class))).thenReturn(registered);

        User created = service.createUser(new CreateUserRequest("tester", "tester@example.com", "long-password", Role.USER));

        assertThat(created.getRole()).isEqualTo("USER");
        verify(users, never()).saveAndFlush(any());
    }

    // ---------- itineraries ----------

    @Test
    @DisplayName("Should create an itinerary owned by the user in the url")
    void createItinerary_BelongsToTheUserInThePath() {
        AdminItineraryRequest request = new AdminItineraryRequest(" Tokyo Trip ", 1L,
                LocalDateTime.of(2026, 12, 1, 0, 0), LocalDateTime.of(2026, 12, 5, 0, 0));

        Itinerary created = service.createItinerary(4L, request);

        assertThat(created.getUser()).isSameAs(owner);
        assertThat(created.getName()).isEqualTo("Tokyo Trip");
        assertThat(created.getCountry()).isSameAs(japan);
    }

    @Test
    @DisplayName("Should ask for a new itinerary to be judged against recent news, like a traveller's")
    void createItinerary_PublishesCreatedEvent() {
        service.createItinerary(4L, new AdminItineraryRequest("Trip", 1L, null, null));

        verify(events).publishEvent(new ItineraryCreatedEvent(10L));
    }

    @Test
    @DisplayName("Should refuse an itinerary with an unknown country, and save nothing")
    void createItinerary_UnknownCountry_Returns400() {
        AdminItineraryRequest request = new AdminItineraryRequest("Trip", 99L, null, null);

        Throwable thrown = catchThrowable(() -> service.createItinerary(4L, request));

        assertBadRequest(thrown);
        verify(itineraries, never()).save(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("Should refuse an itinerary that ends before it starts")
    void createItinerary_EndBeforeStart_Returns400() {
        AdminItineraryRequest request = new AdminItineraryRequest("Trip", 1L,
                LocalDateTime.of(2026, 12, 5, 0, 0), LocalDateTime.of(2026, 12, 1, 0, 0));

        Throwable thrown = catchThrowable(() -> service.createItinerary(4L, request));

        assertBadRequest(thrown);
    }

    @Test
    @DisplayName("Should 404 when listing the itineraries of an account that does not exist")
    void listItineraries_UnknownUser_Returns404() {
        when(users.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listItineraries(99L)).isInstanceOf(UserNotFoundException.class);
    }

    @Test
    @DisplayName("Should move the activities to the new country when an itinerary changes country")
    void updateItinerary_NewCountry_ActivitiesFollow() {
        Itinerary trip = savedTrip(10L, owner);
        Activity activity = savedActivity(20L, trip);
        when(activities.findByItineraryIdOrderByDateStart(10L)).thenReturn(List.of(activity));

        Country thailand = new Country();
        thailand.setId(2L);
        when(countries.findById(2L)).thenReturn(Optional.of(thailand));

        service.updateItinerary(10L, new AdminItineraryRequest("Beach Trip", 2L, null, null));

        assertThat(activity.getCountry()).isSameAs(thailand);
        verify(activities).saveAll(List.of(activity));
    }

    @Test
    @DisplayName("Should drop the verdicts made for the old country and judge the trip again")
    void updateItinerary_NewCountry_ClearsVerdictsAndJudgesAgain() {
        savedTrip(10L, owner);
        List<Recommendation> itsRecommendations = List.of(new Recommendation());
        List<Impact> itsImpacts = List.of(new Impact());
        when(recommendations.findByImpactItineraryId(10L)).thenReturn(itsRecommendations);
        when(impacts.findByItineraryId(10L)).thenReturn(itsImpacts);

        Country france = new Country();
        france.setId(2L);
        when(countries.findById(2L)).thenReturn(Optional.of(france));

        service.updateItinerary(10L, new AdminItineraryRequest("Japan Ski Trip", 2L, null, null));

        // recommendations point at the impacts, so they go first
        InOrder order = inOrder(recommendations, impacts, events);
        order.verify(recommendations).deleteAll(itsRecommendations);
        order.verify(impacts).deleteAll(itsImpacts);
        order.verify(events).publishEvent(new ItineraryCreatedEvent(10L));
    }

    @Test
    @DisplayName("Should keep the verdicts when only the itinerary's name changes")
    void updateItinerary_OnlyName_KeepsVerdicts() {
        savedTrip(10L, owner);

        service.updateItinerary(10L, new AdminItineraryRequest("Renamed Trip", 1L, null, null));

        verify(impacts, never()).deleteAll(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("Should delete an itinerary's recommendations before the itinerary itself")
    void deleteItinerary_RemovesRecommendationsFirst() {
        Itinerary trip = savedTrip(10L, owner);
        List<Recommendation> itsRecommendations = List.of(new Recommendation());
        when(recommendations.findByImpactItineraryId(10L)).thenReturn(itsRecommendations);

        Itinerary deleted = service.deleteItinerary(10L);

        // nothing cascades to recommendations, so the order matters
        InOrder order = inOrder(recommendations, itineraries);
        order.verify(recommendations).deleteAll(itsRecommendations);
        order.verify(itineraries).delete(trip);
        assertThat(deleted).isSameAs(trip);
    }

    // ---------- activities ----------

    @Test
    @DisplayName("Should copy the country from the itinerary onto a new activity")
    void createActivity_CopiesCountryFromItinerary() {
        savedTrip(10L, owner);

        Activity created = service.createActivity(10L,
                new AdminActivityRequest("Onsen", "  ", null, null, null));

        assertThat(created.getCountry()).isSameAs(japan);
        assertThat(created.getAddress()).isNull();   // a blank address is stored as nothing
    }

    @Test
    @DisplayName("Should ask for a new activity to be judged against recent news, like a traveller's")
    void createActivity_PublishesCreatedEvent() {
        savedTrip(10L, owner);

        service.createActivity(10L, new AdminActivityRequest("Onsen", null, null, null, null));

        verify(events).publishEvent(new ActivityCreatedEvent(20L));
    }

    @Test
    @DisplayName("Should delete an activity's recommendations before the activity")
    void deleteActivity_RemovesRecommendationsFirst() {
        Activity activity = savedActivity(20L, savedTrip(10L, owner));
        List<Recommendation> itsRecommendations = List.of(new Recommendation());
        when(recommendations.findByImpactActivityId(20L)).thenReturn(itsRecommendations);

        service.deleteActivity(20L);

        InOrder order = inOrder(recommendations, activities);
        order.verify(recommendations).deleteAll(itsRecommendations);
        order.verify(activities).delete(activity);
    }

    @Test
    @DisplayName("Should drop an activity's verdicts and judge it again when its address changes")
    void updateActivity_NewAddress_ClearsVerdictsAndJudgesAgain() {
        savedActivity(20L, savedTrip(10L, owner));
        List<Recommendation> itsRecommendations = List.of(new Recommendation());
        List<Impact> itsImpacts = List.of(new Impact());
        when(recommendations.findByImpactActivityId(20L)).thenReturn(itsRecommendations);
        when(impacts.findByActivityId(20L)).thenReturn(itsImpacts);

        service.updateActivity(20L, new AdminActivityRequest("Ski at Niseko", "Hakuba", null, null, null));

        InOrder order = inOrder(recommendations, impacts, events);
        order.verify(recommendations).deleteAll(itsRecommendations);
        order.verify(impacts).deleteAll(itsImpacts);
        order.verify(events).publishEvent(new ActivityCreatedEvent(20L));
    }

    @Test
    @DisplayName("Should keep an activity's verdicts when nothing the model was asked about changes")
    void updateActivity_OnlyEndTime_KeepsVerdicts() {
        savedActivity(20L, savedTrip(10L, owner));

        service.updateActivity(20L, new AdminActivityRequest("Ski at Niseko", null, null,
                LocalDateTime.of(2026, 12, 16, 17, 0), null));

        verify(impacts, never()).deleteAll(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    // ---------- impacts ----------

    @Test
    @DisplayName("Should create an impact on the user's own itinerary and activity")
    void createImpact_OwnTripAndActivity_IsSaved() {
        Itinerary trip = savedTrip(10L, owner);
        Activity activity = savedActivity(20L, trip);
        News article = savedNews(30L);

        Impact created = service.createImpact(4L,
                new AdminImpactRequest(30L, 10L, 20L, true, " Lifts are closed ", null, false));

        assertThat(created.getUser()).isSameAs(owner);
        assertThat(created.getItinerary()).isSameAs(trip);
        assertThat(created.getActivity()).isSameAs(activity);
        assertThat(created.getNews()).isSameAs(article);
        assertThat(created.getReason()).isEqualTo("Lifts are closed");
        assertThat(created.getRejected()).isFalse();
        assertThat(created.getAlertEligible()).isFalse();
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("Should refuse an impact on another user's itinerary")
    void createImpact_SomeoneElsesItinerary_Returns400() {
        savedTrip(10L, someoneElse);
        savedNews(30L);

        Throwable thrown = catchThrowable(() -> service.createImpact(4L,
                new AdminImpactRequest(30L, 10L, null, true, "reason", null, false)));

        assertBadRequest(thrown);
        verify(impacts, never()).save(any());
    }

    @Test
    @DisplayName("Should refuse an impact whose activity is on a different itinerary")
    void createImpact_ActivityFromAnotherItinerary_Returns400() {
        savedTrip(10L, owner);
        savedActivity(20L, savedTrip(11L, owner));
        savedNews(30L);

        Throwable thrown = catchThrowable(() -> service.createImpact(4L,
                new AdminImpactRequest(30L, 10L, 20L, true, "reason", null, false)));

        assertBadRequest(thrown);
        verify(impacts, never()).save(any());
    }

    @Test
    @DisplayName("Should mark the impact for an alert and tell the alert listener when asked to")
    void createImpact_SendAlert_PublishesTheEvent() {
        savedTrip(10L, owner);
        savedNews(30L);

        Impact created = service.createImpact(4L,
                new AdminImpactRequest(30L, 10L, null, true, "Rail is suspended", false, true));

        assertThat(created.getAlertEligible()).isTrue();
        verify(events).publishEvent(new ImpactEvaluatedEvent(70L));
    }

    @Test
    @DisplayName("Should refuse an alert for an impact without a reason, since it would never be shown")
    void createImpact_SendAlertWithoutReason_Returns400() {
        savedTrip(10L, owner);
        savedNews(30L);

        Throwable thrown = catchThrowable(() -> service.createImpact(4L,
                new AdminImpactRequest(30L, 10L, null, true, " ", false, true)));

        assertBadRequest(thrown);
        verify(impacts, never()).save(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("Should refuse a second impact for the same news on the same activity")
    void createImpact_SameNewsOnSameActivity_Returns409() {
        savedActivity(20L, savedTrip(10L, owner));
        savedNews(30L);
        Impact existing = savedImpact(71L);
        when(impacts.findFirstByNewsIdAndActivityId(30L, 20L)).thenReturn(Optional.of(existing));

        Throwable thrown = catchThrowable(() -> service.createImpact(4L,
                new AdminImpactRequest(30L, 10L, 20L, false, "not affected", false, false)));

        assertThat(thrown).isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(exception.getReason()).contains("impact 71");
        });
        verify(impacts, never()).save(any());
    }

    @Test
    @DisplayName("Should refuse a second whole trip impact for the same news")
    void createImpact_SameNewsOnWholeTrip_Returns409() {
        savedTrip(10L, owner);
        savedNews(30L);
        Impact existing = savedImpact(71L);
        when(impacts.findFirstByNewsIdAndItineraryIdAndActivityIsNull(30L, 10L)).thenReturn(Optional.of(existing));

        Throwable thrown = catchThrowable(() -> service.createImpact(4L,
                new AdminImpactRequest(30L, 10L, null, true, "rail strike", false, false)));

        assertThat(thrown).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(impacts, never()).save(any());
    }

    @Test
    @DisplayName("Should refuse to blank the reason of an impact that raised an alert")
    void updateImpact_BlankReasonAfterAlert_Returns400() {
        Impact impact = savedImpact(70L);
        impact.setReason("Lifts are closed");
        impact.setAlerts(List.of(new Alert(impact)));

        Throwable thrown = catchThrowable(() -> service.updateImpact(70L,
                new AdminImpactUpdateRequest(true, "  ", false)));

        assertBadRequest(thrown);
        assertThat(impact.getReason()).isEqualTo("Lifts are closed");
        verify(impacts, never()).save(any());
    }

    @Test
    @DisplayName("Should allow a blank reason on an impact with no alert, so it is judged again")
    void updateImpact_BlankReasonWithoutAlert_IsAllowed() {
        Impact impact = savedImpact(70L);
        impact.setReason("Lifts are closed");

        Impact updated = service.updateImpact(70L, new AdminImpactUpdateRequest(true, "  ", false));

        assertThat(updated.getReason()).isNull();
    }

    @Test
    @DisplayName("Should list a user's impacts newest first")
    void listImpacts_NewestFirst() {
        Impact older = new Impact();
        older.setId(1L);
        Impact newer = new Impact();
        newer.setId(2L);
        when(impacts.findByUserId(4L)).thenReturn(List.of(older, newer));

        List<Impact> listed = service.listImpacts(4L);

        assertThat(listed).containsExactly(newer, older);
    }

    @Test
    @DisplayName("Should delete an impact's recommendations before the impact")
    void deleteImpact_RemovesRecommendationsFirst() {
        Impact impact = savedImpact(70L);
        List<Recommendation> itsRecommendations = List.of(new Recommendation());
        when(recommendations.findByImpactIdOrderByIdAsc(70L)).thenReturn(itsRecommendations);

        service.deleteImpact(70L);

        InOrder order = inOrder(recommendations, impacts);
        order.verify(recommendations).deleteAll(itsRecommendations);
        order.verify(impacts).delete(impact);
    }

    // ---------- recommendations ----------

    @Test
    @DisplayName("Should create a pending recommendation with no decision time by default")
    void createRecommendation_DefaultsToPending() {
        savedImpact(70L);

        Recommendation created = service.createRecommendation(70L,
                new AdminRecommendationRequest(Severity.HIGH, Action.CANCEL, "Skip it", null, null));

        assertThat(created.getStatus()).isEqualTo(RecommendationStatus.PENDING);
        assertThat(created.getDecidedAt()).isNull();
        assertThat(created.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Should stamp the decision time when a recommendation is created already accepted")
    void createRecommendation_Accepted_HasDecisionTime() {
        savedImpact(70L);

        Recommendation created = service.createRecommendation(70L,
                new AdminRecommendationRequest(Severity.LOW, Action.MONITOR, null, null, RecommendationStatus.ACCEPTED));

        assertThat(created.getDecidedAt()).isNotNull();
    }

    @Test
    @DisplayName("Should keep the old decision time when the status does not change")
    void updateRecommendation_SameStatus_KeepsDecisionTime() {
        LocalDateTime decided = LocalDateTime.of(2026, 11, 1, 9, 0);
        Recommendation existing = new Recommendation();
        existing.setId(80L);
        existing.setStatus(RecommendationStatus.DISMISSED);
        existing.setDecidedAt(decided);
        when(recommendations.findById(80L)).thenReturn(Optional.of(existing));

        service.updateRecommendation(80L,
                new AdminRecommendationRequest(Severity.MEDIUM, Action.RESCHEDULE, "Go later", null, RecommendationStatus.DISMISSED));

        assertThat(existing.getDecidedAt()).isEqualTo(decided);
        assertThat(existing.getAction()).isEqualTo(Action.RESCHEDULE);
    }

    @Test
    @DisplayName("Should refuse a recommendation pointing at an option that does not exist")
    void createRecommendation_UnknownOption_Returns400() {
        savedImpact(70L);
        when(options.findById(999L)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> service.createRecommendation(70L,
                new AdminRecommendationRequest(Severity.HIGH, Action.REPLACE, null, 999L, null)));

        assertBadRequest(thrown);
        verify(recommendations, never()).save(any());
    }

    @Test
    @DisplayName("Should mark a recommendation written for older preferences as stale")
    void listRecommendations_OlderPreferences_AreStale() {
        UserPreference prefs = new UserPreference(owner);
        when(preferences.findByUserId(4L)).thenReturn(Optional.of(prefs));

        Recommendation outOfDate = new Recommendation();
        outOfDate.setPreferencesVersion(prefs.getUpdatedAt().minusSeconds(60));
        Recommendation upToDate = new Recommendation();
        upToDate.setPreferencesVersion(prefs.getUpdatedAt());
        when(recommendations.findByImpactUserIdOrderByIdDesc(4L)).thenReturn(List.of(outOfDate, upToDate));

        service.listRecommendations(4L);

        assertThat(outOfDate.isStale()).isTrue();
        assertThat(upToDate.isStale()).isFalse();
    }

    @Test
    @DisplayName("Should leave everything fresh for a user who never saved preferences")
    void listRecommendations_NoPreferences_NothingStale() {
        when(preferences.findByUserId(4L)).thenReturn(Optional.empty());

        Recommendation old = new Recommendation();
        old.setPreferencesVersion(Instant.parse("2026-01-01T00:00:00Z"));
        when(recommendations.findByImpactUserIdOrderByIdDesc(4L)).thenReturn(List.of(old));

        service.listRecommendations(4L);

        assertThat(old.isStale()).isFalse();
    }
}
