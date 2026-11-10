package csd.tripsense.notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class AdminChangeServiceTest {

    private AdminChangeRepository changes;
    private ApplicationEventPublisher events;
    private AdminChangeService service;

    @BeforeEach
    void setUp() {
        changes = mock(AdminChangeRepository.class);
        events = mock(ApplicationEventPublisher.class);
        service = new AdminChangeService(changes, events);
    }

    @Test
    @DisplayName("Should save the notice for the owner and tell their open tabs")
    void record_OtherUsersData_SavesAndPublishes() {
        service.record(4L, 3L, "An admin edited your trip 'Japan'.");

        ArgumentCaptor<AdminChange> saved = ArgumentCaptor.forClass(AdminChange.class);
        verify(changes).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(4L);
        assertThat(saved.getValue().getMessage()).isEqualTo("An admin edited your trip 'Japan'.");
        assertThat(saved.getValue().getReadAt()).isNull();
        verify(events).publishEvent(new AdminChangedEvent(4L));
    }

    @Test
    @DisplayName("Should not tell an admin about changes to their own trips")
    void record_OwnData_SavesNothing() {
        service.record(3L, 3L, "An admin edited your trip 'Japan'.");

        verify(changes, never()).save(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("Should save nothing when there is no owner to tell")
    void record_NoOwner_SavesNothing() {
        service.record(null, 3L, "An admin deleted your trip 'Japan'.");

        verify(changes, never()).save(any());
    }

    @Test
    @DisplayName("Should cut a very long message to what the column holds")
    void record_LongMessage_IsCut() {
        service.record(4L, 3L, "x".repeat(800));

        ArgumentCaptor<AdminChange> saved = ArgumentCaptor.forClass(AdminChange.class);
        verify(changes).save(saved.capture());
        assertThat(saved.getValue().getMessage()).hasSize(500).endsWith("…");
    }

    @Test
    @DisplayName("Should not fail the admin's change when the notice cannot be saved")
    void record_SaveFails_IsOnlyLogged() {
        when(changes.save(any())).thenThrow(new IllegalStateException("database is locked"));

        service.record(4L, 3L, "An admin edited your trip 'Japan'.");

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("Should mark the user's notices read up to the one given and say how many are left")
    void markAllRead_ReturnsWhatIsLeft() {
        when(changes.countByUserIdAndReadAtIsNull(4L)).thenReturn(1L);

        AdminChangeService.UnreadCount left = service.markAllRead(4L, 12L);

        verify(changes).markRead(eq(4L), eq(12L), any());
        assertThat(left.unreadCount()).isEqualTo(1L);
    }
}
