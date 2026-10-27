package csd.tripsense.itinerary;

import csd.tripsense.auth.CurrentUser;
import csd.tripsense.impact.Impact;
import csd.tripsense.impact.ImpactNotFoundException;
import csd.tripsense.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;


import java.util.List;
import java.util.Objects;

/**
 * The one shared ownership rule for itineraries: a user may see and change their OWN itineraries;
 * an ADMIN may see and change all of them. Anyone else gets "not found" (404), exactly as if the
 * itinerary did not exist, so ids cannot be probed to find out what exists.
 *
 * Activities and impacts can reuse this: call requireAccess(activity.getItinerary(), me).
 */
@Component
public class ItineraryAccess {

    private final UserRepository userRepository;

    public ItineraryAccess(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public boolean isAdmin(CurrentUser me) {
        return me != null && "ADMIN".equals(me.getRole());
    }

    /** Owner or admin. */
    public boolean canAccess(Itinerary itinerary, CurrentUser me) {
        requireLoggedIn(me);
        return isAdmin(me)
                || (itinerary.getUser() != null && Objects.equals(itinerary.getUser().getId(), me.getId()));
    }

    /** Returns the itinerary if the caller may access it, otherwise throws 404. */
    public Itinerary requireAccess(Itinerary itinerary, CurrentUser me) {
        if (!canAccess(itinerary, me)) {
            throw new ItineraryNotFoundException(itinerary.getId());
        }
        return itinerary;
    }

    // an impact belongs to whoever owns its itinerary. one with no itinerary is admin only.
    // anyone else gets "not found" (404), the same as for itineraries
    public Impact requireAccess(Impact impact, CurrentUser me) {
        requireLoggedIn(me);

        boolean allowed = impact.getItinerary() == null
                ? isAdmin(me)
                : canAccess(impact.getItinerary(), me);

        if (!allowed) {
            throw new ImpactNotFoundException(impact.getId());
        }
        return impact;
    }

    /** Keeps only the itineraries the caller may access (everything for an admin). */
    public List<Itinerary> visibleTo(List<Itinerary> itineraries, CurrentUser me) {
        requireLoggedIn(me);
        return itineraries.stream().filter(i -> canAccess(i, me)).toList();
    }

    /** For "all itineraries of user X": only X themselves or an admin. Others get 404. */
    public void requireSelfOrAdmin(Long userId, CurrentUser me) {
        requireLoggedIn(me);
        if (!isAdmin(me) && !Objects.equals(me.getId(), userId)) {
            throw new ItineraryNotFoundException("User (ID: " + userId + ") has no existing itineraries.");
        }
    }

    /**
     * A new itinerary always belongs to the caller, never to whoever the request body names,
     * and it can never reuse an existing id (which would overwrite that itinerary).
     */
    public Itinerary prepareNew(Itinerary itinerary, CurrentUser me) {
        requireLoggedIn(me);
        itinerary.setId(null);
        itinerary.setUser(userRepository.findById(me.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account no longer exists")));
        return itinerary;
    }

    private void requireLoggedIn(CurrentUser me) {
        if (me == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not logged in");
        }
    }
}
