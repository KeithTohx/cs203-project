package csd.tripsense.admin;

import csd.tripsense.audit.AuditEntryResponse;
import csd.tripsense.user.User;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Admin-only operations. Every method checks the role itself (second layer of E1-07), in addition to
 * the /api/admin/** URL rule in SecurityConfig.
 */
public interface AdminService {

    /** Paged list of accounts, searchable by part of the email or username. The page size is capped at 100. */
    @PreAuthorize("hasRole('ADMIN')")
    Page<User> listUsers(String search, int page, int size);

    /** @throws csd.tripsense.user.UserNotFoundException if no account has that id */
    @PreAuthorize("hasRole('ADMIN')")
    User getUser(Long id);

    /**
     * Stops an account from logging in WITHOUT touching its data (E1-06). An admin cannot disable
     * their own account (400). Disabling an already-disabled account changes nothing.
     * @throws csd.tripsense.user.UserNotFoundException if no account has that id
     */
    @PreAuthorize("hasRole('ADMIN')")
    User disableUser(Long id, Long actingAdminId);

    /** Lets a disabled account log in again. @throws csd.tripsense.user.UserNotFoundException if no such account */
    @PreAuthorize("hasRole('ADMIN')")
    User enableUser(Long id);

    /**
     * Permanently deletes an account AND its data (impacts, activities, itineraries).
     * An admin cannot delete their own account (400).
     * @return the deleted account, so the caller can record and clean up after it
     * @throws csd.tripsense.user.UserNotFoundException if no account has that id
     */
    @PreAuthorize("hasRole('ADMIN')")
    User deleteUser(Long id, Long actingAdminId);

    /** The whole audit trail, newest first, filtered by part of the actor's email and/or an exact action. */
    @PreAuthorize("hasRole('ADMIN')")
    Page<AuditEntryResponse> reviewActions(String email, String action, int page, int size);

    /** What one account did and what was done to it, newest first; works for deleted accounts too. */
    @PreAuthorize("hasRole('ADMIN')")
    Page<AuditEntryResponse> reviewUserActions(Long userId, String action, int page, int size);
}
