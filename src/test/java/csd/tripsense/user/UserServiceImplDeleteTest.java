package csd.tripsense.user;

import csd.tripsense.chat.ChatbotService;
import csd.tripsense.impact.ImpactRepository;
import csd.tripsense.itinerary.ItineraryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import csd.tripsense.user.AccountRuleException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The two ways of deleting an account, and the one shared cleanup behind them. */
class UserServiceImplDeleteTest {

    private UserRepository userRepository;
    private ItineraryRepository itineraryRepository;
    private ImpactRepository impactRepository;
    private ChatbotService chatbotService;
    private PasswordEncoder passwordEncoder;
    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        itineraryRepository = mock(ItineraryRepository.class);
        impactRepository = mock(ImpactRepository.class);
        chatbotService = mock(ChatbotService.class);
        passwordEncoder = new BCryptPasswordEncoder();
        service = new UserServiceImpl(userRepository, passwordEncoder, itineraryRepository,
                impactRepository, chatbotService);
    }

    private User accountWithPassword(String role, String password) {
        return new User("bob@example.com", "bob", passwordEncoder.encode(password), role);
    }

    // ---------- the shared cleanup ----------

    @Test
    void deleteUserAndData_removesChatHistory_thenImpacts_thenItineraries_thenTheAccount() {
        User bob = accountWithPassword("USER", "password123");
        when(userRepository.findById(4L)).thenReturn(Optional.of(bob));
        when(itineraryRepository.findByUserId(4L)).thenReturn(List.of());

        service.deleteUserAndData(4L);

        InOrder order = inOrder(chatbotService, impactRepository, itineraryRepository, userRepository);
        order.verify(chatbotService).deleteAllConversationsFor(4L);
        order.verify(impactRepository).deleteByUserId(4L);
        order.verify(itineraryRepository).deleteAll(any());
        order.verify(itineraryRepository).flush();
        order.verify(userRepository).delete(bob);
    }

    @Test
    void deleteUserAndData_unknownAccount_throwsNotFoundAndDeletesNothing() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteUserAndData(99L)).isInstanceOf(UserNotFoundException.class);

        verify(chatbotService, never()).deleteAllConversationsFor(any());
        verify(userRepository, never()).delete(any(User.class));
    }

    @Test
    void deleteUserAndData_theLastAdmin_isRefused_forEveryCaller() {
        User admin = accountWithPassword("ADMIN", "admin123");
        when(userRepository.findById(3L)).thenReturn(Optional.of(admin));
        when(userRepository.countEnabledByRole("ADMIN")).thenReturn(1L);   // if your countByRole returns int, use 1

        assertThatThrownBy(() -> service.deleteUserAndData(3L))
                .isInstanceOfSatisfying(AccountRuleException.class,
                        e -> assertThat(e.getKind()).isEqualTo(AccountRuleException.Kind.CONFLICT));

        verify(chatbotService, never()).deleteAllConversationsFor(any());
        verify(userRepository, never()).delete(any(User.class));
    }

    @Test
    void deleteUserAndData_anAdminWhenAnotherAdminExists_isAllowed() {
        User admin = accountWithPassword("ADMIN", "admin123");
        when(userRepository.findById(5L)).thenReturn(Optional.of(admin));
        when(userRepository.countEnabledByRole("ADMIN")).thenReturn(2L);   // if your countByRole returns int, use 2
        when(itineraryRepository.findByUserId(5L)).thenReturn(List.of());

        service.deleteUserAndData(5L);

        verify(userRepository).delete(admin);
    }

    // ---------- a user deleting their own account ----------

    @Test
    void deleteAccount_withTheRightPassword_goesThroughTheSharedCleanup() {
        User bob = accountWithPassword("USER", "password123");
        when(userRepository.findById(4L)).thenReturn(Optional.of(bob));
        when(itineraryRepository.findByUserId(4L)).thenReturn(List.of());

        service.deleteAccount(4L, "password123");

        verify(chatbotService).deleteAllConversationsFor(4L);
        verify(userRepository).delete(bob);
    }

    @Test
    void deleteAccount_withTheWrongPassword_isRefusedAndDeletesNothing() {
        User bob = accountWithPassword("USER", "password123");
        when(userRepository.findById(4L)).thenReturn(Optional.of(bob));

        assertThatThrownBy(() -> service.deleteAccount(4L, "wrong-password"))
                .isInstanceOfSatisfying(AccountRuleException.class,
                        e -> assertThat(e.getKind()).isEqualTo(AccountRuleException.Kind.INVALID_INPUT));

        verify(chatbotService, never()).deleteAllConversationsFor(any());
        verify(userRepository, never()).delete(any(User.class));
    }
}
