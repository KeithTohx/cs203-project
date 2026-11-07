package csd.tripsense.user.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import csd.tripsense.activity.ActivityCategory;
import csd.tripsense.user.User;
import csd.tripsense.user.UserRepository;

/**
 * The preferred categories part of updatePreferences, kept apart from PreferenceServiceImplTest.
 *
 * The rules being tested:
 *   - only indoor categories may be preferred, because the recommendations only ever suggest indoor
 *     places and an outdoor preference could never be honoured
 *   - null means "leave it alone", an empty set means "clear it", anything else replaces the set
 *
 * It assumes UpdatePreferenceRequest has preferredCategories as its LAST component, after
 * travellingWithElderly. If yours is in a different position, change the request(...) helper.
 */
class PreferenceServiceCategoriesTest {

    private static final Long USER_ID = 1L;

    private UserPreferenceRepository preferencesRepository;
    private PreferenceServiceImpl service;
    private UserPreference existing;

    @BeforeEach
    void setUp() {
        preferencesRepository = mock(UserPreferenceRepository.class);
        service = new PreferenceServiceImpl(preferencesRepository, mock(UserRepository.class));

        // an existing row, so the service never needs to create one
        existing = new UserPreference(mock(User.class));
        when(preferencesRepository.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
        when(preferencesRepository.save(any(UserPreference.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // an update that sends only the categories (null for everything else = "not sent")
    private UpdatePreferenceRequest request(Set<ActivityCategory> categories) {
        return new UpdatePreferenceRequest(null, null, null, null, null, null, categories);
    }

    // ----- what is accepted -----

    @ParameterizedTest
    @EnumSource(value = ActivityCategory.class,
            names = { "MUSEUM_GALLERY", "FOOD_DINING", "SHOPPING", "WELLNESS_SPA" })
    void everyIndoorCategoryCanBePreferred(ActivityCategory category) {
        UserPreference result = service.updatePreferences(USER_ID, request(Set.of(category)));

        assertThat(result.getPreferredCategories()).containsExactly(category);
    }

    @Test
    void severalCategoriesCanBePreferredAtOnce() {
        UserPreference result = service.updatePreferences(USER_ID,
                request(Set.of(ActivityCategory.SHOPPING, ActivityCategory.FOOD_DINING)));

        assertThat(result.getPreferredCategories())
                .containsExactlyInAnyOrder(ActivityCategory.SHOPPING, ActivityCategory.FOOD_DINING);
        verify(preferencesRepository).save(result);
    }

    @Test
    void theNewSetReplacesTheOldOneInsteadOfAddingToIt() {
        existing.setPreferredCategories(new HashSet<>(Set.of(ActivityCategory.MUSEUM_GALLERY)));

        UserPreference result = service.updatePreferences(USER_ID, request(Set.of(ActivityCategory.SHOPPING)));

        // museum is gone, so changing your mind does not leave the old choice behind
        assertThat(result.getPreferredCategories()).containsExactly(ActivityCategory.SHOPPING);
    }

    @Test
    void anEmptySetClearsThePreference() {
        existing.setPreferredCategories(new HashSet<>(Set.of(ActivityCategory.SHOPPING)));

        UserPreference result = service.updatePreferences(USER_ID, request(Set.of()));

        assertThat(result.getPreferredCategories()).isEmpty();
    }

    @Test
    void nullLeavesThePreferenceAlone() {
        existing.setPreferredCategories(new HashSet<>(Set.of(ActivityCategory.SHOPPING)));

        // a settings screen that only changed the risk setting sends no categories at all
        UserPreference result = service.updatePreferences(USER_ID, request(null));

        assertThat(result.getPreferredCategories()).containsExactly(ActivityCategory.SHOPPING);
    }

    @Test
    void aSoloTravellerCanStillHavePreferredCategories() {
        // the solo rule is about children and elderly, it has nothing to do with taste
        assertThat(existing.getTravelGroup()).isEqualTo(TravelGroup.SOLO);

        UserPreference result = service.updatePreferences(USER_ID, request(Set.of(ActivityCategory.SHOPPING)));

        assertThat(result.getPreferredCategories()).containsExactly(ActivityCategory.SHOPPING);
    }

    // ----- what is rejected -----

    @ParameterizedTest
    @EnumSource(value = ActivityCategory.class,
            names = { "SKI_SNOW", "BEACH_WATER", "OUTDOOR_NATURE", "SIGHTSEEING" })
    void anOutdoorCategoryIsRejectedWith400(ActivityCategory category) {
        assertThatThrownBy(() -> service.updatePreferences(USER_ID, request(Set.of(category))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(preferencesRepository, never()).save(any());
    }

    @Test
    void oneOutdoorCategoryAmongGoodOnesRejectsTheWholeRequest() {
        existing.setPreferredCategories(new HashSet<>(Set.of(ActivityCategory.MUSEUM_GALLERY)));

        assertThatThrownBy(() -> service.updatePreferences(USER_ID,
                request(Set.of(ActivityCategory.SHOPPING, ActivityCategory.SKI_SNOW))))
                .isInstanceOf(ResponseStatusException.class);

        // nothing was half applied, the old choice is still there and nothing was saved
        assertThat(existing.getPreferredCategories()).containsExactly(ActivityCategory.MUSEUM_GALLERY);
        verify(preferencesRepository, never()).save(any());
    }

    @Test
    void theErrorTellsTheClientWhichCategoriesAreAllowed() {
        assertThatThrownBy(() -> service.updatePreferences(USER_ID, request(Set.of(ActivityCategory.SKI_SNOW))))
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getReason())
                                .contains("MUSEUM_GALLERY")
                                .contains("FOOD_DINING")
                                .contains("SHOPPING")
                                .contains("WELLNESS_SPA"));
    }

    @Test
    void aNullInsideTheSetIsRejectedRatherThanThrowingANullPointer() {
        // json like {"preferredCategories": [null]} gets this far. Set.of cannot hold a null, so build it by hand
        Set<ActivityCategory> withNull = new HashSet<>(Arrays.asList(ActivityCategory.SHOPPING, null));

        assertThatThrownBy(() -> service.updatePreferences(USER_ID, request(withNull)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(preferencesRepository, never()).save(any());
    }
}
