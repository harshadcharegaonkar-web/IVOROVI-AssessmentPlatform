# API examples — increment 1.7

All requests need a bearer token (`Authorization: Bearer $TOKEN`) unless they use an API key. Responses below are
abbreviated; identifiers are synthetic. The full contract is `docs/api/openapi.json`.

## Dashboards

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$API/api/v1/insights/executive"
```
```json
{"organization":{"universities":1,"colleges":2,"campuses":1,"departments":3},
 "academics":{"programs":3,"courses":4,"subjects":12,"activeAcademicYears":1,"activeBatches":5},
 "students":{"total":36,"active":31,"admissionRecords":34,"newAdmissions30Days":29},
 "staff":{"total":9,"active":9,"faculty":6},"people":{"activeUsers":7,"pendingInvitations":0},
 "imports":{"jobs30Days":2,"inProgress":0,"awaitingAction":1,"completed30Days":1,"failed30Days":0,"rowsImported30Days":24},
 "generatedAt":"2026-11-04T02:30:00Z"}
```
A section the caller may not see is absent (`null`), e.g. `imports` for a faculty member.

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$API/api/v1/insights/students?orgUnitId=$COLLEGE"
curl -s -H "Authorization: Bearer $TOKEN" "$API/api/v1/insights/imports?from=2026-10-01&to=2026-11-03"
```

## Reports

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$API/api/v1/reports"                     # catalogue
curl -s -H "Authorization: Bearer $TOKEN" "$API/api/v1/reports/students?status=ACTIVE&sort=name&page=0&size=25"
curl -s -H "Authorization: Bearer $TOKEN" -o students.xlsx "$API/api/v1/reports/students/export?format=XLSX&programId=$PROGRAM"
```
```json
{"key":"students","columns":[{"key":"studentNo","label":"Student ID","personal":false}, "…"],
 "rows":[["MIE26-0001","Aarav Sharma","Meridian Institute of Engineering","Computer Science and Engineering","B.Tech", "…"]],
 "page":0,"size":25,"totalElements":31,"totalPages":2,"personalIncluded":false}
```
Errors: `400 invalid_status`, `400 invalid_date_range`, `403` without `report.read` or the record permission,
`403` for `includePersonal=true` without `student.personal.read`, `422 export_too_large` above 50,000 rows.

## Notifications

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$API/api/v1/notifications?state=UNREAD&category=imports"
curl -s -X POST -H "Authorization: Bearer $TOKEN" "$API/api/v1/notifications/$ID/read"
curl -s -X POST -H "Authorization: Bearer $TOKEN" "$API/api/v1/notifications/$ID/unread"
```
```json
{"items":[{"id":"…","category":"imports","severity":"SUCCESS","title":"Import completed",
  "body":"mie-2026-intake.csv: completed. Open the result for details.","link":"/students/imports/…/result",
  "eventType":"student.import.finished","resourceType":"student_import","channel":"IN_APP","deliveryStatus":"DELIVERED"}],
 "page":0,"size":20,"totalElements":1,"totalPages":1,"unread":1,"unreadByCategory":{"imports":1}}
```

## Integrations

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' "$API/api/v1/integrations" -d '{
  "kind":"SIS_ERP","adapter":"generic-rest","name":"Campus SIS",
  "config":{"baseUrl":"https://sis.example.edu/api","healthPath":"/health","authType":"BEARER"},"secret":"<token>"}'
curl -s -X PUT  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' "$API/api/v1/integrations/$ID/enabled" -d '{"enabled":true}'
curl -s -X POST -H "Authorization: Bearer $TOKEN" "$API/api/v1/integrations/$ID/test"
curl -s -H "Authorization: Bearer $TOKEN" "$API/api/v1/integrations/$ID/runs?size=20"
curl -s -X POST -H "Authorization: Bearer $TOKEN" "$API/api/v1/integrations/$ID/runs/$RUN/retry"
```
The response contains `"secretSet":true` — never the secret. Private, loopback and metadata addresses are refused with
`400 invalid_target`.

## API clients

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' "$API/api/v1/api-clients" -d '{
  "name":"Timetable service","scopes":["academics.read","org_unit.read"],"allowedIps":["203.0.113.0/24"],"expiresAt":"2027-11-01T00:00:00Z"}'
```
```json
{"client":{"id":"…","name":"Timetable service","scopes":["academics.read","org_unit.read"],"status":"ACTIVE", "…"},
 "apiKey":"ivk_3f9c0d1e2a4b5c6d7e8f9a0b_<secret shown once>","keyId":"3f9c0d1e2a4b5c6d7e8f9a0b"}
```
```bash
curl -s -H "X-Api-Key: $KEY" "$API/api/v1/academics/programs"            # 200 (in scope)
curl -s -H "X-Api-Key: $KEY" "$API/api/v1/users"                         # 403 (out of scope)
curl -s -X POST -H "X-Api-Key: $KEY" "$API/api/v1/academics/programs"    # 403 api_key_read_only
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' "$API/api/v1/api-clients/$ID/rotate" -d '{"graceHours":24}'
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' "$API/api/v1/api-clients/$ID/revoke" -d '{"reason":"Contract ended"}'
```
Rejections: `401 invalid_api_key`, `401 api_key_expired`, `401 api_key_revoked`, `403 api_key_ip_not_allowed`.

## Webhooks

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' "$API/api/v1/webhooks" -d '{
  "name":"LMS roster","url":"https://lms.example.edu/hooks/ivorovi","eventTypes":["student.created","student.enrolment.*"]}'
# → {"endpoint":{…},"signingSecret":"whsec_… (shown once)"}
curl -s -X POST -H "Authorization: Bearer $TOKEN" "$API/api/v1/webhooks/$ID/test"
curl -s -H "Authorization: Bearer $TOKEN" "$API/api/v1/webhooks/$ID/deliveries?status=FAILED"
curl -s -X POST -H "Authorization: Bearer $TOKEN" "$API/api/v1/webhooks/$ID/deliveries/$DELIVERY/replay"
curl -s -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' "$API/api/v1/webhooks/$ID/status" -d '{"status":"PAUSED"}'
```
Delivered request:
```
POST /hooks/ivorovi
Content-Type: application/json
Ivorovi-Signature: t=1762223400,v1=5f2c…
Ivorovi-Event-Type: student.created
Ivorovi-Delivery-Id: 0193…

{"id":"0193…","type":"student.created","occurredAt":"2026-11-04T02:30:00Z","tenantId":"…",
 "data":{"resourceType":"student","resourceId":"0193…"}}
```
Verifying (Python): `hmac.compare_digest(hmac.new(secret.encode(), f"{t}.{body}".encode(), "sha256").hexdigest(), v1)` and
reject timestamps older than 5 minutes. Deliveries are at-least-once: de-duplicate on `id`.
