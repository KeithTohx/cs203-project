package csd.tripsense.admin;

import csd.tripsense.audit.AuditEntryResponse;
import csd.tripsense.audit.AuditService;
import csd.tripsense.user.Role;
import csd.tripsense.user.User;
import csd.tripsense.user.UserNotFoundException;
import csd.tripsense.user.UserRepository;
import csd.tripsense.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import csd.tripsense.user.AccountRuleException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminServiceImplTest {

    private UserRepository userRepository;
    private AuditService auditService;
    private UserService userService;
    private AdminServiceImpl service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        auditService = mock(AuditService.class);
        userService = mock(UserService.class);
        service = new AdminServiceImpl(userRepository, auditService, userService);
    }

    // ---------- delete ----------

    @Test
    void deleteUser_usesTheSharedCleanup_andReturnsTheDeletedAccount() {
        User bob = new User("bob@example.com", "bob", "hash", "USER");
        when(userRepository.findById(4L)).thenReturn(Optional.of(bob));

        User result = service.deleteUser(4L, 3L);

        verify(userService).deleteUserAndData(4L);   // the same method a user deleting themselves goes through
        assertThat(result).isSameAs(bob);
    }

    @Test
    void deleteUser_adminDeletingThemselves_isRefusedAndNothingIsTouched() {
        assertThatThrownBy(() -> service.deleteUser(3L, 3L))
                .isInstanceOfSatisfying(AccountRuleException.class,
                        e -> assertThat(e.getKind()).isEqualTo(AccountRuleException.Kind.INVALID_INPUT));

        verify(userService, never()).deleteUserAndData(any());
    }

    @Test
    void deleteUser_unknownId_throwsNotFoundAndDeletesNothing() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteUser(99L, 3L)).isInstanceOf(UserNotFoundException.class);

        verify(userService, never()).deleteUserAndData(any());
    }

    @Test
    void deleteUser_adminAccountsAreProtected() {
        User lastAdmin = new User("other@example.com", "other", "hash", "ADMIN");
        when(userRepository.findById(5L)).thenReturn(Optional.of(lastAdmin));
        assertThatThrownBy(() -> service.deleteUser(5L, 3L))
                .isInstanceOfSatisfying(AccountRuleException.class,
                        e -> assertThat(e.getKind()).isEqualTo(AccountRuleException.Kind.CONFLICT));

        verify(userService, never()).deleteUserAndData(any());
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
    void disableUser_stopsTheAccount_butDeletesNothing() {
        User bob = new User("bob@example.com", "bob", "hash", "USER");
        when(userRepository.findById(4L)).thenReturn(Optional.of(bob));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = service.disableUser(4L, 3L);

        assertThat(result.isEnabled()).isFalse();
        verify(userService, never()).deleteUserAndData(any());   // trips and history stay
        verify(userRepository, never()).delete(any(User.class));
    }

    @Test
    void disableUser_adminDisablingThemselves_isRefused() {
        assertThatThrownBy(() -> service.disableUser(3L, 3L))
                .isInstanceOfSatisfying(AccountRuleException.class,
                        e -> assertThat(e.getKind()).isEqualTo(AccountRuleException.Kind.INVALID_INPUT));

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void disableUser_unknownId_throwsNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.disableUser(99L, 3L)).isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void disableUser_adminAccountsAreProtected() {
        User otherAdmin = new User("other@example.com", "other", "hash", "ADMIN");
        when(userRepository.findById(5L)).thenReturn(Optional.of(otherAdmin));

        // an admin has to be demoted through role management first
        assertThatThrownBy(() -> service.disableUser(5L, 3L))
                .isInstanceOfSatisfying(AccountRuleException.class,
                        e -> assertThat(e.getKind()).isEqualTo(AccountRuleException.Kind.CONFLICT));

        assertThat(otherAdmin.isEnabled()).isTrue();
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void enableUser_letsTheAccountLogInAgain() {
        User bob = new User("bob@example.com", "bob", "hash", "USER");
        bob.setEnabled(false);
        when(userRepository.findById(4L)).thenReturn(Optional.of(bob));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.enableUser(4L).isEnabled()).isTrue();
    }

    // ---------- change role ----------

    @Test
    void changeRole_toAdmin_savesThePromotion() {
        User bob = new User("bob@example.com", "bob", "hash", "USER");
        when(userRepository.findById(4L)).thenReturn(Optional.of(bob));
        when(userRepository.saveAndFlush(bob)).thenReturn(bob);

        User result = service.changeRole(4L, Role.ADMIN, 3L);

        assertThat(result.getRole()).isEqualTo("ADMIN");
        verify(userRepository).saveAndFlush(bob);
    }

    @Test
    void changeRole_toUser_savesTheDemotion_whenAnotherAdminIsLeft() {
        User otherAdmin = new User("other@example.com", "other", "hash", "ADMIN");
        when(userRepository.findById(5L)).thenReturn(Optional.of(otherAdmin));
        when(userRepository.countEnabledByRole("ADMIN")).thenReturn(2L);
        when(userRepository.saveAndFlush(otherAdmin)).thenReturn(otherAdmin);

        User result = service.changeRole(5L, Role.USER, 3L);

        assertThat(result.getRole()).isEqualTo("USER");
    }

    @Test
    void changeRole_onOwnAccount_isRefusedAndNothingIsTouched() {
        assertThatThrownBy(() -> service.changeRole(3L, Role.USER, 3L))
                .isInstanceOfSatisfying(AccountRuleException.class,
                        e -> assertThat(e.getKind()).isEqualTo(AccountRuleException.Kind.INVALID_INPUT));

        verifyNoInteractions(userRepository);
    }

    @Test
    void changeRole_lastEnabledAdmin_cannotBeDemoted() {
        User lastAdmin = new User("admin@example.com", "admin", "hash", "ADMIN");
        when(userRepository.findById(5L)).thenReturn(Optional.of(lastAdmin));
        when(userRepository.countEnabledByRole("ADMIN")).thenReturn(1L);

        assertThatThrownBy(() -> service.changeRole(5L, Role.USER, 3L))
                .isInstanceOfSatisfying(AccountRuleException.class,
                        e -> assertThat(e.getKind()).isEqualTo(AccountRuleException.Kind.CONFLICT));

        assertThat(lastAdmin.getRole()).isEqualTo("ADMIN");
        verify(userRepository, never()).saveAndFlush(any());
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
