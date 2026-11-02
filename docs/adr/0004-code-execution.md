# ADR-0004: Code execution service — own orchestration, established isolation, no in-house sandbox

* Status: **Proposed**. Needs product-owner approval before Phase 6. Implementation starts with the spike below.
* Date: 2026-11-02
* Supersedes: tracker decision D-04

## Context

Coding assessments (module 12) run untrusted candidate code, sometimes thousands of submissions in a burst at
the end of an exam. Directive from the product owner: do **not** build a sandbox from scratch. Build our own
orchestration and execution management on top of established isolation technology, and evaluate Judge0 first.
Candidate code must never run inside the primary application server.

Mandatory controls: CPU and memory limits, timeouts, process limits, filesystem isolation, restricted network,
ephemeral environments, secure cleanup, audit and execution logs.

## Options evaluated

### A. Judge0 (self-hosted, integrated as the execution engine)

| Aspect | Finding |
|---|---|
| Licence | **GPL-3.0** ([repository](https://github.com/judge0/judge0)). Running it unmodified as a separate network service in our SaaS does not trigger copyleft for our code. **Shipping it inside on-premises bundles is distribution:** we must provide its source and licence, and any modification we make must also be GPL. Before bundling, legal review is required. |
| Security history | Three critical sandbox-escape CVEs in 2024 (CVE-2024-28185 CVSS 10.0, CVE-2024-28189 as a bypass of that fix, CVE-2024-29021) allowed host takeover. All are fixed in 1.13.1 ([NVD](https://nvd.nist.gov/vuln/detail/CVE-2024-28185), [Tanto Security analysis](https://tantosec.com/blog/judge0/), [SecurityWeek](https://www.securityweek.com/critical-vulnerabilities-in-judge0-lead-to-sandbox-escape-host-takeover/amp/)). The Tanto write-up also notes that `ALLOW_ENABLE_NETWORK` defaults to true. |
| Isolation model | Uses `isolate` (Linux namespaces + cgroups) inside **privileged** Docker containers. One isolation bug therefore leads to host compromise, which is exactly what the 2024 CVEs demonstrated. There is no hardware/VM boundary. |
| Maintenance | The latest release listed on GitHub is v1.13.1 (security release, April 2024) ([releases](https://github.com/judge0/api/releases)). We could not confirm the current release cadence, so treat maintenance as a risk. |
| Fit | Good language coverage and a simple API. However, its submission/test-case model, multi-tenancy, audit and result model do not match ours. We would still need our own orchestration layer on top. |

**Verdict:** do not adopt as the execution engine. The privileged-container model and copyleft distribution
burden for on-prem outweigh its convenience. Keep the door open for a `Judge0Backend` adapter only if a customer
already operates Judge0 and accepts the risk. If used, it runs in a dedicated VM and never on shared nodes.

### B. Container + gVisor (`runsc`)

User-space kernel that intercepts syscalls. Used in production by GKE Sandbox and Modal. Works without
nested virtualization, so it runs on ordinary cloud VMs and on-prem servers. Weaker boundary than a VM. 10–30%
overhead on syscall/I/O-heavy workloads, which matters little for typical judge workloads (CPU-bound, small I/O)
([comparison](https://edera.dev/stories/kata-vs-firecracker-vs-gvisor-isolation-compared),
[Northflank](https://northflank.com/blog/kata-containers-vs-firecracker-vs-gvisor)).

### C. MicroVM: Firecracker / Kata Containers

Hardware-virtualization boundary per execution. Firecracker is used by AWS Lambda/Fargate and E2B. Kata
Containers runs Firecracker or Cloud Hypervisor behind a Kubernetes `RuntimeClass`. This is the strongest
isolation for hostile multi-tenant code
([Fly.io](https://fly.io/learn/firecracker-vs-gvisor/), [Northflank](https://northflank.com/blog/firecracker-vs-gvisor)).
It requires KVM (bare metal or nested virtualization), which some clouds and many on-prem VMs do not provide.

### D. Build our own sandbox (seccomp/namespaces/nsjail glue)

Rejected by directive, and rightly so. It concentrates the hardest security problem in the smallest team.

## Decision

1. **Build `ivorovi-code-runner`, our own orchestration and execution-management service** (separate deployable,
   separate network zone, no database credentials of the main API). It owns:
   - job intake from RabbitMQ
   - language profiles (pinned image digests, compile/run commands, default limits)
   - hidden/sample test execution and output comparison (exact, token, float tolerance, custom checker)
   - result normalisation and verdicts
   - burst scheduling and back-pressure
   - the execution log
2. **Isolation is delegated to established technology behind an `IsolationBackend` SPI**, chosen per deployment:
   - **Kubernetes with KVM available (SaaS, dedicated): Kata Containers** `RuntimeClass` (Cloud Hypervisor or Firecracker). This is the preferred backend.
   - **Kubernetes without KVM:** gVisor `RuntimeClass`.
   - **On-prem without Kubernetes:** Docker Engine with the gVisor `runsc` runtime, on hosts dedicated to execution.
3. **Judge0 is not used.** An optional adapter may be written later under the conditions above.

## How each mandatory control is met

| Control | Mechanism (defence in depth) |
|---|---|
| CPU limit | Pod/container CPU quota (cgroups) + per-process `RLIMIT_CPU` set by the in-sandbox launcher |
| Memory limit | cgroup memory limit (OOM-kill = verdict "Memory limit exceeded") + `RLIMIT_AS` |
| Timeouts | CPU-time limit in the sandbox; wall-clock deadline enforced by the orchestrator, which kills the sandbox from outside |
| Process limits | cgroup `pids.max` + `RLIMIT_NPROC` (stops fork bombs) |
| Filesystem isolation | Read-only, digest-pinned language image; a single size-limited `tmpfs` work directory; no host mounts; no service-account token automount |
| Network | Sandbox has no network interface beyond loopback. Kubernetes `NetworkPolicy` deny-all ingress/egress is a second layer. No route to the API, database or metadata endpoint |
| Ephemeral environments | Fresh sandbox per execution job, never reused across candidates |
| Secure cleanup | Orchestrator deletes the sandbox on completion, failure or timeout. A periodic reaper removes anything older than its TTL. Work directories are tmpfs (memory only). Cleanup failures raise alerts |
| Runs as | Non-root UID inside the sandbox, all capabilities dropped, `no_new_privs`, seccomp default profile (on top of gVisor/VM) |
| Audit and execution logs | Each execution record stores: tenant, attempt/question reference, language, image digest, limits applied, start/end, CPU time, peak memory, exit code/signal, verdict, and truncated stdout/stderr (stored in object storage). Admin actions such as changing language profiles or limits go to the platform audit log |
| Never in the API server | Enforced architecturally: the API only publishes jobs to RabbitMQ and reads results. The code-runner has no route back except the result queue |

## Spike before implementation (Phase 6, week 1)

1. Measure cold-start and throughput for Kata (Cloud Hypervisor), Kata (Firecracker) and gVisor with a burst of
   2,000 submissions (Python, Java, C++), and confirm KVM availability on our target clouds.
2. Run an escape-attempt test suite inside each backend (fork bombs, memory bombs, `/proc` and `/sys` probes,
   network egress, metadata endpoint, symlink and mount tricks), so we don't simply assume isolation works.
3. Legal review of every bundled component's licence for on-prem distribution.

## Consequences

* The security-critical boundary is maintained by large, well-funded projects (gVisor, Kata, Firecracker). Our
  code focuses on orchestration, which is where our domain lies.
* Operating Kata requires KVM-capable nodes. This must be captured in the SaaS/dedicated infrastructure plan
  and in on-prem sizing guides.
* Language images are our responsibility: pinned digests, rebuilt and scanned regularly.
