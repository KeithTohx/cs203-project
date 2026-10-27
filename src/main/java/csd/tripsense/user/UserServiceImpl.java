package csd.tripsense.user;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import csd.tripsense.chat.ChatbotService;
import csd.tripsense.impact.ImpactRepository;
import csd.tripsense.itinerary.ItineraryRepository;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ItineraryRepository itineraryRepository;
    private final ImpactRepository impactRepository;
    private final ChatbotService chatbotService;
    
    public UserServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder,
                        ItineraryRepository itineraryRepository, ImpactRepository impactRepository,
                        ChatbotService chatbotService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.itineraryRepository = itineraryRepository;
        this.impactRepository = impactRepository;
        this.chatbotService = chatbotService;
    }

    @Override
    @Transactional
    public User register(User request) {
        requirePasswordWithinBcryptLimit(request.getPassword());
        if (userRepository.existsByEmailIgnoreCase(request.getEmail())) {
            throw new AccountRuleException(AccountRuleException.Kind.CONFLICT,
                    "email '" + request.getEmail() + "' is already taken");
        }
        if (userRepository.existsByUsernameIgnoreCase(request.getUsername())) {
            throw new AccountRuleException(AccountRuleException.Kind.CONFLICT,
                    "username '" + request.getUsername() + "' is already taken");
        }

        // Fresh entity: the client can't set the id, role is always USER, password is hashed.
        User user = new User(request.getEmail(), request.getUsername(),
                passwordEncoder.encode(request.getPassword()), "USER");
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Two identical registrations at the same instant: the database's unique constraint wins.
            throw new AccountRuleException(AccountRuleException.Kind.CONFLICT, "email or username is already taken");
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
            throw new AccountRuleException(AccountRuleException.Kind.CONFLICT,
                    "username '" + newUsername + "' is already taken");
        }

        user.setUsername(newUsername);
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Someone else took the name between the check and the save: the unique constraint wins.
            throw new AccountRuleException(AccountRuleException.Kind.CONFLICT,
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
            throw new AccountRuleException(AccountRuleException.Kind.INVALID_INPUT, "current password is incorrect");
        }
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new AccountRuleException(AccountRuleException.Kind.INVALID_INPUT,
                    "new password must be different from the current password");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    private User findById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("No user found with id " + userId));
    }

    // BCrypt only reads the first 72 BYTES, and one character can take several bytes.
    private static void requirePasswordWithinBcryptLimit(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new AccountRuleException(AccountRuleException.Kind.INVALID_INPUT, "password must be at most 72 bytes");
        }
    }

    @Override
    @Transactional
    public void deleteAccount(Long userId, String currentPassword) {
        User user = findById(userId);

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new AccountRuleException(AccountRuleException.Kind.INVALID_INPUT, "current password is incorrect");
        }
        if ("ADMIN".equals(user.getRole()) && user.isEnabled() && userRepository.countEnabledByRole("ADMIN") <= 1) {
            throw new AccountRuleException(AccountRuleException.Kind.CONFLICT, "the last admin account cannot be deleted");
        }

        chatbotService.deleteAllConversationsFor(userId);   // chat history is personal data too

        impactRepository.deleteByUserId(userId);
        itineraryRepository.deleteAll(itineraryRepository.findByUserId(userId));  // cascades to activities and impacts
        itineraryRepository.flush();
        userRepository.delete(user);
    }

    @Override
    @Transactional
    public void deleteUserAndData(Long userId) {
        User user = findById(userId);

        if ("ADMIN".equals(user.getRole()) && user.isEnabled() && userRepository.countEnabledByRole("ADMIN") <= 1) {
            throw new AccountRuleException(AccountRuleException.Kind.CONFLICT, "the last admin account cannot be deleted");
        }

        chatbotService.deleteAllConversationsFor(userId);   // chat history is personal data too

        impactRepository.deleteByUserId(userId);
        itineraryRepository.deleteAll(itineraryRepository.findByUserId(userId));  // cascades to activities and impacts
        itineraryRepository.flush();
        userRepository.delete(user);
    }
}
