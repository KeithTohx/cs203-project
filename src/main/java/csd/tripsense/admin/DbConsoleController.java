package csd.tripsense.admin;

import java.util.List;

import csd.tripsense.audit.AuditService;
import csd.tripsense.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The admin database console: read-only queries against the whole database.
 * ADMIN accounts only (USER gets 403, not logged in gets 401), and every query is audited.
 */
@RestController
@RequestMapping("/api/admin/db")
public class DbConsoleController {

    private final DbConsoleService dbConsoleService;
    private final AuditService auditService;

    public DbConsoleController(DbConsoleService dbConsoleService, AuditService auditService) {
        this.dbConsoleService = dbConsoleService;
        this.auditService = auditService;
    }

    @Operation(summary = "List the tables in the database",
            description = "Every table and how many rows it holds, for the SQL console's sidebar. "
                    + "Tables SQLite keeps for itself are left out.")
    @ApiResponse(responseCode = "200", description = "The tables, by name")
    @ApiResponse(responseCode = "401", description = "Not logged in")
    @ApiResponse(responseCode = "403", description = "Not an admin")
    @GetMapping("/tables")
    public List<DbTableInfo> tables() {
        return dbConsoleService.listTables();
    }

    @Operation(summary = "Run a read-only SQL query",
            description = "Requires an ADMIN account and the X-XSRF-TOKEN header. Runs ONE SELECT (a WITH ... SELECT "
                    + "is fine) against the whole database. It is read-only: the connection itself refuses writes, "
                    + "and anything but a SELECT is refused up front. At most 500 rows come back (truncated says "
                    + "whether there were more), long text is cut at 2000 characters, and a query that runs more "
                    + "than 5 seconds is stopped. The password column of the user table is never returned. "
                    + "Every query, allowed or refused, is written to the audit log as SQL_QUERY.")
    @ApiResponse(responseCode = "200", description = "The result")
    @ApiResponse(responseCode = "400", description = "Refused (not a single SELECT, or it names the password column) "
            + "or SQLite rejected it, eg a table that does not exist. The message says which")
    @ApiResponse(responseCode = "401", description = "Not logged in")
    @ApiResponse(responseCode = "403", description = "Not an admin, or the X-XSRF-TOKEN header is missing")
    @ApiResponse(responseCode = "408", description = "The query ran too long and was stopped")
    @PostMapping("/query")
    public DbQueryResponse run(@Valid @RequestBody DbQueryRequest request,
                                @AuthenticationPrincipal CurrentUser admin) {
        if (admin == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not logged in");
        }

        try {
            DbQueryResponse result = dbConsoleService.run(request.sql());
            // after it succeeded, like every other audited action. the outcome comes first because the
            // audit details are cut at 500 characters and a long statement should not hide it
            auditService.record(AuditService.SQL_QUERY, admin.getId(), admin.getEmail(), null,
                    "OK, " + result.rowCount() + " rows | " + request.sql());
            return result;
        } catch (ResponseStatusException e) {
            // refused, invalid or too slow. the attempt is just as worth keeping as a success
            auditService.record(AuditService.SQL_QUERY, admin.getId(), admin.getEmail(), null,
                    "FAILED " + e.getStatusCode().value() + ": " + e.getReason() + " | " + request.sql());
            throw e;
        }
    }
}
