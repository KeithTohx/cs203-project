package csd.tripsense.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditServiceTest {

    private AuditLogRepository repository;
    private AuditService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuditLogRepository.class);
        service = new AuditService(repository);
    }

    @Test
    void record_savesTheEntry() {
        service.record(AuditService.LOGIN, 1L, "john@example.com", 1L, "details");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getAction()).isEqualTo("LOGIN");
        assertThat(saved.getActorId()).isEqualTo(1L);
        assertThat(saved.getActorEmail()).isEqualTo("john@example.com");
        assertThat(saved.getTargetId()).isEqualTo(1L);
        assertThat(saved.getDetails()).isEqualTo("details");
    }

    @Test
    void record_neverThrows_evenIfTheDatabaseFails() {
        when(repository.save(any(AuditLog.class))).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> service.record(AuditService.LOGIN, 1L, "john@example.com", 1L, null))
                .doesNotThrowAnyException();
    }

    @Test
    void record_truncatesVeryLongDetails() {
        service.record(AuditService.PROFILE_UPDATED, 1L, "john@example.com", 1L, "x".repeat(900));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getDetails()).hasSize(500);
    }

    @Test
    void search_withoutFilters_listsEverythingNewestFirst() {
        when(repository.findAllByOrderByIdDesc(any(Pageable.class))).thenReturn(Page.empty());

        service.search(null, "  ", 0, 20);

        verify(repository).findAllByOrderByIdDesc(any(Pageable.class));
    }

    @Test
    void search_byEmailOnly() {
        when(repository.findByActorEmailContainingIgnoreCaseOrderByIdDesc(eq("john"), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.search(" john ", null, 0, 20);

        verify(repository).findByActorEmailContainingIgnoreCaseOrderByIdDesc(eq("john"), any(Pageable.class));
    }

    @Test
    void search_byActionOnly_ignoresCapitalisation() {
        when(repository.findByActionOrderByIdDesc(eq("LOGIN_FAILED"), any(Pageable.class))).thenReturn(Page.empty());

        service.search(null, "login_failed", 0, 20);

        verify(repository).findByActionOrderByIdDesc(eq("LOGIN_FAILED"), any(Pageable.class));
    }

    @Test
    void search_byEmailAndAction() {
        when(repository.findByActorEmailContainingIgnoreCaseAndActionOrderByIdDesc(
                eq("john"), eq("LOGIN"), any(Pageable.class))).thenReturn(Page.empty());

        service.search("john", "login", 0, 20);

        verify(repository).findByActorEmailContainingIgnoreCaseAndActionOrderByIdDesc(
                eq("john"), eq("LOGIN"), any(Pageable.class));
    }

    @Test
    void searchForUser_withoutAction_returnsEverythingToDoWithThatAccount() {
        when(repository.findForUser(eq(4L), any(Pageable.class))).thenReturn(Page.empty());

        service.searchForUser(4L, null, 0, 20);

        verify(repository).findForUser(eq(4L), any(Pageable.class));
    }

    @Test
    void searchForUser_withAction_filtersByThatAction() {
        when(repository.findForUserAndAction(eq(4L), eq("ITINERARY_DELETED"), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.searchForUser(4L, "itinerary_deleted", 0, 20);

        verify(repository).findForUserAndAction(eq(4L), eq("ITINERARY_DELETED"), any(Pageable.class));
    }
}
