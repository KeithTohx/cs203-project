package csd.tripsense.admin;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One query result. Every cell is a number, a boolean, text or null. A blob comes back as a
 * description of its size, and long text is cut, so one row cannot make the response huge.
 */
public record DbQueryResponse(
        @Schema(description = "Column names, in the order of each row's values")
        List<String> columns,
        @Schema(description = "At most 500 rows. Each row has one value per column")
        List<List<Object>> rows,
        @Schema(description = "How many rows are in this response")
        int rowCount,
        @Schema(description = "True when the query matched more than 500 rows and only the first 500 are here")
        boolean truncated,
        @Schema(description = "How long the query took, in milliseconds")
        long elapsedMs) {
}
