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

/** Ownership checks for personal trip data, including accounts with the ADMIN role. */
@Component
public class ItineraryAccess {

    private final UserRepository userRepository;

    public ItineraryAccess(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** Only the owner. */
    public boolean canAccess(Itinerary itinerary, CurrentUser me) {
        requireLoggedIn(me);
        return itinerary != null && itinerary.getUser() != null
                && Objects.equals(itinerary.getUser().getId(), me.getId());
    }

    /** Returns the itinerary if the caller may access it, otherwise throws 404. */
    public Itinerary requireAccess(Itinerary itinerary, CurrentUser me) {
        if (!canAccess(itinerary, me)) {
            throw new ItineraryNotFoundException(itinerary == null ? null : itinerary.getId());
        }
        return itinerary;
    }

    // An impact belongs to whoever owns its itinerary; orphan impacts are inaccessible.
    // anyone else gets "not found" (404), the same as for itineraries
    public Impact requireAccess(Impact impact, CurrentUser me) {
        requireLoggedIn(me);

        boolean allowed = canAccess(impact.getItinerary(), me);

        if (!allowed) {
            throw new ImpactNotFoundException(impact.getId());
        }
        return impact;
    }

    /** Keeps only the itineraries the caller may access (only their own, including admins). */
    public List<Itinerary> visibleTo(List<Itinerary> itineraries, CurrentUser me) {
        requireLoggedIn(me);
        return itineraries.stream().filter(i -> canAccess(i, me)).toList();
    }

    /** For "all itineraries of user X": only X themselves. Others get 404. */
    public void requireSelf(Long userId, CurrentUser me) {
        requireLoggedIn(me);
        if (!Objects.equals(me.getId(), userId)) {
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
