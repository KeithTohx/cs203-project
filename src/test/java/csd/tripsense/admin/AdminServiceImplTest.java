package csd.tripsense.admin;

import csd.tripsense.audit.AuditEntryResponse;
import csd.tripsense.audit.AuditService;
import csd.tripsense.user.User;
import csd.tripsense.user.UserNotFoundException;
import csd.tripsense.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminServiceImplTest {

    private UserRepository userRepository;
    private AuditService auditService;
    private EntityManager entityManager;
    private Query query;
    private AdminServiceImpl service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        auditService = mock(AuditService.class);
        entityManager = mock(EntityManager.class);
        query = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        service = new AdminServiceImpl(userRepository, auditService, entityManager);
    }

    // ---------- delete ----------

    @Test
    void deleteUser_removesTheirDataFirst_thenTheAccount() {
        User bob = new User("bob@example.com", "bob", "hash", "USER");
        when(userRepository.findById(4L)).thenReturn(Optional.of(bob));

        User result = service.deleteUser(4L, 3L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager, times(3)).createNativeQuery(sql.capture());
        List<String> statements = sql.getAllValues();
        // children before parents, so it keeps working when foreign keys are enforced
        assertThat(statements.get(0)).startsWith("delete from impact");
        assertThat(statements.get(1)).startsWith("delete from activity");
        assertThat(statements.get(2)).startsWith("delete from itinerary");
        verify(userRepository).delete(bob);
        assertThat(result).isSameAs(bob);
    }

    @Test
    void deleteUser_adminDeletingThemselves_isRefusedAndNothingIsTouched() {
        assertThatThrownBy(() -> service.deleteUser(3L, 3L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(entityManager, never()).createNativeQuery(anyString());
        verify(userRepository, never()).delete(any(User.class));
    }

    @Test
    void deleteUser_unknownId_throwsNotFoundAndDeletesNothing() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteUser(99L, 3L)).isInstanceOf(UserNotFoundException.class);

        verify(entityManager, never()).createNativeQuery(anyString());
        verify(userRepository, never()).delete(any(User.class));
    }

    // ---------- view ----------

    @Test
    void listUsers_withoutSearch_listsEveryone() {
        User bob = new User("bob@example.com", "bob", "hash", "USER");
        when(userRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(bob)));

        assertThat(service.listUsers(null, 0, 20).getContent()).containsExactly(bob);
    }

    @Test
    void listUsers_withSearch_searchesEmailAndUsername() {
        User bob = new User("bob@example.com", "bob", "hash", "USER");
        when(userRepository.findByEmailContainingIgnoreCaseOrUsernameContainingIgnoreCase(
                eq("bob"), eq("bob"), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(bob)));

        assertThat(service.listUsers("  bob ", 0, 20).getContent()).containsExactly(bob);
    }

    @Test
    void listUsers_capsThePageSizeAt100() {
        when(userRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.listUsers(null, -5, 5000);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository).findAll(captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
        assertThat(captor.getValue().getPageNumber()).isZero();
    }

    @Test
    void getUser_unknownId_throwsNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getUser(99L)).isInstanceOf(UserNotFoundException.class);
    }

    // ---------- disable / enable (E1-06) ----------

    @Test
    void disableUser_stopsTheAccount_butTouchesNoData() {
        User bob = new User("bob@example.com", "bob", "hash", "USER");
        when(userRepository.findById(4L)).thenReturn(Optional.of(bob));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = service.disableUser(4L, 3L);

        assertThat(result.isEnabled()).isFalse();
        verify(entityManager, never()).createNativeQuery(anyString());   // trips and history stay
        verify(userRepository, never()).delete(any(User.class));
    }

    @Test
    void disableUser_adminDisablingThemselves_isRefused() {
        assertThatThrownBy(() -> service.disableUser(3L, 3L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void disableUser_unknownId_throwsNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.disableUser(99L, 3L)).isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void enableUser_letsTheAccountLogInAgain() {
        User bob = new User("bob@example.com", "bob", "hash", "USER");
        bob.setEnabled(false);
        when(userRepository.findById(4L)).thenReturn(Optional.of(bob));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.enableUser(4L).isEnabled()).isTrue();
    }

    // ---------- review what a user did ----------

    @Test
    void reviewUserActions_asksTheAuditTrailForThatAccount_withASafePageSize() {
        Page<AuditEntryResponse> empty = Page.empty();
        when(auditService.searchForUser(eq(4L), eq("LOGIN"), eq(0), eq(100))).thenReturn(empty);

        Page<AuditEntryResponse> result = service.reviewUserActions(4L, "LOGIN", -1, 5000);

        assertThat(result).isSameAs(empty);
    }
}
