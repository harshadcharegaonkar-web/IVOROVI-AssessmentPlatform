package com.ivorovi.exams;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Exams module API (contract C-1): the marks of items that are not marked automatically. Descriptive answers get their
 * marks from manual evaluation (source EVALUATION); coding answers from the isolated executor (source EXECUTION).
 * Scoring defers such an attempt; its score is written, complete and immutable, once every deferred item has marks.
 * Callers run in the attempt's tenant, inside their own transaction, and authorize their own actor first.
 */
public interface ItemResults {

    enum Source { EVALUATION, EXECUTION }

    /** One deferred item of an attempt; {@code awarded} is null until its marks are recorded. */
    record Item(UUID attemptId, int position, UUID questionVersionId, BigDecimal marks, Source source, BigDecimal awarded) {}

    /** Submitted attempts of a schedule that have answers deferred to {@code source} (marked or not). */
    List<UUID> deferredAttempts(UUID scheduleId, Source source);

    /** The institution (organization unit) a schedule's examinations belong to; empty when it has no attempts. */
    java.util.Optional<UUID> scheduleUnit(UUID scheduleId);

    /** The saved answer value of one position of a submitted attempt (null when unanswered). Never for an attempt in progress. */
    Object response(UUID attemptId, int position);

    /** The deferred items of an attempt (empty when the attempt has none or is not yet scored). */
    List<Item> deferred(UUID attemptId);

    /**
     * Records the final marks of one deferred item. Idempotent: recording the same marks again is a no-op. Refused when
     * the item is not deferred to {@code source}, when the marks lie outside 0…the item's marks, or when different marks
     * were already recorded (a change goes through re-evaluation). Returns true when this completed the attempt's score.
     */
    boolean record(UUID attemptId, int position, BigDecimal awarded, Source source, String detail);
}
