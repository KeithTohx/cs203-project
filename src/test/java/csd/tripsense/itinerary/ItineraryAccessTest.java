package csd.tripsense.itinerary;

import csd.tripsense.auth.CurrentUser;
import csd.tripsense.impact.Impact;
import csd.tripsense.impact.ImpactNotFoundException;
import csd.tripsense.user.User;
import csd.tripsense.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the ownership rule itself: only the owner of an itinerary (or of an impact on it) gets
 * access. Everyone else, admins included, gets "not found" so they cannot tell which ids exist.
 *
 * Plain unit tests with no Spring context. Only UserRepository and User are mocked.
 */
class ItineraryAccessTest {

    private UserRepository userRepository;
    private ItineraryAccess access;

    /**
     * John owns the itineraries in these tests, 
     * Bob is another normal user, 
     * Admin has the ADMIN role to prove the role gives no extra access
     */
    private final CurrentUser john = new CurrentUser(1L, "john@example.com", "john", null, "USER", true);
    private final CurrentUser bob = new CurrentUser(4L, "bob@example.com", "bob", null, "USER", true);
    private final CurrentUser admin = new CurrentUser(3L, "admin@example.com", "admin", null, "ADMIN", true);

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        access = new ItineraryAccess(userRepository);
    }

    /** An itinerary owned by the user with this id. */
    private static Itinerary ownedBy(long userId, long itineraryId) {
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(userId);
        Itinerary itinerary = new Itinerary();
        itinerary.setId(itineraryId);
        itinerary.setUser(owner);
        return itinerary;
    }

    /** An impact on this itinerary (pass null for an orphan impact that belongs to no itinerary). */
    private static Impact impactOn(Itinerary itinerary, long impactId) {
        Impact impact = mock(Impact.class);
        when(impact.getId()).thenReturn(impactId);
        when(impact.getItinerary()).thenReturn(itinerary);
        return impact;
    }

    /** Asserts the failure is a 401, which is what every method gives a missing caller. */
    private static void assertUnauthorized(Throwable e) {
        assertThat(e).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    // ---------- ITINERARY ACCESS ----------

    /** happy path: the owner gets the very same object back */
    @Test
    void owner_canAccessOwnItinerary() {
        Itinerary mine = ownedBy(1L, 10L);

        assertThat(access.requireAccess(mine, john)).isSameAs(mine);
    }

    /** Not found rather than forbidden, so a stranger cannot tell the itinerary exists */
    @Test
    void otherUser_getsNotFound() {
        Itinerary johns = ownedBy(1L, 10L);

        assertThatThrownBy(() -> access.requireAccess(johns, bob))
                .isInstanceOf(ItineraryNotFoundException.class);
    }

    /** The ADMIN role must not work as a master key to other people's trips */
    @Test
    void admin_cannotAccessAnotherUsersItinerary() {
        Itinerary johns = ownedBy(1L, 10L);

        assertThatThrownBy(() -> access.requireAccess(johns, admin))
                .isInstanceOf(ItineraryNotFoundException.class);
    }

    /**
     * Each caller sees only their own itineraries out of the same list, 
     * and the admin sees none because none of them are the admin's
     */
    @Test
    void visibleTo_keepsOnlyOwnItineraries_forEveryRole() {
        List<Itinerary> all = List.of(ownedBy(1L, 10L), ownedBy(4L, 11L), ownedBy(1L, 12L));

        assertThat(access.visibleTo(all, john)).extracting(Itinerary::getId).containsExactly(10L, 12L);
        assertThat(access.visibleTo(all, bob)).extracting(Itinerary::getId).containsExactly(11L);
        assertThat(access.visibleTo(all, admin)).isEmpty();
    }

    /**
     * "all itineraries of user X" is only for X: john asking about himself passes (no exception),
     * anyone else, including the admin, gets not found
     */
    @Test
    void requireSelf_allowsOnlySelf() {
        access.requireSelf(1L, john);
        assertThatThrownBy(() -> access.requireSelf(1L, admin))
                .isInstanceOf(ItineraryNotFoundException.class);

        assertThatThrownBy(() -> access.requireSelf(1L, bob))
                .isInstanceOf(ItineraryNotFoundException.class);
    }

    /**
     * A client can put any id and any user into the request body. prepareNew must ignore both:
     * the id is cleared so it cannot overwrite itinerary 99, and the owner is always the caller
     */
    @Test
    void prepareNew_ownsItToTheCallerAndClearsAnyId() {
        User johnEntity = new User("john@example.com", "john", "hash", "USER");
        when(userRepository.findById(1L)).thenReturn(Optional.of(johnEntity));

        Itinerary fromRequest = new Itinerary();
        fromRequest.setId(99L);                                   // a client trying to overwrite itinerary 99
        fromRequest.setUser(new User("bob@example.com", "bob", "hash", "USER"));   // ...and claim it for bob

        Itinerary prepared = access.prepareNew(fromRequest, john);

        assertThat(prepared.getId()).isNull();
        assertThat(prepared.getUser()).isSameAs(johnEntity);
    }

    /** No caller at all is 401 (not logged in), which is different from 404 (logged in but not yours) */
    @Test
    void notLoggedIn_isRejectedWith401() {
        assertThatThrownBy(() -> access.requireAccess(ownedBy(1L, 10L), null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    // ---------- ITINERARY EDGE CASES ----------

    /**
     * The cast picks the Itinerary version of requireAccess. a bare null would not compile
     * because the method is also overloaded for Impact
     */
    @Test
    void nullItinerary_getsNotFound() {
        assertThatThrownBy(() -> access.requireAccess((Itinerary) null, john))
                .isInstanceOf(ItineraryNotFoundException.class);
    }

    /** An itinerary with no user belongs to nobody, so nobody gets in */
    @Test
    void itineraryWithNoOwner_getsNotFound() {
        Itinerary orphan = new Itinerary();
        orphan.setId(10L);

        assertThatThrownBy(() -> access.requireAccess(orphan, john))
                .isInstanceOf(ItineraryNotFoundException.class);
    }

    /** canAccess is the yes/no version, it is true only for the owner and never throws for a missing itinerary */
    @Test
    void canAccess_isTrueOnlyForTheOwner() {
        Itinerary johns = ownedBy(1L, 10L);

        assertThat(access.canAccess(johns, john)).isTrue();
        assertThat(access.canAccess(johns, bob)).isFalse();
        assertThat(access.canAccess(johns, admin)).isFalse();
        assertThat(access.canAccess(null, john)).isFalse();
    }

    // ---------- IMPACT ACCESS ----------

    /** An impact belongs to whoever owns its itinerary, so the itinerary's owner can use it */
    @Test
    void impact_ownerOfItsItinerary_canAccess() {
        Impact impact = impactOn(ownedBy(1L, 10L), 5L);

        assertThat(access.requireAccess(impact, john)).isSameAs(impact);
    }

    /** Anyone else gets ImpactNotFoundException (the impact version of 404) */
    @Test
    void impact_otherUser_getsImpactNotFound() {
        Impact impact = impactOn(ownedBy(1L, 10L), 5L);

        assertThatThrownBy(() -> access.requireAccess(impact, bob))
                .isInstanceOf(ImpactNotFoundException.class);
    }

    /** The admin role gives no access to impacts either */
    @Test
    void impact_admin_cannotAccessAnotherUsersImpact() {
        Impact impact = impactOn(ownedBy(1L, 10L), 5L);

        assertThatThrownBy(() -> access.requireAccess(impact, admin))
                .isInstanceOf(ImpactNotFoundException.class);
    }

    /** An impact with no itinerary has no owner to check against, so it is closed to everyone */
    @Test
    void impact_withNoItinerary_isInaccessibleToEveryone() {
        Impact orphan = impactOn(null, 5L);

        assertThatThrownBy(() -> access.requireAccess(orphan, john))
                .isInstanceOf(ImpactNotFoundException.class);
        assertThatThrownBy(() -> access.requireAccess(orphan, admin))
                .isInstanceOf(ImpactNotFoundException.class);
    }

    /** A missing caller is a 401 for impacts as well */
    @Test
    void impact_notLoggedIn_isRejectedWith401() {
        Impact impact = impactOn(ownedBy(1L, 10L), 5L);

        assertThatThrownBy(() -> access.requireAccess(impact, null))
                .satisfies(ItineraryAccessTest::assertUnauthorized);
    }

    // ---------- Every method rejects a missing caller ----------

    /** 
     * visibleTo, requireSelf and prepareNew each check for a caller themselves, so each gets its own test. 
     * Otherwise one of them could lose the check without any test failing
     */

    @Test
    void visibleTo_notLoggedIn_isRejectedWith401() {
        assertThatThrownBy(() -> access.visibleTo(List.of(ownedBy(1L, 10L)), null))
                .satisfies(ItineraryAccessTest::assertUnauthorized);
    }

    @Test
    void requireSelf_notLoggedIn_isRejectedWith401() {
        assertThatThrownBy(() -> access.requireSelf(1L, null))
                .satisfies(ItineraryAccessTest::assertUnauthorized);
    }

    @Test
    void prepareNew_notLoggedIn_isRejectedWith401() {
        assertThatThrownBy(() -> access.prepareNew(new Itinerary(), null))
                .satisfies(ItineraryAccessTest::assertUnauthorized);
    }

    /**
     * A session can outlive the account (for example it was deleted), 
     * in which case the user lookup is empty and prepareNew must refuse instead of saving an itinerary with no owner
     */
    @Test
    void prepareNew_accountNoLongerExists_isRejectedWith401() {
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> access.prepareNew(new Itinerary(), john))
                .satisfies(ItineraryAccessTest::assertUnauthorized);
    }

    
}
