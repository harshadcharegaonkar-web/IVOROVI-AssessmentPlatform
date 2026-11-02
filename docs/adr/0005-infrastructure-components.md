# ADR-0005: Open-source infrastructure components safe for on-premises redistribution

* Status: Accepted (Phase 0)

| Need | Choice | Why not the obvious alternative |
|---|---|---|
| Cache / transient state | **Valkey 8** (BSD-3) | Redis ≥ 7.4 is no longer under an OSI licence, which complicates bundling it for on-prem customers. Valkey speaks the same protocol, so any Redis-compatible managed service still works. |
| Object storage (dev / on-prem default) | **SeaweedFS** (Apache-2.0) | The MinIO community edition stopped publishing images and binaries. The application uses only the plain S3 API, so customers can use AWS S3, Ceph RGW, MinIO, etc. |
| Broker | RabbitMQ 4.3 (MPL-2.0) | — |
| IdP | Keycloak 26.8 (Apache-2.0) | quay.io is not reachable from our build environment, so the official Docker Hub image is used |
| Test mail | Mailpit (MIT) | dev only |

Re-evaluate at every major upgrade. Pin exact image tags (digests in production manifests).
