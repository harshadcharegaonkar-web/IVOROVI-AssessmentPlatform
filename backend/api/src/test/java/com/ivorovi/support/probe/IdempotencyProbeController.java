package com.ivorovi.support.probe;

import com.ivorovi.platform.idempotency.Idempotent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** TEST ONLY: exercises the idempotency machinery. Lives in test sources; excluded from the OpenAPI export. */
@RestController
@RequestMapping("/api/v1/test-probe")
public class IdempotencyProbeController {

    public static final AtomicInteger EXECUTIONS = new AtomicInteger();

    @PostMapping("/items")
    @Idempotent
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        EXECUTIONS.incrementAndGet();
        return Map.of("id", UUID.randomUUID().toString(), "name", body.getOrDefault("name", ""));
    }

    @PostMapping("/failing")
    @Idempotent
    public Map<String, Object> failing() {
        EXECUTIONS.incrementAndGet();
        throw new IllegalStateException("simulated server failure");
    }
}
