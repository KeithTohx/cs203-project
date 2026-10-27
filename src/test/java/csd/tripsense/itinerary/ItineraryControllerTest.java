package csd.tripsense.itinerary;

import csd.tripsense.audit.AuditService;
import csd.tripsense.auth.CurrentUser;
import csd.tripsense.user.User;
import csd.tripsense.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ItineraryControllerTest {

    private ItineraryService itineraryService;
    private AuditService auditService;
    private MockMvc mockMvc;

    private final CurrentUser john = new CurrentUser(1L, "john@example.com", "john", null, "USER", true);
    private final CurrentUser bob = new CurrentUser(4L, "bob@example.com", "bob", null, "USER", true);

    @BeforeEach
    void setUp() {
        itineraryService = mock(ItineraryService.class);
        auditService = mock(AuditService.class);
        ItineraryAccess access = new ItineraryAccess(mock(UserRepository.class));
        mockMvc = MockMvcBuilders.standaloneSetup(new ItineraryController(itineraryService, access, auditService))
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

    // Build these BEFORE calling when(...): creating a mock inside a thenReturn(...) argument breaks Mockito.
    private static Itinerary ownedBy(long userId, long itineraryId) {
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(userId);
        Itinerary itinerary = new Itinerary();
        itinerary.setId(itineraryId);
        itinerary.setName("Trip " + itineraryId);
        itinerary.setUser(owner);
        return itinerary;
    }

    @Test
    void getById_asOwner_returns200() throws Exception {
        Itinerary johns = ownedBy(1L, 10L);
        when(itineraryService.getItineraryById(10L)).thenReturn(johns);
        loginAs(john);

        mockMvc.perform(get("/api/itinerary/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10));
    }

    @Test
    void getById_asSomeoneElse_returns404() throws Exception {
        Itinerary johns = ownedBy(1L, 10L);
        when(itineraryService.getItineraryById(10L)).thenReturn(johns);
        loginAs(bob);

        mockMvc.perform(get("/api/itinerary/10"))
                .andExpect(status().isNotFound());
    }

    @Test
    void list_returnsOnlyTheCallersItineraries() throws Exception {
        List<Itinerary> all = List.of(ownedBy(1L, 10L), ownedBy(4L, 11L), ownedBy(1L, 12L));
        when(itineraryService.getAllItineraries()).thenReturn(all);
        loginAs(john);

        mockMvc.perform(get("/api/itinerary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(10))
                .andExpect(jsonPath("$[1].id").value(12));
    }

    @Test
    void itinerariesOfAnotherUser_return404() throws Exception {
        loginAs(bob);

        mockMvc.perform(get("/api/user/1/itinerary"))
                .andExpect(status().isNotFound());
        verify(itineraryService, never()).getItinerariesByUser(anyLong());
    }

    @Test
    void delete_someoneElsesItinerary_returns404AndDeletesNothing() throws Exception {
        Itinerary johns = ownedBy(1L, 10L);
        when(itineraryService.getItineraryById(10L)).thenReturn(johns);
        loginAs(bob);

        mockMvc.perform(delete("/api/itinerary/10"))
                .andExpect(status().isNotFound());
        verify(itineraryService, never()).deleteItinerary(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any());   // nothing happened, nothing recorded
    }

    @Test
    void delete_ownItinerary_deletesIt() throws Exception {
        Itinerary johns = ownedBy(1L, 10L);
        when(itineraryService.getItineraryById(10L)).thenReturn(johns);
        loginAs(john);

        mockMvc.perform(delete("/api/itinerary/10"))
                .andExpect(status().isOk());
        verify(itineraryService).deleteItinerary(10L);
        verify(auditService).record(eq(AuditService.ITINERARY_DELETED), eq(1L), eq("john@example.com"),
                eq(1L), contains("Trip 10"));
    }

    @Test
    void delete_asAdmin_cannotDeleteAnotherUsersItinerary() throws Exception {
        Itinerary johns = ownedBy(1L, 10L);
        when(itineraryService.getItineraryById(10L)).thenReturn(johns);
        loginAs(new CurrentUser(3L, "admin@example.com", "admin", null, "ADMIN", true));

        mockMvc.perform(delete("/api/itinerary/10")).andExpect(status().isNotFound());
        verify(itineraryService, never()).deleteItinerary(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }
}
