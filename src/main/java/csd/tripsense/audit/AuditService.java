package csd.tripsense.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Records who did what, and lets an admin review it.
 *
 * Call record(...) from controllers and handlers AFTER the action has succeeded and OUTSIDE any
 * @Transactional method: the entry is written in its own short transaction, and a failure to
 * write it is logged but never breaks the action itself.
 *
 * Never pass passwords, tokens or other secrets in any argument.
 */
@Service
public class AuditService {

    public static final String ROLE_CHANGED = "ROLE_CHANGED";
    public static final String LOGIN = "LOGIN";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String LOGOUT = "LOGOUT";
    public static final String REGISTER = "REGISTER";
    public static final String PROFILE_UPDATED = "PROFILE_UPDATED";
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    public static final String ACCOUNT_DELETED = "ACCOUNT_DELETED";
    public static final String ACCOUNT_DISABLED = "ACCOUNT_DISABLED";
    public static final String ACCOUNT_ENABLED = "ACCOUNT_ENABLED";
    public static final String ITINERARY_CREATED = "ITINERARY_CREATED";
    public static final String ITINERARY_UPDATED = "ITINERARY_UPDATED";
    public static final String ITINERARY_DELETED = "ITINERARY_DELETED";

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    public void record(String action, Long actorId, String actorEmail, Long targetId, String details) {
        try {
            repository.save(new AuditLog(action, actorId, truncate(actorEmail, 254), targetId, truncate(details, 500)));
        } catch (RuntimeException e) {
            log.error("Could not write audit entry for action {}", action, e);
        }
    }

    /** Newest first. Both filters are optional: email = part of the actor's email, action = exact action name. */
    public Page<AuditEntryResponse> search(String email, String action, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        boolean hasEmail = email != null && !email.isBlank();
        boolean hasAction = action != null && !action.isBlank();

        Page<AuditLog> result;
        if (hasEmail && hasAction) {
            result = repository.findByActorEmailContainingIgnoreCaseAndActionOrderByIdDesc(
                    email.trim(), action.trim().toUpperCase(Locale.ROOT), pageable);
        } else if (hasEmail) {
            result = repository.findByActorEmailContainingIgnoreCaseOrderByIdDesc(email.trim(), pageable);
        } else if (hasAction) {
            result = repository.findByActionOrderByIdDesc(action.trim().toUpperCase(Locale.ROOT), pageable);
        } else {
            result = repository.findAllByOrderByIdDesc(pageable);
        }
        return result.map(AuditEntryResponse::from);
    }

    /**
     * What one account did, and what was done to it, newest first. The action filter is optional.
     * Works for deleted accounts too: the trail outlives the account.
     */
    public Page<AuditEntryResponse> searchForUser(Long userId, String action, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<AuditLog> result = (action == null || action.isBlank())
                ? repository.findForUser(userId, pageable)
                : repository.findForUserAndAction(userId, action.trim().toUpperCase(Locale.ROOT), pageable);
        return result.map(AuditEntryResponse::from);
    }

    private static String truncate(String value, int max) {
        return (value == null || value.length() <= max) ? value : value.substring(0, max);
    }
}
