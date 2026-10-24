package csd.tripsense.user;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import csd.tripsense.user.preference.TravelGroup;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Optional;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final UserPreferenceRepository preferencesRepository;
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserRepository userRepository, UserPreferenceRepository preferencesRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.preferencesRepository = preferencesRepository; 
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public User register(User request) {
        requirePasswordWithinBcryptLimit(request.getPassword());
        if (userRepository.existsByEmailIgnoreCase(request.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "email '" + request.getEmail() + "' is already taken");
        }
        if (userRepository.existsByUsernameIgnoreCase(request.getUsername())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "username '" + request.getUsername() + "' is already taken");
        }

        // Fresh entity: the client can't set the id, role is always USER, password is hashed.
        User user = new User(request.getEmail(), request.getUsername(),
                passwordEncoder.encode(request.getPassword()), "USER");
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Two identical registrations at the same instant: the database's unique constraint wins.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email or username is already taken");
        }
    }

    @Override
    public User getByUsername(String username) {
        return userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> UserNotFoundException.forUsername(username));
    }

    @Override
    @Transactional
    public User updateUsername(Long userId, String newUsername) {
        User user = findById(userId);

        // The check must skip the user's own row, otherwise "John" -> "john" would count as a clash.
        Optional<User> owner = userRepository.findByUsernameIgnoreCase(newUsername);
        if (owner.isPresent() && !owner.get().getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "username '" + newUsername + "' is already taken");
        }

        user.setUsername(newUsername);
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Someone else took the name between the check and the save: the unique constraint wins.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "username '" + newUsername + "' is already taken");
        }
    }

    @Override
    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        requirePasswordWithinBcryptLimit(newPassword);

        // Reload from the database: the session copy of the hash is wiped after login.
        User user = findById(userId);
        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "current password is incorrect");
        }
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "new password must be different from the current password");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
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

    // BCrypt only reads the first 72 BYTES, and one character can take several bytes.
    private static void requirePasswordWithinBcryptLimit(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "password must be at most 72 bytes");
        }
    }
}
