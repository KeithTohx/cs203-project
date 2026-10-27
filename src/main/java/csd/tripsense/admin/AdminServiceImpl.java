package csd.tripsense.admin;

import csd.tripsense.audit.AuditEntryResponse;
import csd.tripsense.audit.AuditService;
import csd.tripsense.user.User;
import csd.tripsense.user.UserNotFoundException;
import csd.tripsense.user.UserRepository;
import csd.tripsense.user.UserService;
import csd.tripsense.user.Role;
import csd.tripsense.user.AccountRuleException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class AdminServiceImpl implements AdminService {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;
    private final AuditService auditService;
    private final UserService userService;

    public AdminServiceImpl(UserRepository userRepository, AuditService auditService, UserService userService) {
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.userService = userService;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<User> listUsers(String search, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size), Sort.by("id"));
        if (search == null || search.isBlank()) {
            return userRepository.findAll(pageable);
        }
        String term = search.trim();
        return userRepository.findByEmailContainingIgnoreCaseOrUsernameContainingIgnoreCase(term, term, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public User getUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("No user found with id " + id));
    }

    @Override
    @Transactional
    public User disableUser(Long id, Long actingAdminId) {
        if (Objects.equals(id, actingAdminId)) {
            throw new AccountRuleException(AccountRuleException.Kind.INVALID_INPUT, "admins cannot disable their own account");
        }
        User user = findUser(id);
        requireOrdinaryAccount(user);
        user.setEnabled(false);   // nothing else is touched: trips and history stay (E1-06)
        return userRepository.save(user);
    }

    @Override
    @Transactional
    public User enableUser(Long id) {
        User user = findUser(id);
        user.setEnabled(true);
        return userRepository.save(user);
    }

    @Override
    @Transactional
    public User deleteUser(Long id, Long actingAdminId) {
        if (Objects.equals(id, actingAdminId)) {
            throw new AccountRuleException(AccountRuleException.Kind.INVALID_INPUT, "admins cannot delete their own account");
        }
        User user = findUser(id);

        requireOrdinaryAccount(user);

        // The same cleanup as a user deleting themselves (chat history, impacts, itineraries and their
        // activities, then the account), so the two paths cannot drift apart.
        userService.deleteUserAndData(id);
        return user;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditEntryResponse> reviewUserActions(Long userId, String action, int page, int size) {
        return auditService.searchForUser(userId, action, Math.max(page, 0), clampSize(size));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditEntryResponse> reviewActions(String email, String action, int page, int size) {
        return auditService.search(email, action, Math.max(page, 0), clampSize(size));
    }

    @Override
    @Transactional
    public User changeRole(Long id, Role role, Long actingAdminId) {
        if (Objects.equals(id, actingAdminId)) {
            throw new AccountRuleException(AccountRuleException.Kind.INVALID_INPUT, "admins cannot change their own role");
        }
        Objects.requireNonNull(role, "role is required");
        User user = findUser(id);
        if ("ADMIN".equals(user.getRole()) && role != Role.ADMIN && user.isEnabled()
                && userRepository.countEnabledByRole("ADMIN") <= 1) {
            throw new AccountRuleException(AccountRuleException.Kind.CONFLICT, "the last enabled admin cannot be demoted");
        }
        user.setRole(role);
        return userRepository.saveAndFlush(user);
    }

    private void requireOrdinaryAccount(User user) {
        if ("ADMIN".equals(user.getRole())) {
            throw new AccountRuleException(AccountRuleException.Kind.CONFLICT,
                    "admin accounts must be demoted through role management before disabling or deleting");
        }
    }

    private User findUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("No user found with id " + id));
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }
}
