package csd.tripsense.admin;

import org.springframework.data.domain.Page;

import java.util.List;

/** A stable JSON shape for one page of results (instead of serialising Spring's Page object). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
