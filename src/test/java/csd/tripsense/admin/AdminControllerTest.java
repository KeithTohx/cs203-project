package csd.tripsense.admin;

import csd.tripsense.audit.AuditEntryResponse;
import csd.tripsense.audit.AuditService;
import csd.tripsense.auth.CurrentUser;
import csd.tripsense.auth.SessionService;
import csd.tripsense.user.User;
import csd.tripsense.user.Role;
import csd.tripsense.user.AccountRuleException;
import csd.tripsense.user.AccountExceptionHandler;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Note: standalone MockMvc has no security filters, so the 403 for a normal user is not tested here.
 * Check it against the running app (see the curl steps).
 */
class AdminControllerTest {

    private AdminService adminService;
    private AuditService auditService;
    private SessionService sessionService;
    private MockMvc mockMvc;

    private final CurrentUser admin = new CurrentUser(3L, "admin@example.com", "admin", null, "ADMIN", true);

    @BeforeEach
    void setUp() {
        adminService = mock(AdminService.class);
        auditService = mock(AuditService.class);
        sessionService = mock(SessionService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminController(adminService, auditService, sessionService))
                .setControllerAdvice(new AccountExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(admin, null, admin.getAuthorities()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listUsers_returnsPagedAccounts_withoutPasswords() throws Exception {
        User bob = new User("bob@example.com", "bob", "secret-hash", "USER");
        when(adminService.listUsers(any(), anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of(bob)));

        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].username").value("bob"))
                .andExpect(jsonPath("$.content[0].role").value("USER"))
                .andExpect(jsonPath("$.content[0].enabled").value(true))
                .andExpect(jsonPath("$.content[0].password").doesNotExist())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getUser_returnsOneAccount() throws Exception {
        when(adminService.getUser(4L)).thenReturn(new User("bob@example.com", "bob", "hash", "USER"));

        mockMvc.perform(get("/api/admin/users/4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("bob@example.com"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void deleteUser_returns204_endsTheirSessions_andRecordsAnAuditEntry() throws Exception {
        User deleted = mock(User.class);
        when(deleted.getId()).thenReturn(4L);
        when(deleted.getUsername()).thenReturn("bob");
        when(adminService.deleteUser(4L, 3L)).thenReturn(deleted);

        mockMvc.perform(delete("/api/admin/users/4"))
                .andExpect(status().isNoContent());

        verify(sessionService).expireSessionsFor(4L);
        verify(auditService).record(eq(AuditService.ACCOUNT_DELETED), eq(3L), eq("admin@example.com"),
                eq(4L), contains("bob"));
    }

    @Test
    void deleteUser_ownAccount_returns400_andNothingElseHappens() throws Exception {
        when(adminService.deleteUser(3L, 3L))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "admins cannot delete their own account"));

        mockMvc.perform(delete("/api/admin/users/3"))
                .andExpect(status().isBadRequest());

        verify(sessionService, never()).expireSessionsFor(anyLong());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void reviewActions_returnsPagedAuditEntries() throws Exception {
        AuditEntryResponse entry = new AuditEntryResponse(1L, Instant.now(), 3L, "admin@example.com",
                "LOGIN", 3L, null);
        when(adminService.reviewActions(any(), any(), anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of(entry)));

        mockMvc.perform(get("/api/admin/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("LOGIN"))
                .andExpect(jsonPath("$.content[0].actorEmail").value("admin@example.com"));
    }

    /** An account as the service returns it: id 4, "bob". Built before it is used in any when(...). */
    private static User bob(boolean enabled) {
        User bob = mock(User.class);
        when(bob.getId()).thenReturn(4L);
        when(bob.getUsername()).thenReturn("bob");
        when(bob.getEmail()).thenReturn("bob@example.com");
        when(bob.getRole()).thenReturn("USER");
        when(bob.isEnabled()).thenReturn(enabled);
        return bob;
    }

    @Test
    void disableUser_returns200_endsTheirSessions_andIsAudited() throws Exception {
        User disabled = bob(false);
        when(adminService.disableUser(4L, 3L)).thenReturn(disabled);

        mockMvc.perform(post("/api/admin/users/4/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.password").doesNotExist());

        verify(sessionService).expireSessionsFor(4L);
        verify(auditService).record(eq(AuditService.ACCOUNT_DISABLED), eq(3L), eq("admin@example.com"),
                eq(4L), contains("bob"));
    }

    @Test
    void disableUser_ownAccount_returns400_andNothingElseHappens() throws Exception {
        when(adminService.disableUser(3L, 3L))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "admins cannot disable their own account"));

        mockMvc.perform(post("/api/admin/users/3/disable"))
                .andExpect(status().isBadRequest());

        verify(sessionService, never()).expireSessionsFor(anyLong());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void enableUser_returns200_andIsAudited_withoutTouchingSessions() throws Exception {
        User enabled = bob(true);
        when(adminService.enableUser(4L)).thenReturn(enabled);

        mockMvc.perform(post("/api/admin/users/4/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        verify(auditService).record(eq(AuditService.ACCOUNT_ENABLED), eq(3L), eq("admin@example.com"),
                eq(4L), contains("bob"));
        verify(sessionService, never()).expireSessionsFor(anyLong());
    }

    @Test
    void reviewUserActions_returnsThatUsersTrail() throws Exception {
        AuditEntryResponse entry = new AuditEntryResponse(7L, Instant.now(), 4L, "bob@example.com",
                "ITINERARY_CREATED", 4L, "itinerary 9 'Seoul Food Tour'");
        when(adminService.reviewUserActions(eq(4L), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(entry)));

        mockMvc.perform(get("/api/admin/users/4/actions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("ITINERARY_CREATED"))
                .andExpect(jsonPath("$.content[0].details").value("itinerary 9 'Seoul Food Tour'"));
    }

    @Test
    void reviewUserActions_passesTheActionFilterThrough() throws Exception {
        when(adminService.reviewUserActions(eq(4L), eq("LOGIN"), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/admin/users/4/actions").param("action", "LOGIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        verify(adminService).reviewUserActions(eq(4L), eq("LOGIN"), anyInt(), anyInt());
    }

    @Test
    void roleEndpointRejectsInvalidOrMissingRoles() throws Exception {
        for (String body : List.of("{}", "{\"role\":\"SUPERUSER\"}")) {
            mockMvc.perform(post("/api/admin/users/4/role").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(adminService, never()).changeRole(any(), any(), any());
    }

    @Test
    void roleEndpointMapsBusinessConflictWithoutExpiringSessions() throws Exception {
        when(adminService.getUser(4L)).thenReturn(new User("person@example.com", "person", "hash", "ADMIN"));
        when(adminService.changeRole(4L, Role.USER, 3L)).thenThrow(
                new AccountRuleException(AccountRuleException.Kind.CONFLICT, "last enabled admin"));
        mockMvc.perform(post("/api/admin/users/4/role").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("last enabled admin"));
        verify(sessionService, never()).expireSessionsFor(anyLong());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

}
