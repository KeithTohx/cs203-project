package csd.tripsense.itinerary;

import csd.tripsense.auth.CurrentUser;
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

class ItineraryAccessTest {

    private UserRepository userRepository;
    private ItineraryAccess access;

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

    @Test
    void owner_canAccessOwnItinerary() {
        Itinerary mine = ownedBy(1L, 10L);

        assertThat(access.requireAccess(mine, john)).isSameAs(mine);
    }

    @Test
    void otherUser_getsNotFound() {
        Itinerary johns = ownedBy(1L, 10L);

        assertThatThrownBy(() -> access.requireAccess(johns, bob))
                .isInstanceOf(ItineraryNotFoundException.class);
    }

    @Test
    void admin_cannotAccessAnotherUsersItinerary() {
        Itinerary johns = ownedBy(1L, 10L);

        assertThatThrownBy(() -> access.requireAccess(johns, admin))
                .isInstanceOf(ItineraryNotFoundException.class);
    }

    @Test
    void visibleTo_keepsOnlyOwnItineraries_forEveryRole() {
        List<Itinerary> all = List.of(ownedBy(1L, 10L), ownedBy(4L, 11L), ownedBy(1L, 12L));

        assertThat(access.visibleTo(all, john)).extracting(Itinerary::getId).containsExactly(10L, 12L);
        assertThat(access.visibleTo(all, bob)).extracting(Itinerary::getId).containsExactly(11L);
        assertThat(access.visibleTo(all, admin)).isEmpty();
    }

    @Test
    void requireSelf_allowsOnlySelf() {
        access.requireSelf(1L, john);
        assertThatThrownBy(() -> access.requireSelf(1L, admin))
                .isInstanceOf(ItineraryNotFoundException.class);

        assertThatThrownBy(() -> access.requireSelf(1L, bob))
                .isInstanceOf(ItineraryNotFoundException.class);
    }

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

    @Test
    void notLoggedIn_isRejectedWith401() {
        assertThatThrownBy(() -> access.requireAccess(ownedBy(1L, 10L), null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
