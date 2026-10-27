package csd.tripsense.user;

import csd.tripsense.audit.AuditService;
import csd.tripsense.user.preference.PreferenceService;
import csd.tripsense.user.preference.UserPreference;
import csd.tripsense.user.preference.UpdatePreferenceRequest;
import csd.tripsense.user.preference.RiskTolerance;
import csd.tripsense.auth.CurrentUser;
import csd.tripsense.auth.SessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserControllerTest {

    private UserService userService;
    private PreferenceService preferenceService;
    private SessionService sessionService;
    private AuditService auditService;
    private MockMvc mockMvc;

    private final CurrentUser alice = new CurrentUser(7L, "alice@example.com", "alice", null, "USER", true);

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        preferenceService = mock(PreferenceService.class);
        sessionService = mock(SessionService.class);
        auditService = mock(AuditService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new UserController(userService, preferenceService, sessionService, auditService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(CurrentUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
    }

    private static String registerJson(String email, String username, String password) {
        return "{\"email\":\"" + email + "\",\"username\":\"" + username
                + "\",\"password\":\"" + password + "\"}";
    }

    /** A fake logged-in caller for the endpoints that read the plain Authentication. */
    private static Authentication caller(String email, String role) {
        return new UsernamePasswordAuthenticationToken(email, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    // ---------- register ----------

    @Test
    void register_withValidPayload_returns201_andIsAudited() throws Exception {
        when(userService.register(any(User.class)))
                .thenReturn(new User("john@example.com", "john", "hash", "USER"));

        mockMvc.perform(post("/api/user/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("john@example.com", "john", "password123")))
                .andExpect(status().isCreated());

        verify(auditService).record(eq(AuditService.REGISTER), any(), eq("john@example.com"), any(), any());
    }

    @Test
    void register_withTakenEmail_returns409_andIsNotAudited() throws Exception {
        when(userService.register(any(User.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "email is already taken"));

        mockMvc.perform(post("/api/user/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("john@example.com", "john", "password123")))
                .andExpect(status().isConflict());

        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void register_withBlankEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/user/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("", "john", "password123")))
                .andExpect(status().isBadRequest());
    }

    // ---------- get by username ----------

    @Test
    void getByUsername_asOwner_returns200() throws Exception {
        when(userService.getByUsername("john"))
                .thenReturn(new User("john@example.com", "john", "hash", "USER"));

        mockMvc.perform(get("/api/user/john").principal(caller("john@example.com", "USER")))
                .andExpect(status().isOk());
    }

    @Test
    void getByUsername_asDifferentUser_returns404() throws Exception {
        when(userService.getByUsername("john"))
                .thenReturn(new User("john@example.com", "john", "hash", "USER"));

        mockMvc.perform(get("/api/user/john").principal(caller("mallory@example.com", "USER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void getByUsername_asAdmin_returns200() throws Exception {
        when(userService.getByUsername("john"))
                .thenReturn(new User("john@example.com", "john", "hash", "USER"));

        mockMvc.perform(get("/api/user/john").principal(caller("admin@example.com", "ADMIN")))
                .andExpect(status().isOk());
    }

    // ---------- PATCH /api/user/me (change username) ----------

    @Test
    void updateProfile_changesTheLoggedInAccount_refreshesTheSession_andIsAudited() throws Exception {
        loginAs(alice);
        User renamed = new User("alice@example.com", "alice2", "hash", "USER");
        when(userService.updateUsername(7L, "alice2")).thenReturn(renamed);

        mockMvc.perform(patch("/api/user/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice2"))
                .andExpect(jsonPath("$.password").doesNotExist());

        verify(sessionService).refreshCurrentSession(eq(renamed), any(), any());
        verify(auditService).record(eq(AuditService.PROFILE_UPDATED), eq(7L), eq("alice@example.com"),
                eq(7L), eq("changed username"));
    }

    @Test
    void updateProfile_alwaysTargetsTheLoggedInAccount_whateverIdTheBodyContains() throws Exception {
        loginAs(alice);
        when(userService.updateUsername(eq(7L), any())).thenReturn(new User("alice@example.com", "alice2", "hash", "USER"));

        mockMvc.perform(patch("/api/user/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":1,\"username\":\"alice2\"}"))
                .andExpect(status().isOk());

        verify(userService).updateUsername(eq(7L), any());
        verify(userService, never()).updateUsername(eq(1L), any());
    }

    @Test
    void updateProfile_withBlankUsername_returns400AndChangesNothing() throws Exception {
        loginAs(alice);

        mockMvc.perform(patch("/api/user/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\"}"))
                .andExpect(status().isBadRequest());

        verify(userService, never()).updateUsername(anyLong(), any());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void updateProfile_whenNotLoggedIn_returns401() throws Exception {
        mockMvc.perform(patch("/api/user/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice2\"}"))
                .andExpect(status().isUnauthorized());

        verify(userService, never()).updateUsername(anyLong(), any());
    }

    // ---------- PUT /api/user/me/password ----------

    @Test
    void changePassword_returns204_endsAllSessions_andIsAuditedWithoutAnyPassword() throws Exception {
        loginAs(alice);

        mockMvc.perform(put("/api/user/me/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"old-password1\",\"newPassword\":\"new-password1\"}"))
                .andExpect(status().isNoContent());

        verify(userService).changePassword(7L, "old-password1", "new-password1");
        verify(sessionService).expireSessionsFor("alice@example.com");
        verify(auditService).record(eq(AuditService.PASSWORD_CHANGED), eq(7L), eq("alice@example.com"),
                eq(7L), isNull());
    }

    @Test
    void changePassword_withWrongCurrentPassword_returns400_andNothingElseHappens() throws Exception {
        loginAs(alice);
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "current password is incorrect"))
                .when(userService).changePassword(any(), any(), any());

        mockMvc.perform(put("/api/user/me/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong-password\",\"newPassword\":\"new-password1\"}"))
                .andExpect(status().isBadRequest());

        verify(sessionService, never()).expireSessionsFor(anyString());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void changePassword_withTooShortNewPassword_returns400BeforeAnythingIsChecked() throws Exception {
        loginAs(alice);

        mockMvc.perform(put("/api/user/me/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"old-password1\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest());

        verify(userService, never()).changePassword(any(), any(), any());
    }

    @Test
    void getPreferences_returnsTheLoggedInUsersPreferences() throws Exception {
        loginAs(alice);
        when(preferenceService.getPreferences(7L))
                .thenReturn(new UserPreference(new User("alice@example.com", "alice", "hash", "USER")));

        mockMvc.perform(get("/api/user/me/preferences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskTolerance").value("MEDIUM"));

        verify(preferenceService).getPreferences(7L);
    }

    @Test
    void updatePreferences_targetsTheLoggedInUser() throws Exception {
        loginAs(alice);
        UserPreference preferences = new UserPreference(new User("alice@example.com", "alice", "hash", "USER"));
        preferences.setRiskTolerance(RiskTolerance.LOW);
        when(preferenceService.updatePreferences(eq(7L), any())).thenReturn(preferences);

        mockMvc.perform(patch("/api/user/me/preferences").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riskTolerance\":\"LOW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskTolerance").value("LOW"));

        verify(preferenceService).updatePreferences(7L,
                new UpdatePreferenceRequest(RiskTolerance.LOW, null, null, null, null, null));
    }

    @Test
    void getPreferences_whenNotLoggedIn_returns401() throws Exception {
        mockMvc.perform(get("/api/user/me/preferences"))
                .andExpect(status().isUnauthorized());

        verify(preferenceService, never()).getPreferences(any());
    }

    @Test
    void updatePreferences_whenNotLoggedIn_returns401() throws Exception {
        mockMvc.perform(patch("/api/user/me/preferences").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        verify(preferenceService, never()).updatePreferences(any(), any());
    }

}
