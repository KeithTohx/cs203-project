package csd.tripsense.auth;

import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;

/**
 * Ends every active login for an account. Call it after an admin disables or demotes someone,
 * or when a user changes their password, so the change takes effect immediately instead of
 * when the old session times out.
 */
@Service
public class SessionService {

    private final SessionRegistry sessionRegistry;

    public SessionService(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    /** @return how many sessions were ended */
    public int expireSessionsFor(String email) {
        int expired = 0;
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (principal instanceof CurrentUser user && user.getEmail().equalsIgnoreCase(email)) {
                for (SessionInformation session : sessionRegistry.getAllSessions(principal, false)) {
                    session.expireNow();
                    expired++;
                }
            }
        }
        return expired;
    }
}