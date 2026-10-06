# Kafka Integration Plan — Admin Auth Service (Phase: Producer)

Service publishes **Audit/Log Event** và **Notification Event** lên Kafka làm event bus cho hệ sinh thái Auto-Earning.

| Thông tin | Nội dung |
| --- | --- |
| Phiên bản | v1.2 |
| Ngày | 30/09/2026 |
| Cập nhật | 30/09/2026 — bổ sung distributed lock, outbox cleanup, `OutboxEventMapper`, audit `MFA_ENABLED`, Confluent repo, tách tầng test; review v1.1: severity rule toàn enum, index cho `ORDER BY`, thống nhất `.properties`, ghi chú backfill + `AsyncConfig`; v1.2: thêm **Kafka UI (Kafbat)** cho dev/demo + sửa host port Schema Registry `8081→8082` (tránh đụng app chạy `8081`) |
| Tham chiếu | PLAN.md (mục 3, 5.4), TEST_CASES.md (GAP-09/10), BRD (R11, R13, BR-15/16/17, BR-18, D07) |
| Phạm vi phase này | Infrastructure (Kafka + Schema Registry) + Avro schema + **Producer** (outbox). **Consumer** (notification sender, SIEM/monitoring sink) = phase sau. |

---

## 1. Mục tiêu & nguyên tắc

- Kafka = **event bus** cho 2 luồng độc lập: `audit/log` và `notification` — tách schema, tách topic.
- **Không thay nguồn sự thật**: audit vẫn ghi vào `audit_events` (PostgreSQL) phục vụ query BR-18; Kafka là kênh phân phát real-time cho downstream (SIEM, monitoring, notification service).
- **Không mất event** (BR-18, R11): dùng **Transactional Outbox** — event ghi cùng transaction nghiệp vụ, relay async publish lên Kafka (at-least-once). Đây cũng là cơ hội fix GAP-09 (audit nuốt lỗi).
- **Không làm chậm API chính**: nghiệp vụ chỉ ghi outbox trong tx (1 INSERT); phần publish hoàn toàn async.
- Mọi event có `eventId` (UUID) + `correlationId` → consumer (phase sau) dedupe + truy vết end-to-end.
- ⚠️ **`recordEventAsync` bị loại bỏ** (critical): phương thức này dùng `@Async` chạy trên thread khác với transaction riêng — nếu outbox INSERT nằm trong `recordEvent`, gọi qua `recordEventAsync` sẽ **không cùng transaction** với nghiệp vụ → phá outbox pattern. Outbox relay đã đảm bảo async rồi; `@Async` ở đây trở nên thừa và nguy hiểm. Tất cả call site chuyển sang `recordEvent` (sync, cùng transaction).

---

## 2. Kiến trúc

```
ae-admin-auth-service
  Business logic (cùng PostgreSQL transaction)
      │
      ├── ghi audit_events (nguồn sự thật, như hiện tại)
      └── INSERT outbox_events (AuditEvent hoặc NotificationEvent)
                │
                ▼  OutboxRelay — @Scheduled poll 500ms, batch 100
          KafkaEventPublisher (KafkaTemplate + Avro + Schema Registry)
                │
                ▼
   Kafka ──▶ admin.auth.audit.events        (key = actorId)
        └──▶ admin.auth.notification.events (key = recipientId)
                │
                ▼ (phase sau — Consumer)
      Notification service (email/in-app) · SIEM/monitoring sink
```

**Vì sao outbox thay vì publish trực tiếp:** ghi DB + publish Kafka là **dual-write** — crash giữa 2 bước = mất event âm thầm (chính là dạng lỗi GAP-09 hiện tại). Outbox dùng chung 1 transaction → event chỉ tồn tại khi nghiệp vụ thành công; relay đảm bảo publish lại khi Kafka chết.

---

## 3. Infrastructure

### 3.1. docker-compose (dev) — mở rộng compose hiện có

| Service | Image | Port (host) | Ghi chú |
| --- | --- | --- | --- |
| `kafka` | `confluentinc/cp-kafka:7.6.1` | `9092` | **KRaft single-node** (không cần Zookeeper); PLAINTEXT chỉ dùng cho dev |
| `schema-registry` | `confluentinc/cp-schema-registry:7.6.1` | `8082` | Trỏ tới `kafka:9092`; healthcheck qua `/subjects`. ⚠️ Host port đổi khỏi `8081` vì **đụng app Spring Boot đang chạy `8081`** (container nội bộ vẫn listen 8081) |
| `kafka-ui` | `ghcr.io/kafbat/kafka-ui:latest` | `8090` | Xem chi tiết mục 3.2 — **dev/demo only, không đem sang prod** |

PostgreSQL (5433) + Redis (6380) giữ nguyên. Prod: SASL_SSL + ACL + RF=3 — xem open question #1.

### 3.2. Kafka UI (dev/demo) — Kafbat UI

| Thông tin | Giá trị |
| --- | --- |
| Image | `ghcr.io/kafbat/kafka-ui:latest` — bản được maintain tiếp của Provectus `kafka-ui` |
| URL | `http://localhost:8090` |
| Phạm vi | **Chỉ dev/demo — KHÔNG đem sang prod** (đọc được toàn bộ message, kể cả event `sensitive=true` chứa mật khẩu tạm) |

Service trong docker-compose:

```yaml
kafka-ui:
  image: ghcr.io/kafbat/kafka-ui:latest
  container_name: admin-auth-kafka-ui
  ports:
    - "127.0.0.1:8090:8080"        # bind localhost — UI không có auth mặc định
  environment:
    KAFKA_CLUSTERS_0_NAME: admin-auth-dev
    KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:9092
    KAFKA_CLUSTERS_0_SCHEMAREGISTRY: http://schema-registry:8081   # port NỘI BỘ container, không phải host port 8082
    DYNAMIC_CONFIG_ENABLED: "true"
  depends_on:
    - kafka
    - schema-registry
```

**Checklist demo trên UI:**

1. Cluster `admin-auth-dev` hiển thị 2 topic `admin.auth.audit.events` + `admin.auth.notification.events` (6 partitions).
2. Tab **Schema Registry**: 2 subject `<topic>-value` đăng ký thành công, compatibility `BACKWARD`.
3. Đăng nhập thử (`svc_maker`) → tab **Messages** của topic audit thấy `LOGIN_SUCCESS` **giải mã Avro đầy đủ field** (eventId, severity, actor...).
4. SUPERADMIN tạo tài khoản → notification topic có `ADMIN_ACCOUNT_CREATED`, channels `[EMAIL]`, `params.temporaryPassword` hiển thị rõ → thấy trực tiếp lý do nên chuyển activation link (OQ #3).
5. SUPERADMIN disable `svc_maker` → 2 event sinh song song: audit `ACCOUNT_DISABLED` (severity SECURITY) + notification `ACCOUNT_DISABLED` `[EMAIL, IN_APP, SECURITY_ALERT]`.
6. Lọc message theo key (actorId/recipientId) kiểm tra ordering per-actor.
7. (Phase sau) tab **Consumers** theo dõi consumer lag của notification service.

**Bảo mật**: UI không có đăng nhập mặc định → bind `127.0.0.1` như YAML trên; không expose ra network chung; không đưa vào compose prod. Event notification chứa mật khẩu tạm đọc được qua UI → càng thêm lý do chốt sớm OQ #3 (activation link).

### 3.3. Dependencies (pom)

| Loại | Artifact |
| --- | --- |
| BOM | `spring-kafka` (theo Spring Boot 3.3.4 BOM) |
| Schema | `org.apache.avro:avro:1.11.x`, `io.confluent:kafka-avro-serializer:7.6.x` |
| Codegen | `avro-maven-plugin` — generate POJO từ `.avsc` vào `target/generated-sources` |
| Test | `spring-kafka-test`, `org.testcontainers:kafka` + `confluentinc/cp-schema-registry` container |

> ⚠️ **Confluent Maven Repository**: `io.confluent:kafka-avro-serializer` **không có trên Maven Central** — phải thêm vào `pom.xml`:
> ```xml
> <repositories>
>   <repository>
>     <id>confluent</id>
>     <url>https://packages.confluent.io/maven/</url>
>   </repository>
> </repositories>
> ```
> Testcontainers Kafka image dùng `confluentinc/cp-kafka` và `confluentinc/cp-schema-registry` (trùng version 7.6.x với SR lib).

### 3.4. Cấu hình producer

| Config | Giá trị | Lý do |
| --- | --- | --- |
| `acks` | `all` + `enable.idempotence=true` | Không mất/k trùng message ở broker |
| `retries` / `delivery.timeout.ms` | max / 120s | Retry tự nhiên khi broker lag |
| `compression` | `zstd` | Payload audit có before/after JSON |
| `linger.ms` / `batch.size` | 10ms / 32KB | Throughput batch |
| `auto.register.schemas` | `true` (dev) / **`false` + CI** (prod) | Prod đăng ký schema có kiểm soát, chặn breaking-change |
| Compatibility | **`BACKWARD`** | Chỉ cho phép thêm field có default — không phá consumer cũ |
| Key / Value serializer | `StringSerializer` / `KafkaAvroSerializer` | Subject = `<topic>-value` (TopicNameStrategy) |

### 3.5. Topics (thiết kế sẵn tư duy cho consumer phase sau)

| Topic | Key (ordering) | Partitions | RF / min.insync.replicas | Retention | Ghi chú |
| --- | --- |:---:| --- | --- | --- |
| `admin.auth.audit.events` | `actorId` | 6 | 3 / 2 (prod) | 30 ngày (cấu hình) | Consumer: SIEM, monitoring (BR-17) |
| `admin.auth.notification.events` | `recipientId` | 6 | 3 / 2 (prod) | 14 ngày | Consumer: notification service — ordering theo người nhận |

- Key đảm bảo **thứ tự per-actor / per-recipient** (không yêu cầu ordering toàn topic).
- Dev: `auto.create.topics.enable=true`; Prod: script `kafka-topics --create` riêng + ACL cho service account chỉ `WRITE` vào 2 topic này.
- DLQ (`*.dlt`) thiết kế khi có consumer (phase sau).

---

## 4. Thiết kế Event (Avro)

### 4.1. Envelope chung (2 schema đều có)

| Field | Kiểu | Mục đích |
| --- | --- | --- |
| `eventId` | `string` (UUID) | **Idempotency** cho consumer (at-least-once → dedupe bắt buộc) |
| `eventType` | `enum` | Phân loại event |
| `occurredAt` | `long` (logicalType `timestamp-millis`) | Thời điểm nghiệp vụ (không phải thời điểm publish) |
| `serviceName` | `string` = `"ae-admin-auth-service"` | Truy vết đa service trên cùng bus |
| `correlationId` | `string` nullable | Từ MDC — truy vết end-to-end (R11) |

Schemas đặt tại `src/main/avro/` (`AuditEventV1.avsc`, `NotificationEventV1.avsc`); codegen tạo POJO vào package `com.example.adminauth.event`. Đăng ký schema ra Dev ngay bước đầu để chốt contract **trước khi** viết consumer.

### 4.2. `AuditEventV1` — phục vụ ghi log/audit (đối xứng bảng `audit_events` hiện có)

```
eventId, eventType(enum: LOGIN_SUCCESS | LOGIN_FAILED | ACCOUNT_LOCKED |
  ONBOARDING_REQUIRED | ONBOARDING_COMPLETED | MFA_VERIFIED | MFA_FAILED |
  TOKEN_REUSE_DETECTED | LOGOUT | PASSWORD_CHANGED | PASSWORD_RESET |
  ACCOUNT_CREATED | ACCOUNT_UPDATED | ACCOUNT_DISABLED | ACCOUNT_ENABLED |
  ROLE_ASSIGNED | SESSION_REVOKED | SESSION_REVOKED_ALL | MFA_ENABLED),
severity(enum: INFO | WARN | SECURITY),
actorId, actorUsername, targetId, targetType,
beforeState(nullable string), afterState(nullable string),   // JSON string như hiện tại
ipAddress, userAgent, correlationId, occurredAt, serviceName
```

- `severity` là field mới: `TOKEN_REUSE_DETECTED`, `ACCOUNT_LOCKED` = `SECURITY` → downstream (SIEM/SOC) alert được ngay không cần tự phân loại lại.
- **Rule severity áp cho toàn bộ enum** (tránh phải quyết từng event lúc code): `SECURITY` = dấu hiệu tấn công (`TOKEN_REUSE_DETECTED`, `ACCOUNT_LOCKED`); `WARN` = thao tác đặc quyền/sai xác thực (`PASSWORD_CHANGED`, `PASSWORD_RESET`, `ROLE_ASSIGNED`, `ACCOUNT_DISABLED`, `LOGIN_FAILED`, `MFA_FAILED`); `INFO` = còn lại (gồm `MFA_ENABLED`).
- ⚠️ **`MFA_ENABLED` thêm vào AuditEvent**: `MfaServiceImpl.confirmTotp` hiện chưa ghi audit — đây là security event bắt buộc phải audit (BR-18). Trong bảng 4.4 cột AuditEvent phải bổ sung `MFA_ENABLED`; không chỉ có NotificationEvent.

### 4.3. `NotificationEventV1` — phục vụ gửi thông báo

```
eventId, eventType(enum: ADMIN_ACCOUNT_CREATED | PASSWORD_RESET |
  ACCOUNT_DISABLED | ACCOUNT_ENABLED | ROLES_PERMISSIONS_CHANGED |
  SESSION_REVOKED | SESSION_REVOKED_ALL | ACCOUNT_LOCKED |
  TOKEN_REUSE_DETECTED | PASSWORD_CHANGED | MFA_ENABLED),
recipientId, recipientUsername, recipientEmail,
channels(array<enum: EMAIL | IN_APP | SECURITY_ALERT>),
templateCode (string),                // nội dung nằm ở consumer — event chỉ mang mã template + params
params(map<string,string>),           // tham số render (VD temporaryPassword, actorName, reason)
sensitive(boolean),                   // params chứa dữ liệu nhạy cảm → consumer cấm log
actorId, actorUsername, reason(nullable), occurredAt, correlationId, serviceName
```

- **Không nhúng nội dung email/i18n vào event** — chỉ `templateCode + params`; consumer (phase sau) quyết định ngôn ngữ/định dạng → thêm kênh mới không phải sửa producer.
- `sensitive=true` cho event chứa mật khẩu tạm → rule: không được ghi vào log ở bất kỳ tầng nào (khớp SEC-03 trong TEST_CASES.md).

### 4.4. Mapping call-site hiện tại → event

| Call site (code) | AuditEvent | NotificationEvent |
| --- | --- | --- |
| `AdminManagementServiceImpl.createAdmin` | `ACCOUNT_CREATED` | `ADMIN_ACCOUNT_CREATED` (EMAIL — mật khẩu tạm) |
| `resetPassword` | `PASSWORD_RESET` | `PASSWORD_RESET` (EMAIL) |
| `disableAdmin` / `enableAdmin` | `ACCOUNT_DISABLED` / `ACCOUNT_ENABLED` | `ACCOUNT_DISABLED` (EMAIL + IN_APP + SECURITY_ALERT) / `ACCOUNT_ENABLED` (IN_APP) |
| `assignRolesAndPermissions` | `ROLE_ASSIGNED` | `ROLES_PERMISSIONS_CHANGED` (EMAIL + IN_APP) |
| `AuthServiceImpl` lockout / reuse detection | `ACCOUNT_LOCKED` / `TOKEN_REUSE_DETECTED` | `ACCOUNT_LOCKED` (EMAIL + SECURITY_ALERT) / `TOKEN_REUSE_DETECTED` (EMAIL + SECURITY_ALERT) |
| `changePassword` / `completeOnboarding` | `PASSWORD_CHANGED` / `ONBOARDING_COMPLETED` | `PASSWORD_CHANGED` (EMAIL) / — |
| `SessionManagementServiceImpl.revoke*` (bởi admin khác) | `SESSION_REVOKED(_ALL)` | `SESSION_REVOKED` (IN_APP) / `SESSION_REVOKED_ALL` (IN_APP + EMAIL) |
| `MfaServiceImpl.confirmTotp` | `MFA_ENABLED` (severity=INFO) | `MFA_ENABLED` (EMAIL) |

---

## 5. Notification Policy (đề xuất email vs in-app)

> IN-APP = tin nhắn trong portal (consumer phase sau lưu inbox; hiển thị từ lần đăng nhập kế tiếp — với các action thu hồi session thì đây là kênh bền vững). SECURITY_ALERT = kênh SOC/monitoring (email nhóm bảo mật, không phải cho người dùng).

| Hành động của admin cấp cao lên cấp dưới | EMAIL | IN-APP | SECURITY | Lý do |
| --- |:---:|:---:|:---:| --- |
| Tạo tài khoản | ✔ | — | — | Người dùng chưa vào app được; email mang mật khẩu tạm + hướng dẫn onboarding |
| Reset mật khẩu | ✔ | — | — | Session bị thu hồi → in-app vô nghĩa; email mang mật khẩu tạm |
| Disable tài khoản | ✔ | ✔ | ✔ | Security-critical, phải biết ngay cả khi không online |
| Enable tài khoản | — | ✔ | — | Không khẩn |
| Đổi role/permission | ✔ | ✔ | ✔ | Bị thu hồi session, phải đăng nhập lại — cần biết lý do |
| Kick 1 session | — | ✔ | — | Thông tin ra mắt; người dùng còn phiên khác |
| Kick tất cả session | ✔ | ✔ | — | Mất toàn bộ phiên = phải đăng nhập lại |
| Khóa tài khoản do 5 lần sai | ✔ | ✔ | ✔ | Có thể đang bị tấn công — cảnh báo cả chủ sở hữu lẫn SOC |
| Phát hiện token reuse | ✔ | — | ✔ | Đỉnh nghiêm trọng: bắt buộc login lại + SOC điều tra |
| Đổi mật khẩu thành công | ✔ | — | — | Xác nhận bảo mật (chuẩn ngân hàng) |
| Bật MFA thành công | ✔ | — | — | Xác nhận + nhắc bảo quản backup codes |

**Khuyến nghị bảo mật (quan trọng):** hiện tại API trả `temporaryPassword` cho admin quản trị và (phase 1) email sẽ kèm mật khẩu tạm qua `params` — **không phải pattern an toàn nhất**. Nên chuyển sang **activation link** tái dùng cơ chế `onboardingToken` sẵn có (link TTL 15 phút, dùng 1 lần) — đưa vào open question #3 để chốt trước khi consumer ra đời.

---

## 6. Producer reliability — Transactional Outbox

### 6.1. Bảng `outbox_events` (Flyway `V3__outbox.sql`)

| Cột | Kiểu | Ghi chú |
| --- | --- | --- |
| `id` | UUID PK | Đồng thời là `eventId` trong payload |
| `topic` / `partition_key` | text | Topic đích + key |
| `event_type` | text | Filter/debug |
| `payload` | JSONB | Render Avro-compatible (relay sẽ build record từ đây) |
| `status` | `PENDING` / `PUBLISHED` / `FAILED` | |
| `attempts` / `next_attempt_at` | int / timestamptz | Backoff |
| `created_at` / `published_at` | timestamptz | Đo độ trễ end-to-end |

Index partial `(status, created_at, next_attempt_at)` `WHERE status='PENDING'` — phục vụ cả WHERE/backoff lẫn `ORDER BY created_at` của relay. Lưu ý: `id` là UUID v4 (ngẫu nhiên) nên **không order theo `id`** được.

### 6.2. `OutboxRelay` (thay cho consumer trong phase này)

- `@Scheduled(fixedDelay=500ms)` + `@EnableScheduling`: query batch 100 bản `PENDING` cũ nhất → publish → `PUBLISHED`.
- ⚠️ **Distributed lock bắt buộc (multi-instance safe)**: Nếu chạy > 1 instance (scale out / blue-green), 2 relay cùng poll sẽ publish trùng. Dùng **`SELECT ... FOR UPDATE SKIP LOCKED`** (PostgreSQL native, không cần thư viện thêm):
  ```sql
  SELECT * FROM outbox_events
  WHERE status = 'PENDING' AND next_attempt_at <= now()
  ORDER BY created_at
  LIMIT 100
  FOR UPDATE SKIP LOCKED
  ```
  Mỗi instance chỉ lock đúng batch của mình; instance khác tự skip sang bản chưa bị lock → không xung đột, không trùng.
- Lỗi publish: `attempts+1`, backoff lũy tiến (giây → phút), vẫn `PENDING`; sau **10 lần** → `FAILED` + **alert** (metric + log SECURITY) — row giữ lại để replay thủ công, không bao giờ xóa.
- Semantics: **at-least-once** — có thể trùng khi broker ack xong nhưng relay crash trước khi mark → consumer phase sau **bắt buộc dedupe theo `eventId`** (ghi rõ vào contract của topic).
- Outbox không làm chậm request: ghi outbox chỉ là **1 INSERT chung tx nghiệp vụ** (bình thường); phần **publish nằm ngoài transaction nghiệp vụ** — relay chạy nền async.
- Tương lai: thay poller bằng Debezium CDC khi hệ sinh thái lớn — interface `OutboxWriter` giữ nguyên, đổi transport.

### 6.3. `OutboxCleaner` — cleanup row PUBLISHED

- `@Scheduled(cron="0 0 3 * * *")` (3 giờ sáng): xóa row `status = 'PUBLISHED'` cũ hơn **14 ngày** (`published_at < now() - interval '14 days'`).
- Row `FAILED` **không xóa** — giữ lại để replay thủ công + điều tra.
- Batch delete (1000 row / lần, lặp) tránh lock lâu trên bảng lớn.
- Log số row đã xóa mỗi lần chạy (metric `outbox.cleaned` counter).
- Ghi chú backfill: consumer mới (phase sau) cần lịch sử **từ nguồn sự thật `audit_events` DB**, không tái đọc Kafka/outbox (Kafka chỉ giữ theo retention; row `PUBLISHED` đã bị cleaner dọn).

---

## 7. Cấu trúc code & call site sửa động

```
com.example.adminauth
 ├── event/                      (generated POJO từ .avsc)
 └── messaging/
     ├── KafkaEventPublisher     (KafkaTemplate<String, SpecificRecord>, wrap metric)
     ├── OutboxWriter            (INSERT outbox trong tx hiện tại)
     ├── OutboxEventMapper       (JSONB payload → Avro SpecificRecord, dựa theo event_type)
     ├── OutboxRelay             (@Scheduled poll SKIP LOCKED + publish + retry)
     ├── OutboxCleaner           (@Scheduled cron xóa PUBLISHED > 14 ngày, batch 1000)
     ├── NotificationService     (build NotificationEvent theo policy, gọi OutboxWriter)
     └── NotificationPolicy      (map action → channels, table mục 5)
```

- `AuditServiceImpl.recordEvent`: giữ nguyên query DB, **thêm 1 dòng** ghi outbox `AuditEvent` — cùng transaction; **fix luôn GAP-09** (hết nuốt lỗi: fail ghi audit → rollback nghiệp vụ security-critical) và GAP-10 (filter kết hợp) vì đụng đúng file. `recordEventAsync` bị xóa (xem mục 1).
- Sau khi xóa `recordEventAsync`: audit ghi **đồng bộ** trong tx nghiệp vụ → fail ghi audit/outbox = rollback nghiệp vụ — chủ ý (auditability > availability cho IAM service), hiệu năng không đáng kể (thêm 2 INSERT chung tx). `AsyncConfig` hết call site `@Async("taskExecutor")` — **repurpose thành `SchedulingConfig`**: giữ `@EnableScheduling` (cần cho `OutboxRelay` + `OutboxCleaner`), xóa `@EnableAsync` + bean `taskExecutor`.
- `NotificationService` gọi tại các call site bảng 4.4 — luôn nằm **trong cùng transaction** với thay đổi nghiệp vụ. **Ràng buộc quan trọng**: `NotificationService` và `OutboxWriter` chỉ được **INSERT**, không gọi external service, không query phức tạp — đảm bảo transaction ngắn, tránh lock contention.
- `OutboxEventMapper` nhận JSONB từ bảng outbox, dựa vào field `event_type` để chọn đúng Avro generated class và deserialize, tránh casting mù.
- Không sửa controller — toàn bộ nằm ở tầng service.

---

## 8. Observability & NFR

- Micrometer: `outbox.published` counter (per topic), `outbox.failed` counter, `outbox.backlog` gauge, `outbox.cleaned` counter, publish latency histogram. **Alert**: backlog > 1000 hoặc tồn tại `FAILED`.
- Log: mọi publish kèm `eventId + correlationId`; cấm log `params` của event `sensitive=true`.
- NFR: độ trễ outbox → Kafka P95 < 1.5s (poll 500ms + batch); không tăng độ trễ API chính (chỉ 1 INSERT chung tx); Kafka chết **không** làm API chết (chỉ backlog tăng).
- Dev monitoring: Kafka UI (mục 3.2) cung cấp real-time view topic, message, schema mà không cần tự build dashboard.

---

## 9. Kiểm thử (sơ bộ — chi tiết ra file TEST_CASES riêng khi code)

Tách thành 2 tầng để không làm chậm CI thông thường:

**Tầng 1 — Unit test** (nhanh, chạy mặc định với `mvn test`):
- Mock `KafkaTemplate` (không cần broker thật).
- `NotificationPolicy`: map đúng channels cho từng action.
- `OutboxRelay`: poll → publish → mark `PUBLISHED`; retry/backoff; `FAILED` sau 10 lần + không xóa.
- `OutboxCleaner`: xóa đúng row `PUBLISHED` > 14 ngày, không chạm `FAILED`.
- `OutboxEventMapper`: JSONB → Avro POJO đúng class theo `event_type`.
- Avro roundtrip: serialize → deserialize đủ field, không mất data.

**Tầng 2 — Integration test** (Testcontainers, profile `kafka-it`, chạy trong CI pipeline riêng):
- Container stack: `confluentinc/cp-kafka:7.6.1` + `confluentinc/cp-schema-registry:7.6.1` + PostgreSQL.
- Publish thật → đọc lại bằng test consumer → verify payload + key + `eventId`.
- `SKIP LOCKED` concurrent relay: 2 thread relay chạy song song → không duplicate publish.
- Kafka tắt giữa chừng → outbox giữ `PENDING`, bật lại → tự publish đủ, không mất, không trùng mark.
- Schema compatibility: thêm field có default → vẫn `BACKWARD` compatible với SR.

> ℹ️ **Cấu hình Kafka**: dự án đang dùng `.properties` (`application.properties` + `application-dev.properties`) — giữ nguyên convention, khai báo Kafka config vào đây, **không chuyển sang `.yml`**. Integration test dùng `@DynamicPropertySource` (không phải `@TestPropertySource` — cần giá trị runtime từ container) override bootstrap-servers + schema-registry-url sang dynamicPort của Testcontainers.

---

## 10. Mapping BRD

| BRD | Đáp ứng |
| --- | --- |
| R11 (idempotent, trạng thái, audit trail) | `eventId` + idempotent producer + consumer dedupe (contract); outbox có trạng thái end-to-end |
| R13 / BR-15 (đối soát) | Nền tảng event bus — downstream recon sau này subscribe audit topic |
| BR-17 (monitoring & exception) | Audit topic + severity + SECURITY_ALERT là nguồn cho dashboard/alert |
| BR-18 (audit & retention) | Kafka là kênh phân phát, DB vẫn nguồn sự thật; retention topic cấu hình được; không mất event (outbox) |
| D07 (notification, audit, monitoring) | Notification policy mục 5 + audit pipeline |

---

## 11. Open Questions (chốt trước khi code)

1. **Kafka platform OCB**: ngân hàng có cluster dùng chung (SASL_SSL, ACL, monitoring) hay dự án self-host trong namespace? Quyết định cấu hình prod + cách xin topic/ACL.
2. **Consumer phase sau thuộc team nào** (notification service trung tâm của OCB hay tự dựng)? Ảnh hưởng quyết định `templateCode` và kênh IN-APP (cần nơi lưu inbox).
3. **Mật khẩu tạm qua email**: giữ như phase 1 hay chuyển **activation link** tái dùng `onboardingToken` (khuyến nghị mạnh — link 1 lần, TTL 15 phút)?
4. **Retention Kafka** audit: 30 ngày mặc định có đúng chuẩn OCB (DB giữ vĩnh viễn theo BR-18)?
5. **Avro vs JSON Schema**: khuyến nghị Avro + Schema Registry (hệ sinh thái Confluent chuẩn); nếu OCB chuẩn hóa JSON Schema thì đổi **trước khi** có consumer.
6. **Subject strategy**: TopicNameStrategy (mỗi topic 1 subject) đủ cho phase này; nếu sau gộp nhiều event type/chung topic → RecordNameStrategy.

---

## 12. Roadmap triển khai

| Bước | Việc | Kết quả check |
|:---:| --- | --- |
| 1 | docker-compose (Kafka + SR + **Kafka UI**) + Confluent Maven repo + dependencies + cấu hình producer (`application.properties` / `application-dev.properties`) | `docker-compose up -d` → Kafka + SR healthy; Kafka UI mở tại `http://localhost:8090`; `mvn compile` OK |
| 2 | `.avsc` + codegen + đăng ký schema (dev auto-register) | POJO generated; subject xuất hiện trên SR |
| 3 | Script tạo topic (prod) / auto-create (dev) | `kafka-topics --describe` đúng partitions/retention |
| 4 | Flyway `V3__outbox.sql` + `OutboxWriter` + `OutboxEventMapper` | Unit test mapper pass |
| 5 | `OutboxRelay` (SKIP LOCKED + retry/backoff) + `OutboxCleaner` | Unit test relay + cleaner pass |
| 6 | Wire `AuditServiceImpl` (xóa `recordEventAsync`, thêm outbox + fix GAP-09/10) | Login/đổi quyền → outbox có AuditEvent; `MfaServiceImpl.confirmTotp` ghi thêm `MFA_ENABLED` |
| 7 | `NotificationService` + `NotificationPolicy` + wire 11 call site (bảng 4.4) | Mỗi action sinh đúng NotificationEvent + channels |
| 8 | Observability (metric + alert backlog) | Gauge/counter lên |
| 9 | Integration test Testcontainers (profile `kafka-it`) | Publish thật đọc lại OK; SKIP LOCKED OK; outage recovery OK |
| 10 | Update README (luồng + cấu hình + checklist) | — |
