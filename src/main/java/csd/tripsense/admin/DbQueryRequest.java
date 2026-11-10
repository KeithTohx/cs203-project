package csd.tripsense.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// what the console sends to /api/admin/db/query
public record DbQueryRequest(
        @NotBlank(message = "sql must not be blank")
        @Size(max = 5000, message = "sql must be at most 5000 characters")
        String sql) {
}
