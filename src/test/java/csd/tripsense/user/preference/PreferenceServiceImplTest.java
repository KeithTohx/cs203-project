package csd.tripsense.user.preference;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import csd.tripsense.user.User;
import csd.tripsense.user.UserNotFoundException;
import csd.tripsense.user.UserRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("PreferenceService Unit Tests")
class PreferenceServiceImplTest {

    private static final Long USER_ID = 1L;
    
    @Mock 
    private UserPreferenceRepository preferencesRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks 
    private PreferenceServiceImpl preferenceService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User("john@example.com", "john", "password123", "USER");
        ReflectionTestUtils.setField(testUser, "id", USER_ID);   // User has no setter for its id
    }

    // An existing row with the entity defaults (MEDIUM, SOMEWHAT_FLEXIBLE, BRIEF, SOLO, false, false).
    private UserPreference defaultPrefs() {
        return new UserPreference(testUser);
    }

    private UpdatePreferenceRequest request(RiskTolerance risk, FlexibilityLevel flex,
                                            ExplanationDetail detail, TravelGroup group,
                                            Boolean children, Boolean elderly) {
        return new UpdatePreferenceRequest(risk, flex, detail, group, children, elderly);
    }

    private UpdatePreferenceRequest emptyRequest() {
        return request(null, null, null, null, null, null);
    }
 
    private void stubSaveReturnsArgument() {
        when(preferencesRepository.save(any(UserPreference.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private UserPreference familyPrefsWithChildren() {
        UserPreference p = new UserPreference(testUser);
        p.setTravelGroup(TravelGroup.FAMILY);
        p.setTravellingWithChildren(true);
        return p;
    }

    // ===== GET PREFERENCES TESTS =====

    @Test 
    @DisplayName("Should return the existing preferences without creating a new entry")
    void getPreferences_ShouldReturnExistingPreferences() {
        UserPreference existing = defaultPrefs();
        when(preferencesRepository.findByUserId(USER_ID)).thenReturn(Optional.of(existing));

        UserPreference result = preferenceService.getPreferences(USER_ID);

        assertSame(existing, result);
        verifyNoInteractions(userRepository);
        verify(preferencesRepository, never()).saveAndFlush(any());
        verify(preferencesRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should create a row with default values when the user has none")
    void getPreferences_ShouldCreateDefaults_WhenUserHasNoExistingPreferences() {
        when(preferencesRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(testUser));
        when(preferencesRepository.saveAndFlush(any(UserPreference.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        UserPreference result = preferenceService.getPreferences(USER_ID);

        ArgumentCaptor<UserPreference> captor = ArgumentCaptor.forClass(UserPreference.class);
        verify(preferencesRepository).saveAndFlush(captor.capture());
        UserPreference saved = captor.getValue();

        assertSame(saved, result);
        assertSame(testUser, saved.getUser());
        assertEquals(RiskTolerance.MEDIUM, saved.getRiskTolerance());
        assertEquals(FlexibilityLevel.SOMEWHAT_FLEXIBLE, saved.getFlexibilityLevel());
        assertEquals(ExplanationDetail.BRIEF, saved.getExplanationDetail());
        assertEquals(TravelGroup.SOLO, saved.getTravelGroup());
        assertFalse(saved.isTravellingWithChildren());
        assertFalse(saved.isTravellingWithElderly());
    }

    @Test
    @DisplayName("Should throw UserNotFoundException when the user does not exist")
    void getPreferences_ShouldThrowUserNotFoundException_WhenUserDoesNotExist() {
        Long nonExistentId = 999L;
        when(preferencesRepository.findByUserId(nonExistentId)).thenReturn(Optional.empty());
        when(userRepository.findById(nonExistentId)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> preferenceService.getPreferences(nonExistentId));

        verify(preferencesRepository, never()).saveAndFlush(any());
    }

    // ===== UPDATE PREFERENCES TESTS =====

    @Test
    @DisplayName("Should update every field that is sent")
    void updatePreferences_ShouldUpdate_WhenAllFieldsAreSent() {
        when(preferencesRepository.findByUserId(USER_ID)).thenReturn(Optional.of(defaultPrefs()));
        stubSaveReturnsArgument();

        UserPreference result = preferenceService.updatePreferences(USER_ID,
                request(RiskTolerance.LOW, FlexibilityLevel.FIXED_DATES, ExplanationDetail.DETAILED,
                        TravelGroup.FAMILY, true, true));

        assertEquals(RiskTolerance.LOW, result.getRiskTolerance());
        assertEquals(FlexibilityLevel.FIXED_DATES, result.getFlexibilityLevel());
        assertEquals(ExplanationDetail.DETAILED, result.getExplanationDetail());
        assertEquals(TravelGroup.FAMILY, result.getTravelGroup());
        assertTrue(result.isTravellingWithChildren());
        assertTrue(result.isTravellingWithElderly());
        verify(preferencesRepository).save(result);
    }

    @Test
    @DisplayName("Should leave everything unchanged when all fields are null")
    void updatePreferences_ShouldLeaveValuesUnchanged_WhenFieldsAreNull() {
        UserPreference existing = familyPrefsWithChildren();
        existing.setRiskTolerance(RiskTolerance.HIGH);
        existing.setFlexibilityLevel(FlexibilityLevel.VERY_FLEXIBLE);
        existing.setExplanationDetail(ExplanationDetail.DETAILED);
        when(preferencesRepository.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
        stubSaveReturnsArgument();

        UserPreference result = preferenceService.updatePreferences(USER_ID, emptyRequest());

        assertEquals(RiskTolerance.HIGH, result.getRiskTolerance());
        assertEquals(FlexibilityLevel.VERY_FLEXIBLE, result.getFlexibilityLevel());
        assertEquals(ExplanationDetail.DETAILED, result.getExplanationDetail());
        assertEquals(TravelGroup.FAMILY, result.getTravelGroup());
        assertTrue(result.isTravellingWithChildren());
        assertFalse(result.isTravellingWithElderly());
    }

    @Test
    @DisplayName("Should only update the fields that was sent")
    void updatePreferences_ShouldPartialUpdate_WhenOnlySomeFieldsAreSent() {
        UserPreference existing = defaultPrefs();
        existing.setRiskTolerance(RiskTolerance.LOW);
        when(preferencesRepository.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
        stubSaveReturnsArgument();

        UserPreference result = preferenceService.updatePreferences(USER_ID,
                request(null, null, ExplanationDetail.DETAILED, null, null, null));

        assertEquals(ExplanationDetail.DETAILED, result.getExplanationDetail());
        assertEquals(RiskTolerance.LOW, result.getRiskTolerance());
        assertEquals(FlexibilityLevel.SOMEWHAT_FLEXIBLE, result.getFlexibilityLevel());
        assertEquals(TravelGroup.SOLO, result.getTravelGroup());
    }

    @Test
    @DisplayName("Should create the preference row first when the user has none, then apply the update")
    void updatePreferences_ShouldCreateRowThenUpdatePreferences() {
        when(preferencesRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(testUser));
        when(preferencesRepository.saveAndFlush(any(UserPreference.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        stubSaveReturnsArgument();

        UserPreference result = preferenceService.updatePreferences(USER_ID,
                request(RiskTolerance.HIGH, null, null, null, null, null));

        assertEquals(RiskTolerance.HIGH, result.getRiskTolerance());
        verify(preferencesRepository).saveAndFlush(any(UserPreference.class));
        verify(preferencesRepository).save(result);
    }

    @Test
    @DisplayName("Should throw UserNotFoundException and updates nothing when the user does not exist")
    void updatePreferences_ShouldThrowUserNotFoundException_WhenUserDoesNotExist() {
        when(preferencesRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class,
                () -> preferenceService.updatePreferences(USER_ID, emptyRequest()));

        verify(preferencesRepository, never()).save(any());
    }

    // --- validation for travelGroup (SOLO/FAMILY) and travellingWithChildren/travellingWithElderly ---
    @Test
    @DisplayName("Should reject updating when travelGroup=SOLO and travellingWithChildren=true ")
    void updatePreferences_ShouldReject_WhenTravelGroupIsSoloAndChildrenIsTrue() {
        when(preferencesRepository.findByUserId(USER_ID)).thenReturn(Optional.of(defaultPrefs()));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> preferenceService.updatePreferences(USER_ID,
                        request(null, null, null, null, true, null)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(preferencesRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should reject updating when travelGroup=SOLO and travellingWithElderly=true")
    void updatePreferences_ShouldReject_WhenTravelGroupIsSoloAndElderlyIsTrue() {
        when(preferencesRepository.findByUserId(USER_ID)).thenReturn(Optional.of(defaultPrefs()));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> preferenceService.updatePreferences(USER_ID,
                        request(null, null, null, null, null, true)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(preferencesRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should reject explicit updating travelGroup=SOLO when travellingWithChildren=true")
    void updatePreferences_ShouldRejectExplicitSolo_WhenChildrenIsTrue() {
        when(preferencesRepository.findByUserId(USER_ID)).thenReturn(Optional.of(defaultPrefs()));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> preferenceService.updatePreferences(USER_ID,
                        request(null, null, null, TravelGroup.SOLO, true, false)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("Should reject updating travelGroup=SOLO when travellingWithChildren=true on the saved row")
    void updatePreferences_ShouldRejectUpdateToSolo_WhenChildrenIsTrue() {
        when(preferencesRepository.findByUserId(USER_ID)).thenReturn(Optional.of(familyPrefsWithChildren()));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> preferenceService.updatePreferences(USER_ID,
                        request(null, null, null, TravelGroup.SOLO, null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(preferencesRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should allow updating travelGroup=SOLO when travellingWithChildren=false in the same request")
    void updatePreferences_ShouldAllowUpdateToSolo_WhenChildrenIsFalse() {
        when(preferencesRepository.findByUserId(USER_ID)).thenReturn(Optional.of(familyPrefsWithChildren()));
        stubSaveReturnsArgument();

        UserPreference result = preferenceService.updatePreferences(USER_ID,
                request(null, null, null, TravelGroup.SOLO, false, false));

        assertEquals(TravelGroup.SOLO, result.getTravelGroup());
        assertFalse(result.isTravellingWithChildren());
        assertFalse(result.isTravellingWithElderly());
        verify(preferencesRepository).save(result);
    }

    @Test
    @DisplayName("Should allow travellingWithChildren and travellingWithElderly when travelGroup=FAMILY")
    void updatePreferences_ShouldAllowChildrenAndElderly_WhenTravelGroupIsFamily() {
        when(preferencesRepository.findByUserId(USER_ID)).thenReturn(Optional.of(defaultPrefs()));
        stubSaveReturnsArgument();

        UserPreference result = preferenceService.updatePreferences(USER_ID,
                request(null, null, null, TravelGroup.FAMILY, true, true));

        assertEquals(TravelGroup.FAMILY, result.getTravelGroup());
        assertTrue(result.isTravellingWithChildren());
        assertTrue(result.isTravellingWithElderly());
    }

}
