package csd.tripsense.admin;

import csd.tripsense.audit.AuditService;
import csd.tripsense.auth.CurrentUser;
import csd.tripsense.auth.SessionService;
import csd.tripsense.user.*;
import org.junit.jupiter.api.Test;
import csd.tripsense.user.AccountRuleException;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoleManagementTest {
    private final UserRepository users = mock(UserRepository.class);
    private final UserService userService = mock(UserService.class);
    private final AdminServiceImpl service = new AdminServiceImpl(users, mock(AuditService.class), userService);

    @Test
    void promotionAndDemotionPersistValidatedRoles() {
        User user = new User("user@example.com", "person", "hash", "USER");
        when(users.findById(4L)).thenReturn(Optional.of(user));
        when(users.saveAndFlush(user)).thenReturn(user);
        service.changeRole(4L, Role.ADMIN, 3L);
        assertThat(user.getRole()).isEqualTo("ADMIN");
        when(users.countEnabledByRole("ADMIN")).thenReturn(2L);
        service.changeRole(4L, Role.USER, 3L);
        assertThat(user.getRole()).isEqualTo("USER");
    }

    @Test
    void selfRoleChangesAreRejected() {
        assertThatThrownBy(() -> service.changeRole(3L, Role.USER, 3L))
                .isInstanceOfSatisfying(AccountRuleException.class,
                        e -> assertThat(e.getKind()).isEqualTo(AccountRuleException.Kind.INVALID_INPUT));
        verifyNoInteractions(users);
    }

    @Test
    void lastEnabledAdminCannotBeDemoted() {
        User admin = new User("admin@example.com", "admin", "hash", "ADMIN");
        when(users.findById(3L)).thenReturn(Optional.of(admin));
        when(users.countEnabledByRole("ADMIN")).thenReturn(1L);
        assertThatThrownBy(() -> service.changeRole(3L, Role.USER, 4L))
                .isInstanceOfSatisfying(AccountRuleException.class,
                        e -> assertThat(e.getKind()).isEqualTo(AccountRuleException.Kind.CONFLICT));
        assertThat(admin.getRole()).isEqualTo("ADMIN");
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void adminCannotDisableOrDeleteAPeer() {
        User admin = new User("other@example.com", "other", "hash", "ADMIN");
        when(users.findById(4L)).thenReturn(Optional.of(admin));
        assertThatThrownBy(() -> service.disableUser(4L, 3L)).isInstanceOf(AccountRuleException.class);
        assertThatThrownBy(() -> service.deleteUser(4L, 3L)).isInstanceOf(AccountRuleException.class);
        verifyNoInteractions(userService);
        verify(users, never()).save(any());
    }

    @Test
    void controllerExpiresSessionsAndAuditsOnlyAfterSuccessfulRoleChange() {
        AdminService adminService = mock(AdminService.class);
        AuditService audit = mock(AuditService.class);
        SessionService sessions = mock(SessionService.class);
        User previous = new User("user@example.com", "person", "hash", "USER");
        User promoted = new User("user@example.com", "person", "hash", "ADMIN");
        when(adminService.getUser(4L)).thenReturn(previous);
        when(adminService.changeRole(4L, Role.ADMIN, 3L)).thenReturn(promoted);
        AdminController controller = new AdminController(adminService, audit, sessions);
        CurrentUser actor = new CurrentUser(3L, "manager@example.com", "manager", null, "ADMIN");
        controller.changeRole(4L, new ChangeRoleRequest(Role.ADMIN), actor);
        var order = inOrder(adminService, sessions, audit);
        order.verify(adminService).changeRole(4L, Role.ADMIN, 3L);
        order.verify(sessions).expireSessionsFor(4L);
        order.verify(audit).record(AuditService.ROLE_CHANGED, 3L, "manager@example.com", 4L,
                "role changed from USER to ADMIN");
        // a failed role change touching no sessions or audit is tested in
        // AdminControllerTest.roleEndpointMapsBusinessConflictWithoutExpiringSessions
    }
}
