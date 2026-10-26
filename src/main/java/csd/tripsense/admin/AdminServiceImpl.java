package csd.tripsense.admin;

import csd.tripsense.audit.AuditEntryResponse;
import csd.tripsense.audit.AuditService;
import csd.tripsense.user.User;
import csd.tripsense.user.UserNotFoundException;
import csd.tripsense.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

@Service
public class AdminServiceImpl implements AdminService {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;
    private final AuditService auditService;
    private final EntityManager entityManager;

    public AdminServiceImpl(UserRepository userRepository, AuditService auditService, EntityManager entityManager) {
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.entityManager = entityManager;
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "admins cannot disable their own account");
        }
        User user = findUser(id);
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "admins cannot delete their own account");
        }
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("No user found with id " + id));

        // Children before parents, so this also works once foreign keys are enforced (PostgreSQL, E7-08).
        // Plain SQL on purpose: it only needs the table and column names, not the other teams' classes.
        entityManager.createNativeQuery(
                "delete from impact where user_id = :id"
                + " or itinerary_id in (select id from itinerary where user_id = :id)"
                + " or activity_id in (select a.id from activity a join itinerary i on a.itinerary_id = i.id"
                + " where i.user_id = :id)")
                .setParameter("id", id).executeUpdate();
        entityManager.createNativeQuery(
                "delete from activity where itinerary_id in (select id from itinerary where user_id = :id)")
                .setParameter("id", id).executeUpdate();
        entityManager.createNativeQuery("delete from itinerary where user_id = :id")
                .setParameter("id", id).executeUpdate();
        userRepository.delete(user);
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

    private User findUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("No user found with id " + id));
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }
}
