# AE Admin Auth Service — OCB Auto-Earning

Dịch vụ trung tâm **Quản lý Định danh, Xác thực, Phân quyền và Phiên đăng nhập (IAM)** cho Cổng quản trị **OCB Auto-Earning Admin Portal**.

[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.3.4](https://img.shields.io/badge/Spring%20Boot-3.3.4-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue.svg)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-7-red.svg)](https://redis.io/)
[![Apache Kafka](https://img.shields.io/badge/Apache%20Kafka-7.6.1-black.svg)](https://kafka.apache.org/)
[![License](https://img.shields.io/badge/Status-Active-success.svg)]()

---

## 📌 1. Giới thiệu & Vai trò

Trong hệ sinh thái **OCB Auto-Earning**, `ae-admin-auth-service` đóng vai trò là **Identity Provider (IdP) độc lập**:
* **Tách rời nghiệp vụ Auth:** Các dịch vụ nội bộ downstream (như `system-params-api`, Auto-Earning Engine, Report Service...) **không tự lưu mật khẩu hay quản lý phiên**, mà hoàn toàn tin cậy vào chữ ký điện tử của service này.
* **Xác minh phi tập trung (Decoupled Verification):** Downstream services tự động nạp public key qua endpoint `/.well-known/jwks.json` để thẩm định chữ ký JWT Access Token cục bộ với độ trễ nano-giây.

```
┌─────────────────────────────────────────────────────────────┐
│                 Admin Portal (Web / BFF)                    │
└──────────────┬───────────────────────────────┬──────────────┘
               │ Đăng nhập / MFA / Session      │ Gọi API nghiệp vụ kèm JWT
               ▼                               ▼
    ┌──────────────────────┐        ┌──────────────────────┐
    │ae-admin-auth-service │        │  system-params-api   │
    │   (IAM Trung tâm)    │        │  (Downstream Service)│
    │                      │        │                      │
    │ • JWT RS256 + JWKS   │        │ • Verify qua JWKS    │
    │ • Hybrid RBAC/ABAC   │        │ • Kiểm tra Scopes    │
    │ • TOTP MFA Onboarding│        │ • Maker-Checker rule │
    │ • Outbox -> Kafka    │        └──────────▲───────────┘
    └──────────┬───────────┘                   │ Tự verify public key
               │ ─── Public Key (JWKS) ────────┘
     ┌─────────┴─────────┐
     ▼                   ▼
┌──────────┐       ┌──────────┐       ┌──────────────────────┐
│PostgreSQL│       │  Redis   │       │ Apache Kafka Cluster │
│ (5433)   │       │  (6380)  │       │ Outbox Events (9092) │
└──────────┘       └──────────┘       └──────────────────────┘
```

> 📖 **Lưu ý:** Để xem đặc tả chi tiết toàn bộ các luồng nghiệp vụ, cấu trúc bảng CSDL và danh sách đầy đủ 26 REST APIs, vui lòng xem tài liệu:
> 👉 **[Tài liệu Đặc tả Yêu cầu Phần mềm (SRS.md)](docs/SRS.md)**

---

## 🚀 2. Các Tính năng Cốt lõi (Key Features)

* **Xác thực An toàn & Ký số Bất đối xứng (RS256):** Access Token (TTL 30 phút) được ký bằng cặp khóa RSA 2048-bit, công bố Public Key chuẩn RFC 7517 qua JWKS.
* **Xác thực 2 bước Bắt buộc (Mandatory TOTP MFA Onboarding):** 100% tài khoản mới bắt buộc quét mã QR Google Authenticator, đổi mật khẩu lần đầu và nhận 10 mã dự phòng (Backup Codes).
* **Quản lý Phiên tập trung & Kick Session từ xa:** Lưu trữ trạng thái phiên trên Redis (TTL 30m idle timeout). Hỗ trợ xem danh sách thiết bị và thu hồi phiên (Kick session) tức thì.
* **Xoay vòng Refresh Token & Chống Đánh cắp (Reuse Detection):** Mỗi token chỉ dùng một lần (One-time use). Nếu phát hiện token cũ bị dùng lại (Replay Attack) $\rightarrow$ Tự động thu hồi toàn bộ Token Family và kick toàn bộ phiên.
* **Phân quyền Lai (Hybrid RBAC / ABAC Scoping):** Quản lý Role (Tier + Preset) kết hợp gán trực tiếp Custom Grants kèm `scopes` theo từng microservice cụ thể.
* **Hàng rào Bảo mật Thứ bậc (Tier Guardrails):** Ngăn chặn triệt để hành vi leo thang đặc quyền (Tier 1 SUPERADMIN > Tier 2 OPERATIONS_ADMIN > Tier 3 SERVICE_ADMIN).
* **Transactional Outbox Pattern & Kafka Event Streaming:** Mọi sự kiện Kiểm toán (Audit) và Thông báo (Notification) được ghi nguyên tử vào bảng `outbox_events` (PostgreSQL) và relay nền lên **Apache Kafka** với **Avro Schema Registry** (`SKIP LOCKED`).

---

## 🛠️ 3. Hướng dẫn Khởi chạy Nhanh (Quick Start)

### Yêu cầu cài đặt
* **JDK 21 LTS**
* **Maven 3.9+**
* **Docker Desktop** (hoặc Docker Engine & Docker Compose v2)

---

### Bước 1: Khởi động Hạ tầng Docker (Postgres, Redis, Kafka, Schema Registry, Kafka UI)

Chạy lệnh sau tại thư mục gốc của dự án:
```powershell
docker-compose up -d
```

Kiểm tra trạng thái các container:
```powershell
docker-compose ps
```
Hệ thống sẽ chạy các container dịch vụ:
* **PostgreSQL:** Port `5433` (Database: `admin_auth_db`, user: `admin_user`, pass: `admin_password`)
* **Redis:** Port `6380`
* **Apache Kafka (KRaft mode):** Port `9092`
* **Confluent Schema Registry:** Port `8082` (nội bộ container `8081`)
* **Kafka UI (Kafbat):** Port `8090`

---

### Bước 2: Khởi động Ứng dụng Backend

```powershell
mvn spring-boot:run
```

Ứng dụng sẽ tự động chạy migration Flyway (`V1`, `V2`, `V3`) để tạo bảng và nạp dữ liệu mẫu ban đầu, sau đó lắng nghe tại cổng **`8081`** với context path là **`/api`**.

---

### Bước 3: Truy cập Giao diện Vận hành & Tài liệu API

| Cổng thông tin | Địa chỉ truy cập | Ghi chú |
|---|---|---|
| **Swagger UI** | [http://localhost:8081/api/swagger-ui/index.html](http://localhost:8081/api/swagger-ui/index.html) | Thử nghiệm trực tiếp 26 REST APIs |
| **OpenAPI Docs** | [http://localhost:8081/api/v3/api-docs](http://localhost:8081/api/v3/api-docs) | OpenAPI 3.0 JSON spec |
| **JWKS Discovery** | [http://localhost:8081/api/.well-known/jwks.json](http://localhost:8081/api/.well-known/jwks.json) | Public keys để downstream verify |
| **Kafka UI** | [http://localhost:8090](http://localhost:8090) | Giám sát Topics, Schemas, Messages |

---

## ⚙️ 4. Cấu hình Biến Môi trường (.env Configuration)

Dự án hỗ trợ cấu hình động qua file `.env` hoặc System Environment Variables. Bạn có thể sao chép file mẫu để tùy chỉnh cho môi trường của mình:
```powershell
cp .env.example .env
```

Bảng tổng hợp các biến môi trường chính:

| Phân nhóm | Biến môi trường | Giá trị mặc định (dev) | Mô tả |
|:---|:---|:---|:---|
| **Server** | `SERVER_PORT` | `8081` | Cổng HTTP của service |
| | `APP_CONTEXT_PATH` | `/api` | Base context path của API |
| | `SPRING_PROFILES_ACTIVE` | `dev` | Spring active profile |
| **PostgreSQL** | `DB_HOST` | `localhost` | Địa chỉ máy chủ PostgreSQL |
| | `DB_PORT` | `5433` | Cổng kết nối PostgreSQL |
| | `DB_NAME` | `admin_auth_db` | Tên cơ sở dữ liệu |
| | `DB_USER` | `admin_user` | Tài khoản kết nối CSDL |
| | `DB_PASSWORD` | `admin_password` | Mật khẩu kết nối CSDL |
| **Redis** | `REDIS_HOST` | `localhost` | Địa chỉ máy chủ Redis |
| | `REDIS_PORT` | `6380` | Cổng kết nối Redis |
| | `REDIS_TIMEOUT` | `2000ms` | Thời gian timeout kết nối Redis |
| **Apache Kafka** | `KAFKA_BOOTSTRAP_SERVERS`| `localhost:9092` | Địa chỉ Kafka Broker |
| | `SCHEMA_REGISTRY_URL` | `http://localhost:8082` | Địa chỉ Confluent Schema Registry |
| | `KAFKA_TOPIC_AUDIT` | `admin.auth.audit.events` | Topic phát sự kiện kiểm toán |
| | `KAFKA_TOPIC_NOTIFICATION`| `admin.auth.notification.events`| Topic phát sự kiện thông báo |
| **JWT & Security** | `JWT_ISSUER` | `https://auth.autoeaning.ocb.com.vn` | Tên đơn vị phát hành token |
| | `JWT_KEY_ID` | `ocb-admin-auth-key-1` | Định danh key trong JWKS |
| | `JWT_ACCESS_TOKEN_EXPIRATION_SECONDS` | `1800` | Thời hạn Access Token (30 phút) |
| | `JWT_REFRESH_TOKEN_EXPIRATION_SECONDS`| `86400` | Thời hạn Refresh Token (24 giờ) |
| | `JWT_KEYS_DIR` | `./keys` | Thư mục lưu trữ RSA key pair |
| **MFA TOTP** | `MFA_ISSUER` | `OCB-AutoEarning-Admin` | Tên hiển thị trên Authenticator |
| | `MFA_TOKEN_EXPIRATION_SECONDS` | `300` | Thời hạn token challenge (5 phút) |
| **Outbox Relay** | `OUTBOX_RELAY_ENABLED` | `true` | Bật/tắt tiến trình relay lên Kafka |
| | `OUTBOX_RELAY_FIXED_DELAY_MS` | `500` | Chu kỳ polling kiểm tra outbox (ms) |
| | `OUTBOX_RELAY_BATCH_SIZE` | `100` | Số event xử lý mỗi đợt (`SKIP LOCKED`)|
| | `OUTBOX_CLEANER_RETENTION_DAYS` | `14` | Thời gian lưu trữ event đã gửi (ngày) |

---

## 👥 5. Tài khoản Kiểm thử Mặc định (Seed Accounts)

Hệ thống đã nạp sẵn 4 tài khoản mẫu để phục vụ kiểm thử nhanh:

| Tài khoản | Username | Mật khẩu mặc định | Role | Tier | Mục đích kiểm thử |
|---|---|---|---|:---:|---|
| **Super Admin** | `superadmin` | `SuperAdmin@123456` | `SUPERADMIN` | 1 | Quản trị tối cao, toàn quyền wildcard `*` |
| **Ops Admin** | `ops_admin` | `OpsAdmin@123456` | `OPERATIONS_ADMIN` | 2 | Quản lý admin cấp dưới & kick session |
| **Service Maker** | `svc_maker` | `Maker@123456` | `SERVICE_ADMIN` | 3 | Maker trên scope `system-params-api` |
| **Service Checker**| `svc_checker` | `Checker@123456` | `SERVICE_ADMIN` | 3 | Checker trên scope `system-params-api` |

---

## 📂 6. Cấu trúc Thư mục Dự án (Project Structure)

```
ae-admin-auth-service/
├── src/main/
│   ├── avro/                               # Định nghĩa schema Apache Avro (.avsc)
│   │   ├── AuditEventV1.avsc
│   │   └── NotificationEventV1.avsc
│   ├── java/com/example/adminauth/
│   │   ├── controller/                     # REST Controllers (Auth, Admin, Me, Catalog, Audit, JWKS)
│   │   ├── service/                        # Interfaces & Implementations (Auth, Admin, Session, MFA...)
│   │   ├── messaging/                      # Outbox Relay (SKIP LOCKED), Publisher, Cleaner
│   │   ├── security/                       # Custom Evaluator (@authz), JWT Provider, Session Filter
│   │   ├── repository/                     # Spring Data JPA Repositories
│   │   ├── entity/                         # Hibernate JPA Entities & OutboxStatus
│   │   ├── dto/                            # Data Transfer Objects phân theo domain
│   │   └── exception/                      # Global Exception Handler & Business Exceptions
│   └── resources/
│       ├── application.properties          # Cấu hình Database, Redis, Kafka, JWT, Outbox
│       └── db/migration/                   # Flyway Migrations (V1 Schema, V2 Seed, V3 Outbox)
├── .env.example                            # File mẫu biến môi trường
├── .env                                    # Biến môi trường local (đã gitignore)
├── docker-compose.yml                      # Cụm hạ tầng Postgres, Redis, Kafka, Schema Reg, UI
├── docs/
│   ├── SRS.md                              # Tài liệu Đặc tả Yêu cầu Phần mềm chuẩn IEEE 830
│   ├── TEST_SCENARIOS.md                   # Hướng dẫn 9 Kịch bản kiểm thử End-to-End
│   ├── TEST_CASES.md                       # 110 Test Cases tự động & danh mục GAP kỹ thuật
│   └── KAFKA_PLAN.md                       # Thiết kế chi tiết Transactional Outbox & Event Streaming
└── pom.xml                                 # Cấu hình dependencies & Avro Maven Plugin
```

---

## 🧪 7. Kiểm thử Tự động (Automated Testing)

Dự án sở hữu bộ kiểm thử tự động gồm **109 Unit & Integration Tests** bao phủ toàn bộ các tầng Service, Repository, JWT, Redis Session và Outbox Relay.

Chạy toàn bộ test suite bằng Maven:
```powershell
.\mvnw.cmd clean test
```

Kết quả mong đợi:
```
[INFO] Results:
[INFO] Tests run: 109, Failures: 0, Errors: 0, Skipped: 0
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
```

---

## 📚 8. Trung tâm Tài liệu (Documentation Hub)

Để tìm hiểu sâu hơn về từng khía cạnh kỹ thuật, vui lòng tham khảo các tài liệu chuyên biệt:

1. 📘 **[SRS.md (Software Requirements Specification)](docs/SRS.md):** 
   Tài liệu đặc tả chuẩn IEEE 830: Mô tả chi tiết 6 Module chức năng, bảng đặc tả 26 REST APIs, mô hình Hybrid RBAC/ABAC, yêu cầu phi chức năng (NFR) và Ma trận truy vết yêu cầu (RTM).
2. 🧪 **[TEST_SCENARIOS.md (Test Scenarios & Demo Guide)](docs/TEST_SCENARIOS.md):**
   Hướng dẫn từng bước thực hiện 9 kịch bản demo kiểm thử thực tế qua Swagger UI và Kafka UI (TOTP MFA, Hybrid RBAC, Kick Session, Reuse Detection, Audit Trail, Tier Guardrails, Outbox Kafka).
3. 📋 **[TEST_CASES.md (Automated Test Cases & GAP Analysis)](docs/TEST_CASES.md):**
   Đặc tả chi tiết 110 test cases tự động, đối sánh mã lỗi HTTP và bảng phân tích nợ kỹ thuật (GAPs).
4. ⚙️ **[KAFKA_PLAN.md (Transactional Outbox & Kafka Architecture)](docs/KAFKA_PLAN.md):**
   Kiến trúc chi tiết về luồng chống Dual-Write, định dạng Avro, Confluent Schema Registry và thuật toán Polling `FOR UPDATE SKIP LOCKED`.
