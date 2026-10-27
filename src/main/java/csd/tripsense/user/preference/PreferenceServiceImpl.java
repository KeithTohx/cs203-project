package csd.tripsense.user.preference;

import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import csd.tripsense.user.User;
import csd.tripsense.user.UserNotFoundException;
import csd.tripsense.user.UserRepository;

@Service
public class PreferenceServiceImpl implements PreferenceService {

    private final UserPreferenceRepository preferencesRepository;
    private final UserRepository userRepository;

    public PreferenceServiceImpl(UserPreferenceRepository preferencesRepository, UserRepository userRepository) {
        this.preferencesRepository = preferencesRepository;
        this.userRepository = userRepository;
    }

    
    @Override
    @Transactional
    public UserPreference getPreferences(Long userId) {
        return findOrCreatePreferences(userId);
    }

    @Override
    @Transactional
    public UserPreference updatePreferences(Long userId, UpdatePreferenceRequest r) {
        UserPreference prefs = findOrCreatePreferences(userId);

        if (r.riskTolerance() != null)          prefs.setRiskTolerance(r.riskTolerance());
        if (r.flexibilityLevel() != null)       prefs.setFlexibilityLevel(r.flexibilityLevel());
        if (r.explanationDetail() != null)      prefs.setExplanationDetail(r.explanationDetail());
        if (r.travelGroup() != null)            prefs.setTravelGroup(r.travelGroup());
        if (r.travellingWithChildren() != null) prefs.setTravellingWithChildren(r.travellingWithChildren());
        if (r.travellingWithElderly() != null)  prefs.setTravellingWithElderly(r.travellingWithElderly());

        // Validate the final state so a partial update can't leave it contradictory.
        if (prefs.getTravelGroup() == TravelGroup.SOLO
                && (prefs.isTravellingWithChildren() || prefs.isTravellingWithElderly())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "children or elderly can't be set when travelling solo; change the travel group first");
        }

        return preferencesRepository.save(prefs);
    }

    private User findById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("No user found with id " + userId));
    }

    // Existing users have no row yet, so create defaults on first access.
    private UserPreference findOrCreatePreferences(Long userId) {
        Optional<UserPreference> existing = preferencesRepository.findByUserId(userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        User user = findById(userId);
        try {
            return preferencesRepository.saveAndFlush(new UserPreference(user));
        } catch (DataIntegrityViolationException e) {
            // Two first requests at the same instant: the unique constraint on user_id wins.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "preferences are being created, please retry");
        }
    }

}
