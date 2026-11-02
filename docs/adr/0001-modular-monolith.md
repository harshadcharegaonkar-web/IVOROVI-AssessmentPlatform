# ADR-0001: Modular monolith with isolated workers

* Status: Accepted (baseline)
* Date: 2026-11-02

## Context
The 21 business modules share heavy transactional coupling (e.g. publishing results touches evaluation, merit lists, audit,
and notifications). The same codebase must also run on-premises, where operational simplicity matters. A few
workloads are untrusted (candidate code), resource-heavy (vision), or long-running (LLM jobs).

## Decision
Build one Spring Boot application with module boundaries enforced by Spring Modulith, plus separate workers for
code execution, vision analysis, and async AI jobs, connected via RabbitMQ with a transactional outbox.

## Consequences
* Simple deployment and consistent transactions. A module can be extracted later because modules interact only via
  published APIs and events.
* Requires discipline: the architecture verification test is a merge gate.
