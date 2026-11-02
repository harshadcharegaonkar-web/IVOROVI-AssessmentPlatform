package com.ivorovi.platform.web;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/** Stable pagination envelope for list endpoints (offset pagination; page is zero-based). */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public static final int MAX_PAGE_SIZE = 100;

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    public static int clampSize(int size) {
        return Math.clamp(size, 1, MAX_PAGE_SIZE);
    }
}
