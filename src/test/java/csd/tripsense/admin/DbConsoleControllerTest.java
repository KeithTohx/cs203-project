package csd.tripsense.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import csd.tripsense.audit.AuditService;
import csd.tripsense.auth.CurrentUser;

/**
 * the controller on its own, with the service and the audit log mocked. 
 * what it has to prove is that nobody runs a query without being logged in, and that every query leaves a trace
 */
class DbConsoleControllerTest {

    private DbConsoleService service;
    private AuditService audit;
    private DbConsoleController controller;

    private final CurrentUser admin = new CurrentUser(3L, "admin@example.com", "admin", null, "ADMIN", true);

    @BeforeEach
    void setUp() {
        service = mock(DbConsoleService.class);
        audit = mock(AuditService.class);
        controller = new DbConsoleController(service, audit);
    }

    private DbQueryResponse twoRows() {
        return new DbQueryResponse(List.of("id"), List.of(List.of(1), List.of(2)), 2, false, 4);
    }

    private String auditedDetails() {
        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(AuditService.SQL_QUERY), eq(3L), eq("admin@example.com"), isNull(), details.capture());
        return details.getValue();
    }

    // ----- running a query -----

    @Test
    void aSuccessfulQueryReturnsTheResult() {
        when(service.run("SELECT id FROM country")).thenReturn(twoRows());

        assertThat(controller.run(new DbQueryRequest("SELECT id FROM country"), admin)).isEqualTo(twoRows());
    }

    @Test
    void aSuccessfulQueryIsAuditedWithWhoRanItAndWhatItWas() {
        when(service.run("SELECT id FROM country")).thenReturn(twoRows());

        controller.run(new DbQueryRequest("SELECT id FROM country"), admin);

        String details = auditedDetails();
        assertThat(details).startsWith("OK, 2 rows");
        assertThat(details).contains("SELECT id FROM country");
    }

    @Test
    void aRefusedQueryIsAuditedAndTheRefusalReachesTheAdmin() {
        ResponseStatusException refusal = new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Only SELECT statements can be run here: the console is read-only.");
        when(service.run("DELETE FROM user")).thenThrow(refusal);

        // the attempt matters as much as a success: this is the one someone would want to find later
        assertThatThrownBy(() -> controller.run(new DbQueryRequest("DELETE FROM user"), admin)).isSameAs(refusal);

        String details = auditedDetails();
        assertThat(details).startsWith("FAILED 400");
        assertThat(details).contains("read-only").contains("DELETE FROM user");
    }

    @Test
    void aQueryThatTimedOutIsAudited() {
        when(service.run("SELECT 1")).thenThrow(new ResponseStatusException(HttpStatus.REQUEST_TIMEOUT, "too slow"));

        assertThatThrownBy(() -> controller.run(new DbQueryRequest("SELECT 1"), admin))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.REQUEST_TIMEOUT));

        assertThat(auditedDetails()).startsWith("FAILED 408");
    }

    @Test
    void theOutcomeComesBeforeTheStatementSoALongStatementCannotHideIt() {
        // the audit log cuts details at 500 characters, so the part that matters has to be first
        String longSql = "SELECT '" + "x".repeat(2000) + "'";
        when(service.run(longSql)).thenReturn(twoRows());

        controller.run(new DbQueryRequest(longSql), admin);

        assertThat(auditedDetails()).startsWith("OK, 2 rows | ");
    }

    // ----- not logged in -----

    @Test
    void withNoLoggedInUserNothingRunsAndNothingIsAudited() {
        assertThatThrownBy(() -> controller.run(new DbQueryRequest("SELECT 1"), null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verifyNoInteractions(service);
        verify(audit, never()).record(anyString(), any(), any(), any(), any());
    }

    // ----- the table list -----

    @Test
    void theTableListComesFromTheService() {
        List<DbTableInfo> tables = List.of(new DbTableInfo("country", 2), new DbTableInfo("user", 1));
        when(service.listTables()).thenReturn(tables);

        assertThat(controller.tables()).isEqualTo(tables);
    }

    @Test
    void listingTablesIsNotAudited() {
        when(service.listTables()).thenReturn(List.of());

        controller.tables();

        // it shows names and counts, never data, and the page calls it on every load
        verifyNoInteractions(audit);
    }
}
