package csd.tripsense.auth;

import csd.tripsense.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;


/**
 * Ends every active login for an account. Call it after an admin disables or demotes someone,
 * or when a user changes their password, so the change takes effect immediately instead of
 * when the old session times out.
 *
 * Also refreshes the logged-in user stored in the CURRENT session after a profile change, so
 * GET /api/auth/me does not keep returning the old username.
 */
@Service
public class SessionService {

    private final SessionRegistry sessionRegistry;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

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

    /**
     * Replaces the logged-in user in the current session with one built from the saved account,
     * so a changed username shows up immediately. The session itself (id, cookie) is kept.
     * Other sessions of the same account keep the old username until their next login.
     */
    public void refreshCurrentSession(User user, HttpServletRequest request, HttpServletResponse response) {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();

        CurrentUser refreshed = CurrentUser.from(user);
        refreshed.eraseCredentials();   // same as after login: the hash is not kept in the session

        UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.authenticated(
                refreshed, null, refreshed.getAuthorities());
        if (current != null) {
            token.setDetails(current.getDetails());
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(token);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    /** Ends every login of this account, including the current one, and clears the session cookie. */
    public void endAllSessions(String email, HttpServletRequest request, HttpServletResponse response) {
        expireSessionsFor(email);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(request, response, auth);        // invalidates session, clears context
        new CookieClearingLogoutHandler("JSESSIONID").logout(request, response, auth);
    }
}
