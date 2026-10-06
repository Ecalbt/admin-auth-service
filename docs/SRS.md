# TÀI LIỆU ĐẶC TẢ YÊU CẦU PHẦN MỀM (SRS)
## SOFTWARE REQUIREMENTS SPECIFICATION
### HỆ THỐNG XÁC THỰC VÀ PHÂN QUYỀN TRUNG TÂM (ADMIN AUTH SERVICE)
**DỰ ÁN: OCB AUTO-EARNING (SINH LỘC)**

---

| **Thuộc tính** | **Giá trị** |
|:---|:---|
| **Tên tài liệu** | Software Requirements Specification (SRS) - Admin Auth Service |
| **Dự án** | OCB Auto-Earning / OCB Sinh Lộc |
| **Hệ thống** | Nền tảng Quản trị Trung tâm (Admin Portal & Back-Office IAM) |
| **Phiên bản** | 1.2 - Cập nhật trạng thái FR-AUTH-05 & Outbox Producer sau khi đối chiếu code |
| **Tài liệu tham chiếu** | Account Service BRD (v2.0), docs/PLAN.md, docs/KAFKA_PLAN.md (v1.2), docs/TEST_SCENARIOS.md (9 kịch bản), docs/TEST_CASES.md |

---

## MỤC LỤC
1. [GIỚI THIỆU (INTRODUCTION)](#1-giới-thiệu-introduction)
   * [1.1 Mục đích tài liệu (Purpose)](#11-mục-đích-tài-liệu-purpose)
   * [1.2 Phạm vi sản phẩm (Product Scope)](#12-phạm-vi-sản-phẩm-product-scope)
   * [1.3 Đối tượng độc giả & Hướng dẫn đọc (Intended Audience)](#13-đối-tượng-độc-giả--hướng-dẫn-đọc-intended-audience)
   * [1.4 Định nghĩa, Từ viết tắt & Thuật ngữ (Definitions & Acronyms)](#14-định-nghĩa-từ-viết-tắt--thuật-ngữ-definitions--acronyms)
   * [1.5 Tài liệu tham chiếu (References)](#15-tài-liệu-tham-chiếu-references)
2. [MÔ TẢ TỔNG QUAN HỆ THỐNG (OVERALL DESCRIPTION)](#2-mô-tả-tổng-quan-hệ-thống-overall-description)
   * [2.1 Bối cảnh hệ thống (Product Perspective & Architecture Context)](#21-bối-cảnh-hệ-thống-product-perspective--architecture-context)
   * [2.2 Các phân hệ & Luồng tích hợp (System Interfaces & Topology)](#22-các-phân-hệ--luồng-tích-hợp-system-interfaces--topology)
   * [2.3 Phân loại Người dùng & Vai trò (User Classes and Actors)](#23-phân-loại-người-dùng--vai-trò-user-classes-and-actors)
   * [2.4 Môi trường vận hành (Operating Environment)](#24-môi-trường-vận-hành-operating-environment)
   * [2.5 Ràng buộc thiết kế & Triển khai (Design & Implementation Constraints)](#25-ràng-buộc-thiết-kế--triển-khai-design--implementation-constraints)
   * [2.6 Giả định & Sự phụ thuộc (Assumptions & Dependencies)](#26-giả-định--sự-phụ-thuộc-assumptions--dependencies)
3. [YÊU CẦU CHỨC NĂNG CHI TIẾT (SPECIFIC FUNCTIONAL REQUIREMENTS)](#3-yêu-cầu-chức-năng-chi-tiết-specific-functional-requirements)
   * [3.1 Module 1: Xác thực & Quản lý Định danh (Authentication & Identity)](#31-module-1-xác-thực--quản-lý-định-danh-authentication--identity)
   * [3.2 Module 2: Quản lý Phiên tập trung (Session Management & Remote Revocation)](#32-module-2-quản-lý-phiên-tập-trung-session-management--remote-revocation)
   * [3.3 Module 3: Quản trị Tài khoản & Phân quyền lai (Admin & Hybrid RBAC/ABAC)](#33-module-3-quản-trị-tài-khoản--phân-quyền-lai-admin--hybrid-rbacabac)
   * [3.4 Module 4: Công bố Khóa công khai (Public Key Cryptography & JWKS)](#34-module-4-công-bố-khóa-công-khai-public-key-cryptography--jwks)
   * [3.5 Module 5: Xuất bản Sự kiện Bất đồng bộ (Event Streaming & Outbox)](#35-module-5-xuất-bản-sự-kiện-bất-đồng-bộ-event-streaming--outbox)
   * [3.6 Module 6: Nhật ký Kiểm toán (Audit Trail)](#36-module-6-nhật-ký-kiểm-toán-audit-trail)
4. [YÊU CẦU GIAO DIỆN & TÍCH HỢP (EXTERNAL INTERFACE REQUIREMENTS)](#4-yêu-cầu-giao-diện--tích-hợp-external-interface-requirements)
   * [4.1 Giao diện người dùng / API Documentation (User Interface)](#41-giao-diện-người-dùng--api-documentation-user-interface)
   * [4.2 Giao diện RESTful API (Software Interfaces)](#42-giao-diện-restful-api-software-interfaces)
   * [4.3 Giao diện Truyền thông điệp (Kafka & Schema Registry Interfaces)](#43-giao-diện-truyền-thông-điệp-kafka--schema-registry-interfaces)
   * [4.4 Giao diện CSDL & Cache (Storage Interfaces)](#44-giao-diện-csdl--cache-storage-interfaces)
5. [YÊU CẦU PHI CHỨC NĂNG (NON-FUNCTIONAL REQUIREMENTS - NFR)](#5-yêu-cầu-phi-chức-năng-non-functional-requirements---nfr)
   * [5.1 An toàn thông tin & Bảo mật (Security)](#51-an-toàn-thông-tin--bảo-mật-security)
   * [5.2 Hiệu năng & Khả năng đáp ứng (Performance & Latency)](#52-hiệu-năng--khả-năng-đáp-ứng-performance--latency)
   * [5.3 Độ tin cậy & Tính sẵn sàng (Reliability & Availability)](#53-độ-tin-cậy--tính-sẵn-sàng-reliability--availability)
   * [5.4 Khả năng bảo trì & Mở rộng (Maintainability & Extensibility)](#54-khả-năng-bảo-trì--mở-rộng-maintainability--extensibility)
6. [MA TRẬN TRUY VẾT YÊU CẦU (REQUIREMENTS TRACEABILITY MATRIX - RTM)](#6-ma-trận-truy-vết-yêu-cầu-requirements-traceability-matrix---rtm)

---

## 1. GIỚI THIỆU (INTRODUCTION)

### 1.1 Mục đích tài liệu (Purpose)
Tài liệu **Đặc tả Yêu cầu Phần mềm (SRS)** này xác định chi tiết các yêu cầu chức năng, yêu cầu phi chức năng, tiêu chuẩn bảo mật và các ràng buộc kỹ thuật của microservice **Admin Auth Service** thuộc dự án **OCB Auto-Earning (Sinh Lộc)**. 
Tài liệu này đóng vai trò là căn cứ kỹ thuật chính thức để:
* Đội ngũ phát triển (Developers) lập trình và kiểm thử đơn vị.
* Đội ngũ kiểm thử (QA/QC) xây dựng kịch bản kiểm thử tích hợp (Test Scenarios / Test Cases) và nghiệm thu chức năng.
* Đội ngũ Vận hành & Bảo mật (DevOps/SecOps) phê duyệt giải pháp triển khai, phân tầng an ninh và cấu hình hạ tầng.

### 1.2 Phạm vi sản phẩm (Product Scope)
**Admin Auth Service** là dịch vụ trung tâm chịu trách nhiệm quản lý Định danh và Quyền truy cập (**IAM - Identity & Access Management**) cho toàn bộ hệ thống Cổng quản trị (Admin Portal) của dự án Auto-Earning.
* **Nằm trong phạm vi (In-Scope):**
  * Xác thực người dùng quản trị (Admin Authentication): Đăng nhập 2 bước, TOTP MFA, cơ chế Onboarding bắt buộc.
  * Cấp phát, xoay vòng và thu hồi Token: Access Token (JWT RS256), Refresh Token Rotation kèm cơ chế phát hiện tái sử dụng trái phép (Token Reuse Detection).
  * Quản lý phiên tập trung (Stateful Session Management trên Redis) hỗ trợ kiểm tra thời gian thực và thu hồi phiên từ xa (Remote Revocation / Kick Session).
  * Mô hình phân quyền lai (Hybrid RBAC/ABAC): Quản lý Roles (Tier & Preset) kết hợp gán trực tiếp Custom Grants (Permission kèm Scopes cụ thể).
   * Cơ chế bảo vệ thứ bậc tài khoản (Tier Guardrails): Phân cấp SUPERADMIN (Tier 1), OPERATIONS_ADMIN (Tier 2), SERVICE_ADMIN (Tier 3), THIRD_PARTY_ADMIN (Tier 4), AUDITOR (Tier 5).
  * Xuất bản sự kiện bất đồng bộ qua **Kafka** áp dụng **Transactional Outbox Pattern** và chuẩn hóa schema bằng **Avro / Confluent Schema Registry**.
  * Ghi nhận nhật ký kiểm toán bất biến (Audit Trail) cho toàn bộ các thao tác bảo mật và quản trị.
* **Ngoài phạm vi (Out-of-Scope):**
  * Các nghiệp vụ tài chính/ngân hàng lõi của Auto-Earning (như hạch toán dòng tiền A1/A2, lệnh quét Sweep-in/Sweep-out, mua bán chứng chỉ tiền gửi CD Flexi, tính lợi tức, đối soát tài khoản). Các nghiệp vụ này do Auto-Earning Engine và các microservices chuyên biệt phụ trách.

### 1.3 Đối tượng độc giả & Hướng dẫn đọc (Intended Audience)
* **Kỹ sư Phần mềm (Backend / Frontend Developers):** Đọc kỹ Mục 3 (Yêu cầu chức năng) và Mục 4 (Đặc tả API & Schema) để triển khai logic.
* **Kỹ sư Kiểm thử (QA/QC Engineers):** Đọc kỹ Mục 3 và Mục 6 (Ma trận RTM) để đối chiếu kịch bản kiểm thử.
* **Chuyên viên Bảo mật & Kiến trúc sư Giải pháp (SecOps & Solution Architects):** Đọc kỹ Mục 2, Mục 3.1, 3.2, 3.3 và Mục 5 để đánh giá tuân thủ chính sách bảo mật ngân hàng.

### 1.4 Định nghĩa, Từ viết tắt & Thuật ngữ (Definitions & Acronyms)

| Thuật ngữ | Tên tiếng Anh đầy đủ | Giải thích |
|:---|:---|:---|
| **SRS** | Software Requirements Specification | Tài liệu đặc tả yêu cầu phần mềm. |
| **BRD** | Business Requirements Document | Tài liệu yêu cầu nghiệp vụ từ Khối Kinh doanh / Vận hành. |
| **IAM** | Identity and Access Management | Quản lý định danh và quyền truy cập người dùng. |
| **JWT** | JSON Web Token | Chuẩn mở (RFC 7519) định nghĩa phương thức truyền thông tin an toàn dưới dạng JSON. |
| **RS256** | RSA Signature with SHA-256 | Thuật toán ký số bất đối xứng sử dụng cặp Public Key / Private Key. |
| **JWKS** | JSON Web Key Set | Chuẩn mở (RFC 7517) cho phép công bố Public Keys để các service khác tự verify JWT. |
| **MFA** | Multi-Factor Authentication | Xác thực đa yếu tố (kết hợp mật khẩu + thiết bị vật lý). |
| **TOTP** | Time-based One-Time Password | Mật khẩu dùng một lần dựa trên thời gian thực (chuẩn RFC 6238, ví dụ Google Authenticator). |
| **RBAC** | Role-Based Access Control | Kiểm soát truy cập dựa trên vai trò người dùng. |
| **ABAC** | Attribute-Based Access Control | Kiểm soát truy cập dựa trên thuộc tính/ngữ cảnh (ví dụ: phạm vi dữ liệu `scope`). |
| **Outbox Pattern** | Transactional Outbox Pattern | Mẫu thiết kế phân tán đảm bảo tính toàn vẹn giữa lưu trữ DB và xuất bản message lên Message Queue. |
| **Tier Guardrail** | Phân cấp Hàng rào Bảo vệ | Quy tắc chặn tài khoản cấp dưới thao tác trên tài khoản cấp cao hơn. |

### 1.5 Tài liệu tham chiếu (References)
1. `docs/Account Service BRD.md` — OCB Auto-Earning Business Requirements Document (v2.0, các mục D07, R11, R12, BR-13 đến BR-18).
2. `docs/PLAN.md` — Kế hoạch kiến trúc và thiết kế kỹ thuật chi tiết của Admin Auth Service.
3. `docs/KAFKA_PLAN.md` — Kế hoạch triển khai Transactional Outbox Pattern, Apache Kafka, Confluent Schema Registry và Kafka UI.
4. `docs/TEST_SCENARIOS.md` — Bộ **9 kịch bản** kiểm thử/demo thủ công qua Swagger UI (Kịch bản 8: Quên mật khẩu; Kịch bản 9: Event Bus Kafka & Outbox).
5. `docs/TEST_CASES.md` — Bộ 110 kịch bản kiểm thử tự động (kèm danh mục GAP phát hiện khi review code).
6. RFC 7519 (JWT), RFC 7517 (JWKS), RFC 6238 (TOTP), RFC 6749 (OAuth 2.0 Token Revocation).

---

## 2. MÔ TẢ TỔNG QUAN HỆ THỐNG (OVERALL DESCRIPTION)

### 2.1 Bối cảnh hệ thống (Product Perspective & Architecture Context)
Trước khi Admin Auth Service ra đời, các dịch vụ nội bộ (như `system-params-api`) phải tự quản lý bảng `app_users`, lưu trữ mật khẩu riêng lẻ và tự phân quyền cục bộ. Điều này dẫn đến sự phân mảnh tài khoản, thiếu cơ chế xác thực tập trung, không kiểm soát được phiên đăng nhập và tiềm ẩn nguy cơ bảo mật nghiêm trọng.

Admin Auth Service đóng vai trò là **Identity Provider (IdP) trung tâm** cho toàn bộ Admin Portal. Các service nghiệp vụ hạ tầng không còn lưu trữ mật khẩu hay cấp token cục bộ; thay vào đó, các service này trở thành **Resource Servers**, hoàn toàn tin cậy vào chữ ký điện tử của Admin Auth Service thông qua giao thức **JWKS**.

```
                           ┌────────────────────────────────────────────────────────┐
                           │                 ADMIN WEB BROWSER / BFF                │
                           └───────────────────────────┬────────────────────────────┘
                                                       │
                           ┌───────────────────────────┴────────────────────────────┐
                           │ HTTPS (REST API)                                       │
                           ▼                                                        ▼
           ┌───────────────────────────────┐                        ┌───────────────────────────────┐
           │     ADMIN AUTH SERVICE        │                        │   DOWNSTREAM RESOURCE APIS    │
           │      (Port 8081 /api)         │                        │  (system-params, AE-Engine)   │
           └───────┬───────────────┬───────┘                        └───────────────▲───────────────┘
                   │               │                                                │
          PostgreSQL│          Redis│(Session/Onboarding/MFA)                        │
         (Port 5433)         (Port 6380)                                            │
                   ▼               ▼                                                │
           ┌──────────────┐ ┌──────────────┐       Verifies JWT via JWKS (RS256)    │
           │ Postgres DB  │ │ Redis Store  │────────────────────────────────────────┘
           │ (Tables &    │ │ (sid TTL 30m)│
           │  Outbox)     │ └──────────────┘
           └───────┬──────┘
                   │ Outbox Relay Poller (Scheduled @Transactional)
                   ▼
           ┌──────────────────────────────────────────────┐
           │        APACHE KAFKA CLUSTER (Port 9092)      │
            │ Topics: admin.auth.audit.events              │
            │         admin.auth.notification.events       │
           └───────┬──────────────────────────────┬───────┘
                   │                              │
                   ▼                              ▼
     ┌───────────────────────────┐  ┌───────────────────────────┐
     │  SCHEMA REGISTRY (8082)   │  │    KAFKA UI (Port 8090)   │
     │  (Avro Serialization)     │  │ (Observability & Monitor) │
     └───────────────────────────┘  └───────────────────────────┘
```

### 2.2 Các phân hệ & Luồng tích hợp (System Interfaces & Topology)
1. **Lớp Lưu trữ Quan hệ (Relational Persistence):** PostgreSQL 16 (port `5433`), quản lý các thực thể quản trị, bảng danh mục, bảng phân quyền lai, nhật ký kiểm toán và bảng sự kiện `outbox_events`. Quản lý cấu trúc qua **Flyway Database Migrations**.
2. **Lớp Lưu trữ Trạng thái Phiên (In-Memory Session & Cache):** Redis 7 (port `6380`), lưu trữ session đang hoạt động (`sid`) và token tạm thời cho Onboarding/Password Reset (TTL 15/10 phút). *Lưu ý: Rate Limiting đã thiết kế trong PLAN.md nhưng chưa triển khai (GAP-12).*
3. **Lớp Phân phối Sự kiện (Event Streaming Layer):** 
   * Apache Kafka (Confluent Platform **7.6.1**, chế độ **KRaft single-node — không dùng Zookeeper**, port `9092`).
   * Confluent Schema Registry 7.6.1 (host port `8082`, nội bộ container `8081`), quản lý Avro schemas với quy tắc tương thích `BACKWARD`.
   * Kafka UI — Kafbat UI (host port `8090`, bind localhost), giám sát message + schema trực quan.
4. **Lớp Tích hợp Dịch vụ (Resource Services Integration):** Cung cấp endpoint công khai `GET /.well-known/jwks.json` cho phép mọi service downstream tự động tải public key về để thẩm định chữ ký JWT mà không cần gọi HTTP RPC ngược lại, đảm bảo kiến trúc decoupled; chi phí thẩm định tại downstream chỉ còn verify chữ ký cục bộ (mili-giây, xem NFR-PERF-01).

### 2.3 Phân loại Người dùng & Vai trò (User Classes and Actors)
Hệ thống quản lý 5 nhóm vai trò (theo Flyway seed V2) với các cấp bậc bảo mật (Tier) nghiêm ngặt:

| Nhóm Người Dùng / Vai Trò | Cấp bậc (Tier) | Mô tả & Trách nhiệm chính |
|:---|:---:|:---|
| **SUPERADMIN** | **Tier 1** | Quản trị viên cấp cao nhất. Nắm toàn bộ quyền hạn (wildcard `*`), bypass mọi Tier Guardrail; trực tiếp quản lý tài khoản OPERATIONS_ADMIN, cấu hình security (lockout, IP allowlist), xem toàn bộ audit. Các SUPERADMIN khác có toàn quyền thao tác lẫn nhau — guardrail chỉ chặn Tier thấp hơn. |
| **OPERATIONS_ADMIN** | **Tier 2** | Quản trị viên vận hành, được SUPERADMIN ủy quyền quản lý tài khoản **cấp dưới (Tier 3+)**: tạo/khóa/enable, reset mật khẩu, gán role/permission, quản lý phiên. Bị chặn tuyệt đối với Tier 1 và OPERATIONS_ADMIN khác; không tự thao tác trên chính mình. TOTP bắt buộc. |
| **SERVICE_ADMIN** | **Tier 3** | Quản trị viên nghiệp vụ Auto-Earning (Maker/Checker cấu hình tham số, monitoring, đối soát, báo cáo) theo **scope service** được gán qua Custom Grants. Không có quyền quản trị tài khoản admin. |
| **THIRD_PARTY_ADMIN** | **Tier 4** | Đại diện bên thứ ba (SPV1/SPV2/Vendor). **Chỉ đọc** dữ liệu được cấp theo phạm vi partner (đối soát/statement), cách ly dữ liệu theo `partner_id`, session policy chặt hơn. |
| **AUDITOR** | **Tier 5** | Chuyên viên Kiểm toán & Tuân thủ. **Read-only toàn hệ thống**: `audit:read`, `recon:read`, `report:read`, `config:read`, `role:read` — không có quyền ghi, không có `admin:read`, không có quyền xem session người khác. |

### 2.4 Môi trường vận hành (Operating Environment)
* **Ngôn ngữ & Nền tảng:** Java 21 LTS, Spring Boot 3.3.4.
* **Cơ sở dữ liệu:** PostgreSQL 16.x.
* **Cache & Memory Store:** Redis 7.2.x.
* **Message Broker:** Apache Kafka (Confluent Platform 7.6.x, chế độ KRaft — không dùng Zookeeper).
* **Schema Governance:** Confluent Schema Registry 7.6.x.
* **Môi trường Container:** Docker Engine 24+ & Docker Compose v2.

### 2.5 Ràng buộc thiết kế & Triển khai (Design & Implementation Constraints)
1. **Kiến trúc Hybrid JWT (Stateless Signature + Stateful Session Check):**
   * Chữ ký token phải là bất đối xứng (RS256 với cặp khóa RSA 2048-bit).
   * Token chứa claim `sid` (Session ID). Mọi request vào hệ thống bắt buộc phải kiểm tra sự tồn tại của `sid` trong Redis Session Store. Nếu session bị kick hoặc hết hạn, request bị từ chối ngay lập tức (HTTP 401) dù chữ ký số JWT vẫn còn hạn sử dụng.
2. **Phòng chống Lỗi kép khi Gửi Thông điệp (Dual-Write Problem):**
   * Tuyệt đối không gọi trực tiếp Kafka Producer bên trong transaction của PostgreSQL. Mọi sự kiện phát sinh bắt buộc phải được ghi vào bảng `outbox_events` trong cùng một Local DB Transaction (`@Transactional`).
   * Một tiến trình nền (Outbox Relay) định kỳ quét các bản ghi chưa xử lý (`status = 'PENDING'`) sử dụng cú pháp `FOR UPDATE SKIP LOCKED` để đẩy lên Kafka an toàn, tránh nghẽn thread và tránh trùng lặp.
3. **Ràng buộc Toàn vẹn Dữ liệu & Thứ tự Thực thi Hibernate (Flush Order Constraint):**
   * Các thực thể sử dụng `@GeneratedValue(strategy = GenerationType.IDENTITY)`. Khi thực hiện thay thế quyền/vai trò (xóa cũ và chèn mới), tầng Service bắt buộc phải kích hoạt lệnh `.flush()` trung gian giữa câu lệnh `deleteBy...` và `save(...)` để tránh vi phạm Unique Constraints trong PostgreSQL.

### 2.6 Giả định & Sự phụ thuộc (Assumptions & Dependencies)
* Hệ thống máy chủ được đồng bộ thời gian chuẩn NTP (sai số thời gian giữa các node không quá 1000ms để đảm bảo tính chính xác của thuật toán TOTP RFC 6238).
* Máy trạm của người dùng quản trị có cài đặt ứng dụng bảo mật Authenticator (Google Authenticator, Microsoft Authenticator hoặc tương đương) hỗ trợ chuẩn mã hóa TOTP 6 chữ số theo chu kỳ 30 giây.

---

## 3. YÊU CẦU CHỨC NĂNG CHI TIẾT (SPECIFIC FUNCTIONAL REQUIREMENTS)

### 3.1 Module 1: Xác thực & Quản lý Định danh (Authentication & Identity)

#### FR-AUTH-01: Đăng nhập cơ bản & Nhận diện trạng thái tài khoản
* **Mô tả:** Tiếp nhận thông tin đăng nhập của Admin (`username` và `password`), xác minh mật khẩu bằng BCrypt và kiểm tra trạng thái hoạt động của tài khoản.
* **Dữ liệu đầu vào:** `POST /api/v1/auth/login` gồm `{ "username": "...", "password": "..." }`.
* **Quy tắc nghiệp vụ:**
  1. Nếu tài khoản không tồn tại hoặc mật khẩu sai: Tăng biến đếm `failed_login_attempts`. Nếu vượt quá 5 lần liên tiếp $\rightarrow$ Tự động khóa tài khoản (chuyển sang trạng thái `LOCKED`) và trả lỗi `401 Unauthorized` (GlobalExceptionHandler map `LockedException` → 401).
  2. Nếu tài khoản ở trạng thái `DISABLED`: Từ chối đăng nhập với lỗi `403 Forbidden`.
  3. Nếu tài khoản ở trạng thái `PENDING_ACTIVATION` (tài khoản mới tạo chưa kích hoạt) hoặc cờ `must_change_password = true`: Hệ thống **không cấp Access Token** mà tự động chuyển sang luồng **Mandatory Onboarding**.
  4. Nếu tài khoản đã kích hoạt và đã cấu hình MFA: Sinh mã `mfaToken` tạm thời (dạng `<random>:<adminId>`) và trả về `{ "mfaRequired": true, "mfaToken": "..." }`. *GAP-01 (đã ghi nhận): hiện `mfaToken` chưa được lưu/kiểm tra với Redis — cần bổ sung validate trước khi verify (xem TEST_CASES.md GAP-01).*

#### FR-AUTH-02: Quy trình Onboarding & Kích hoạt MFA bắt buộc (Mandatory Onboarding)
* **Mô tả:** Đảm bảo 100% tài khoản Admin trong hệ thống phải hoàn tất việc liên kết ứng dụng Google Authenticator và đổi mật khẩu tạm thời thành mật khẩu cá nhân an toàn ngay lần truy cập đầu tiên.
* **Quy tắc nghiệp vụ:**
  1. Khi phát hiện tài khoản mới ở `FR-AUTH-01`: Hệ thống sinh ngẫu nhiên chuỗi bí mật `totpSecretKey`, chuyển thành chuỗi định dạng URI chuẩn `otpauth://totp/OCB-AutoEarning-Admin:...` (issuer cấu hình `app.mfa.issuer`) và mã hóa thành `totpQrCodeUri`.
  2. Hệ thống lưu tạm thông tin định danh vào Redis dưới khóa `onboarding:<token>` với thời gian sống (TTL) 15 phút.
  3. Quản trị viên mở ứng dụng Google Authenticator quét mã QR và gọi API `POST /api/v1/auth/onboarding/complete` mang theo:
     * `onboardingToken`: Chuỗi token tạm thời.
     * `newPassword`: Mật khẩu mới đáp ứng tiêu chuẩn an toàn (tối thiểu 12 ký tự, bao gồm chữ hoa, chữ thường, chữ số và ký tự đặc biệt).
     * `totpCode`: Mã xác thực 6 chữ số sinh ra từ ứng dụng Authenticator.
  4. Sau khi kiểm tra mã TOTP hợp lệ:
     * Cập nhật mật khẩu mới (được mã hóa BCrypt), xóa cờ `must_change_password`.
     * Kích hoạt trạng thái MFA chính thức (`mfa_enabled = true`).
     * Tự động sinh ra **10 mã dự phòng (Backup Codes)** gồm 8 ký tự ngẫu nhiên (mã hóa SHA-256 trước khi lưu vào DB) để người dùng lưu trữ phòng trường hợp mất thiết bị.
     * Chuyển trạng thái tài khoản từ `PENDING_ACTIVATION` sang `ACTIVE`.
     * Cấp phát trực tiếp cặp Access Token & Refresh Token đầu tiên cho phiên làm việc.

#### FR-AUTH-03: Xác thực Đa Yếu Tố (TOTP MFA Verification & Backup Codes)
* **Mô tả:** Tiếp nhận mã xác thực bước 2 cho các lần đăng nhập thông thường của tài khoản đã kích hoạt.
* **Dữ liệu đầu vào:** `POST /api/v1/auth/mfa/verify` gồm `{ "mfaToken": "...", "totpCode": 123456 }` hoặc `{ "mfaToken": "...", "backupCode": "ABC12345" }`.
* **Quy tắc nghiệp vụ:**
  1. *Hiện trạng (GAP-01):* `mfaToken` hiện chỉ được tách lấy `adminId`, **chưa được kiểm tra với Redis** — quy tắc đích (backlog): `mfaToken` phải được lưu Redis khi sinh và validate (tồn tại + TTL) trước khi cho verify, chặn việc bỏ qua bước 1 nếu kẻ xấu biết `adminId` và mã TOTP.
  2. Nếu người dùng nhập mã 6 số: Thẩm định mã dựa trên thuật toán TOTP RFC 6238 với secret key của admin (cho phép sai số 1 bước nhảy thời gian ±30 giây để bù đắp độ trễ mạng).
  3. Nếu người dùng sử dụng mã Backup Code: Kiểm tra mã đối sánh với danh sách backup codes chưa dùng trong DB. Nếu đúng, đánh dấu mã đó là đã sử dụng (`used = true`) để không thể tái sử dụng.
  4. Xác thực thành công: Cấp Access Token (JWT có claim `mfa_verified = true`) và Refresh Token.

#### FR-AUTH-04: Xoay vòng Refresh Token & Phòng chống Đánh cắp Token (Reuse Detection)
* **Mô tả:** Cấp mới Access Token khi hết hạn 30 phút mà không bắt người dùng phải đăng nhập lại từ đầu, đồng thời phát hiện kẻ tấn công đánh cắp token.
* **Dữ liệu đầu vào:** `POST /api/v1/auth/refresh` kèm cookie hoặc body chứa Refresh Token.
* **Quy tắc nghiệp vụ:**
  1. Mỗi Refresh Token được gán một định danh dòng họ (`family_id`) và chỉ có giá trị sử dụng **đúng 1 lần duy nhất** (One-time use).
  2. **Trường hợp hợp lệ:** Khi gửi Refresh Token $RT_1$ hợp lệ $\rightarrow$ Hệ thống thu hồi $RT_1$ (`revoked = true`), sinh ra cặp thẻ mới gồm Access Token mới và Refresh Token $RT_2$ thuộc cùng `family_id`.
  3. **Phát hiện tái sử dụng trái phép (Token Reuse Detection):** Nếu hệ thống nhận được yêu cầu refresh sử dụng một token $RT_1$ **đã từng bị thu hồi trước đó** $\rightarrow$ Hệ thống lập tức nhận diện nguy cơ rò rỉ token (Replay Attack). Cơ chế an ninh lập tức kích hoạt:
     * Thu hồi toàn bộ chuỗi token thuộc `family_id` đó.
     * Hủy bỏ toàn bộ phiên làm việc của tài khoản trên Redis.
     * Ghi nhận cảnh báo an ninh nghiêm trọng vào Audit Log.
     * Trả mã lỗi `401 Unauthorized` buộc đăng nhập lại từ đầu bằng mật khẩu và MFA.

#### FR-AUTH-05: Quên mật khẩu tự phục vụ qua TOTP (Self-Service Password Reset) — ✅ ĐÃ TRIỂN KHAI
* **Mô tả:** Cho phép quản trị viên tự đặt lại mật khẩu khi quên mà không phụ thuộc vào Superadmin hay kênh gửi email vốn có thể bị trễ hoặc lộ lọt.
* **Trạng thái:** ✅ Đã triển khai hoàn chỉnh — `AuthController` (`/password/forgot`, `/password/reset`), `AuthServiceImpl.forgotPassword/resetPassword`; reset token lưu Redis `pwd_reset:{adminId}` (TTL 10 phút, dạng `<random>:<adminId>`); audit `PASSWORD_FORGOT_REQUESTED` / `PASSWORD_RESET_FAILED`; có unit test trong `AuthServiceTest` và **Kịch bản 8** trong TEST_SCENARIOS.md. GAP-03 đã resolve.
* **Quy tắc nghiệp vụ:**
  1. **Bước 1 (Yêu cầu):** Gọi `POST /api/v1/auth/password/forgot` với `username`. Nếu tài khoản tồn tại và đã cấu hình MFA $\rightarrow$ Trả về `resetToken` (lưu Redis TTL 10 phút).
  2. **Bước 2 (Xác minh & Đổi mật khẩu):** Gọi `POST /api/v1/auth/password/reset` gửi kèm `resetToken`, mã TOTP 6 số **(hoặc backup code)** từ Google Authenticator và `newPassword`.
  3. Xác thực thành công: Tự động mở khóa tài khoản (nếu đang bị `LOCKED`), đổi mật khẩu mới, xóa sạch các phiên làm việc cũ đang mở trên Redis.

#### FR-AUTH-06: Đổi mật khẩu cá nhân (Change Password)
* **Mô tả:** Quản trị viên đang đăng nhập chủ động thay đổi mật khẩu của mình.
* **Dữ liệu đầu vào:** `POST /api/v1/me/password` gồm `{ "oldPassword": "...", "newPassword": "..." }`.
* **Quy tắc nghiệp vụ:** Kiểm tra mật khẩu cũ chính xác; mật khẩu mới phải khác mật khẩu cũ; sau khi đổi thành công $\rightarrow$ Thu hồi toàn bộ các phiên khác của admin đó trên các thiết bị khác.

#### FR-AUTH-07: Đăng xuất an toàn (Logout)
* **Mô tả:** Hủy bỏ phiên làm việc hiện tại của quản trị viên.
* **Dữ liệu đầu vào:** `POST /api/v1/auth/logout`.
* **Quy tắc nghiệp vụ:** Trích xuất `sid` từ Access Token hiện tại, xóa bản ghi session tương ứng khỏi Redis và đánh dấu thu hồi Refresh Token liên kết trong CSDL.

---

### 3.2 Module 2: Quản lý Phiên tập trung (Session Management & Remote Revocation)

#### FR-SESS-01: Truy vấn danh sách phiên hoạt động
* **Mô tả:** Cho phép người dùng xem danh sách các thiết bị/phiên đang đăng nhập của chính mình, hoặc cho phép cấp quản lý có quyền xem phiên của cấp dưới.
* **Endpoints:**
  * `GET /api/v1/me/sessions` (Xem phiên cá nhân).
  * `GET /api/v1/admins/{adminId}/sessions` (Yêu cầu quyền `session:read_any`).
* **Dữ liệu trả về:** Danh sách `AdminSessionDto` gồm: `sessionId`, `adminId`, `username`, `ipAddress`, `userAgent`, `createdAt`, `lastUsedAt`, `expiresAt`; sắp xếp theo `lastUsedAt` giảm dần.

#### FR-SESS-02: Thu hồi phiên cá nhân từ xa (Kick Session cá nhân)
* **Mô tả:** Quản trị viên phát hiện một phiên đăng nhập lạ ở máy khác và chủ động kick phiên đó từ xa.
* **Endpoint:** `DELETE /api/v1/me/sessions/{sessionId}`.
* **Quy tắc nghiệp vụ:** Xóa khóa `session:{sessionId}` trên Redis (kèm gạch `sid` khỏi set `admin_sessions:{adminId}`). Phiên bị kick khi gửi request tiếp theo sẽ bị bộ lọc bảo mật chặn lại với mã lỗi `401 Unauthorized`.

#### FR-SESS-03: Quản trị viên kick phiên cấp dưới
* **Mô tả:** Quản trị viên vận hành kick 1 phiên cụ thể hoặc kick toàn bộ phiên của một tài khoản cấp dưới (ví dụ khi nhân viên thôi việc hoặc nghi ngờ lộ máy trạm).
* **Endpoints:**
  * `DELETE /api/v1/admins/{adminId}/sessions/{sessionId}` (Yêu cầu quyền `session:revoke_any`).
  * `DELETE /api/v1/admins/{adminId}/sessions` (Thu hồi toàn bộ phiên của tài khoản chỉ định).
* **Ràng buộc:** Theo thiết kế phải tuân thủ Tier Guardrail — không kick phiên của tài khoản Tier cao hơn/ngang bằng. *Hiện trạng (GAP-02): code hiện chỉ kiểm tra permission `session:read_any`/`revoke_any`, chưa kiểm tra tier — cần bổ sung check tier như `enforceTierGuardrail` trước khi nghiệm thu.*

#### FR-SESS-04: Tự động thu hồi phiên theo sự kiện bảo mật (Event-Driven Invalidation)
* **Mô tả:** Hệ thống phải tự động xóa bỏ toàn bộ phiên hoạt động của một admin trên Redis khi phát sinh bất kỳ sự kiện bảo mật nào sau đây:
  1. Tài khoản bị chuyển sang trạng thái Vô hiệu hóa (`DISABLE`).
  2. Mật khẩu tài khoản bị Reset (bởi chính họ hoặc bởi Superadmin).
  3. Vai trò (Roles) hoặc Quyền hạn (Permissions) của tài khoản bị thay đổi (để bắt buộc người dùng đăng nhập lại và nhận JWT mới chứa snapshot quyền hạn mới nhất).

---

### 3.3 Module 3: Quản trị Tài khoản & Phân quyền lai (Admin & Hybrid RBAC/ABAC)

#### FR-ADM-01: Tạo mới tài khoản Admin với Custom Grants
* **Mô tả:** Cho phép cấp quản lý tạo tài khoản cho nhân sự mới, thiết lập Role mặc định và tùy chỉnh bóp hẹp quyền hạn cụ thể.
* **Endpoint:** `POST /api/v1/admins` (Yêu cầu quyền `admin:create`).
* **Dữ liệu đầu vào:**
  ```json
  {
    "username": "maker_01",
    "email": "maker01@ocb.com.vn",
    "fullName": "Nguyen Van A",
    "roleCode": "SERVICE_ADMIN",
    "initialPassword": "OptionalPassword@123",
    "customGrants": [
      { "permissionCode": "config:read", "scopes": ["system-params-api"] },
      { "permissionCode": "config:write", "scopes": ["system-params-api"] }
    ]
  }
  ```
* **Quy tắc nghiệp vụ:**
  1. Kiểm tra `username` và `email` là duy nhất trên toàn hệ thống.
  2. Nếu không truyền `initialPassword`: Tự động sinh ngẫu nhiên mật khẩu tạm thời 14 ký tự chuẩn an toàn.
  3. Gán Role và nạp quyền theo cơ chế **Hybrid Permission**:
     * Nếu không truyền `customGrants`: Nạp toàn bộ các quyền mặc định từ Preset của Role (`role_permissions`).
     * Nếu có truyền `customGrants`: **Bỏ qua preset thừa, chỉ cấp đúng các quyền được chỉ định cụ thể trong mảng `customGrants`**, đồng thời tự động bổ sung quyền `auth:self` để người dùng quản lý tài khoản của mình.
  4. Tài khoản được tạo ở trạng thái `PENDING_ACTIVATION` với cờ `must_change_password = true`.

#### FR-ADM-02: Hàng rào bảo mật thứ bậc (Tier Guardrails)
* **Mô tả:** Đảm bảo nguyên tắc phân quyền tối thiểu (Least Privilege) và chống leo thang đặc quyền (Privilege Escalation) giữa các cấp bậc quản trị.
* **Ma trận thực thi:**

| Hành vi thực hiện | Người thực hiện: SUPERADMIN (Tier 1) | Người thực hiện: OPERATIONS_ADMIN (Tier 2) | Người thực hiện: SERVICE_ADMIN (Tier 3) |
|:---|:---:|:---:|:---:|
| **Tạo tài khoản Tier 1** | Cho phép | **Chặn (403 Forbidden)** | **Chặn (403 Forbidden)** |
| **Tạo tài khoản Tier 2** | Cho phép | **Chặn (403 Forbidden)** | **Chặn (403 Forbidden)** |
| **Tạo tài khoản Tier 3** | Cho phép | Cho phép | **Chặn (403 Forbidden)** |
| **Reset PW / Disable Tier 1** | Cho phép | **Chặn (403 Forbidden)** | **Chặn (403 Forbidden)** |
| **Reset PW / Disable Tier 2** | Cho phép | **Chặn (403 Forbidden)** | **Chặn (403 Forbidden)** |
| **Reset PW / Disable Tier 3** | Cho phép | Cho phép | **Chặn (403 Forbidden)** |
| **Tự khóa/xóa chính mình** | **Chặn (422 Unprocessable)** | **Chặn (422 Unprocessable)** | **Chặn (422 Unprocessable)** |

#### FR-ADM-03: Cập nhật thông tin hồ sơ Admin
* **Endpoint:** `PATCH /api/v1/admins/{adminId}` (Yêu cầu quyền `admin:update`).
* **Chức năng:** Cập nhật thông tin họ tên (`fullName`) hoặc email (`email`). Nếu thay đổi email, phải kiểm tra trùng lặp với các tài khoản khác.

#### FR-ADM-04: Reset mật khẩu bởi Quản trị viên
* **Endpoint:** `POST /api/v1/admins/{adminId}/reset-password` (Yêu cầu quyền `admin:reset_password`).
* **Chức năng:** Đặt lại mật khẩu ngẫu nhiên tạm thời (14 ký tự), đặt cờ `must_change_password = true`, reset số lần đăng nhập sai về 0, và tự động kick toàn bộ phiên làm việc của tài khoản đó.

#### FR-ADM-05: Vô hiệu hóa (Disable) & Kích hoạt lại (Enable) tài khoản
* **Endpoints:**
  * `POST /api/v1/admins/{adminId}/disable` (Chuyển trạng thái sang `DISABLED`, kick toàn bộ session).
  * `POST /api/v1/admins/{adminId}/enable` (Chuyển trạng thái sang `ACTIVE`).

#### FR-ADM-06: Tinh chỉnh Roles & Permissions (Assign Roles & Hybrid Grants)
* **Endpoint:** `PUT /api/v1/admins/{adminId}/roles` (Yêu cầu quyền `admin:assign_role`).
* **Quy tắc nghiệp vụ:**
  1. Kiểm tra Tier Guardrail của người thực hiện so với tài khoản đích và Role đích.
  2. Xóa bỏ toàn bộ vai trò cũ trong bảng `admin_roles` $\rightarrow$ Thực hiện lệnh `.flush()` trung gian $\rightarrow$ Lưu các vai trò mới.
  3. Xóa bỏ toàn bộ quyền cũ trong bảng `admin_permissions` $\rightarrow$ Thực hiện lệnh `.flush()` trung gian $\rightarrow$ Lưu danh sách quyền mới (theo preset hoặc custom grants).
  4. Thu hồi toàn bộ phiên làm việc trên Redis và bắn sự kiện `ROLES_PERMISSIONS_CHANGED` lên Kafka.

#### FR-ADM-07: Truy vấn Danh mục Roles & Permissions (Catalog Query)
* **Endpoints:**
  * `GET /api/v1/roles`: Lấy toàn bộ danh sách Role kèm quyền preset mặc định.
  * `GET /api/v1/permissions`: Lấy toàn bộ danh mục Permission có trong hệ thống.

---

### 3.4 Module 4: Công bố Khóa công khai (Public Key Cryptography & JWKS)

#### FR-JWKS-01: Công bố endpoint JWKS chuẩn RFC 7517
* **Mô tả:** Cho phép các service khác trong hệ sinh thái Auto-Earning tự động tải public key để xác minh chữ ký của Access Token mà không cần chia sẻ mã bí mật đối xứng (symmetric secret).
* **Endpoint:** `GET /.well-known/jwks.json` (Public, không yêu cầu xác thực).
* **Định dạng dữ liệu trả về:**
  ```json
  {
    "keys": [
      {
        "kty": "RSA",
        "e": "AQAB",
        "use": "sig",
        "kid": "ocb-admin-auth-key-1",
        "alg": "RS256",
        "n": "u1P..."
      }
    ]
  }
  ```

---

### 3.5 Module 5: Xuất bản Sự kiện Bất đồng bộ (Event Streaming & Outbox) — ✅ Tầng Producer đã triển khai

#### FR-EVT-01: Ghi nhận sự kiện Outbox nguyên tử (Atomic Outbox Writing)
* **Mô tả:** Mọi hành động làm thay đổi dữ liệu hoặc trạng thái bảo mật đều phải sinh ra sự kiện tương ứng và lưu trữ vào bảng `outbox_events` trong cùng một transaction với nghiệp vụ chính.
* **Cấu trúc bản ghi Outbox (theo KAFKA_PLAN.md — Flyway V3):** `id` (UUID — đồng thời là `eventId`), `topic`, `partition_key`, `event_type`, `payload` (JSONB), `status` (`PENDING`, `PUBLISHED`, `FAILED`), `attempts`, `next_attempt_at`, `created_at`, `published_at`; index partial `(status, created_at, next_attempt_at) WHERE status='PENDING'`.

#### FR-EVT-02: Relay sự kiện lên Kafka với Avro & Schema Registry
* **Mô tả:** Một tiến trình nền (Scheduled Poller) quét các bản ghi `PENDING` trong bảng Outbox và gửi lên Kafka.
* **Quy tắc nghiệp vụ:**
  1. Quét theo mẻ (Batch size cấu hình được, mặc định **100 bản ghi**, poll mỗi 500ms) sử dụng `SELECT ... FOR UPDATE SKIP LOCKED` để an toàn khi scale nhiều instance.
  2. Serialize nội dung thông điệp bằng **Apache Avro** thông qua Schema Registry (compatibility `BACKWARD`).
  3. Định tuyến sự kiện theo Topic chuẩn:
     * Sự kiện Kiểm toán: topic `admin.auth.audit.events` (key = `actorId`), schema `AuditEventV1`.
     * Sự kiện Thông báo: topic `admin.auth.notification.events` (key = `recipientId`), schema `NotificationEventV1`.
  4. Khi Kafka Broker xác nhận ghi thành công (Ack) $\rightarrow$ `OutboxRelay` cập nhật trạng thái thành `PUBLISHED` + ghi `published_at`. Nếu thất bại $\rightarrow$ Tăng `attempts` (tối đa 10) với exponential backoff; vượt ngưỡng $\rightarrow$ `FAILED` + alert `CRITICAL_OUTBOX_ALERT`.

#### FR-EVT-03: Dọn dẹp sự kiện Outbox định kỳ (Outbox Cleanup)
* **Mô tả:** Tự động xóa (cron 03:00 hằng ngày, batch 1000) các bản ghi Outbox đã gửi thành công (`PUBLISHED`) cũ hơn **14 ngày**; bản ghi `FAILED` **không bao giờ xóa** — giữ để replay thủ công. Backfill lịch sử cho consumer mới đọc từ `audit_events` (nguồn sự thật), không tái đọc Kafka/outbox.

---

### 3.6 Module 6: Nhật ký Kiểm toán (Audit Trail)

#### FR-AUD-01: Ghi nhận vết kiểm toán bất biến (Append-Only Audit Logging)
* **Mô tả:** Lưu trữ lịch sử toàn bộ các hành động mang tính quản trị và bảo mật. Bảng `audit_events` là bảng chỉ ghi (Append-Only), không hỗ trợ cập nhật hay xóa bản ghi.
* **Thông tin ghi nhận:** `actorId` (username người thực hiện), `action` (mã hành động, ví dụ `LOGIN_SUCCESS`, `ROLE_ASSIGNED`), `targetId`, `beforeState`/`afterState` (JSON ghi nhận giá trị trước/sau thay đổi), `ipAddress`, `userAgent`, `correlationId`, `createdAt`.

#### FR-AUD-02: Truy vấn nhật ký kiểm toán (Audit Query & Filtering)
* **Endpoint:** `GET /api/v1/audit-events` (Yêu cầu quyền `audit:read`).
* **Tính năng:** Hỗ trợ phân trang (`page`, `size`), sắp xếp và bộ lọc theo `actorId`, `action`, khoảng thời gian (`start`, `end`). *Lưu ý GAP-10: filter hiện là chuỗi else-if — kết hợp `actorId` + `action` cùng lúc chỉ áp dụng `actorId`, cần sửa bằng JPA Specification.*

---

## 4. YÊU CẦU GIAO DIỆN & TÍCH HỢP (EXTERNAL INTERFACE REQUIREMENTS)

### 4.1 Giao diện người dùng / API Documentation (User Interface)
* Hệ thống là dịch vụ Backend REST API không có giao diện Web tĩnh riêng cho người dùng cuối.
* Cung cấp giao diện tài liệu tương tác **Swagger UI** tại đường dẫn:
  * URL: `http://<host>:8081/api/swagger-ui/index.html`
  * OpenAPI Specification: `http://<host>:8081/api/v3/api-docs`
  * Hỗ trợ nút **Authorize** với chuẩn định dạng `Bearer <JWT_Access_Token>` để kiểm thử trực tiếp trên trình duyệt.

### 4.2 Giao diện RESTful API (Software Interfaces)

Toàn bộ các API đều sử dụng định dạng JSON, Base path: `/api/v1`.

| Phương thức | Đường dẫn Endpoint | Quyền hạn yêu cầu | Mô tả chức năng |
|:---|:---|:---|:---|
| `POST` | `/v1/auth/login` | Public | Đăng nhập bước 1 với username và password |
| `POST` | `/v1/auth/onboarding/mfa/setup` | Public (Onboarding Token) | Lấy lại QR & secret TOTP bằng onboarding token |
| `POST` | `/v1/auth/onboarding/complete` | Public (Onboarding Token) | Hoàn tất kích hoạt MFA và đổi mật khẩu lần đầu |
| `POST` | `/v1/auth/mfa/verify` | Public (MFA Token) | Xác thực bước 2 với mã TOTP hoặc Backup Code |
| `POST` | `/v1/auth/refresh` | Public (Refresh Token) | Cấp mới Access Token (Token Rotation) |
| `POST` | `/v1/auth/password/forgot` | Public | Yêu cầu token đặt lại mật khẩu tự phục vụ |
| `POST` | `/v1/auth/password/reset` | Public (Reset Token) | Đặt lại mật khẩu qua mã xác thực TOTP |
| `POST` | `/v1/auth/logout` | Authenticated | Đăng xuất và hủy phiên hiện tại |
| `GET` | `/.well-known/jwks.json` | Public | Lấy danh sách Public Keys thẩm định chữ ký JWT |
| `GET` | `/v1/me` | Authenticated | Lấy thông tin cá nhân, roles và permissions hiện tại |
| `POST` | `/v1/me/password` | Authenticated | Tự thay đổi mật khẩu (kiểm tra policy + lịch sử 5 mật khẩu) |
| `POST` | `/v1/me/mfa/totp/setup` | Authenticated | Khởi tạo TOTP MFA (trả secret + QR URI) |
| `POST` | `/v1/me/mfa/totp/enable` | Authenticated | Kích hoạt TOTP (trả 10 backup codes) |
| `GET` | `/v1/me/sessions` | Authenticated | Xem danh sách các phiên đăng nhập cá nhân |
| `DELETE` | `/v1/me/sessions/{sid}` | Authenticated | Tự kick một phiên đăng nhập của chính mình |
| `DELETE` | `/v1/me/sessions` | Authenticated | Tự kick toàn bộ phiên của mình |
| `GET` | `/v1/admins` | `admin:read` | Lấy danh sách tài khoản Admin (phân trang) |
| `POST` | `/v1/admins` | `admin:create` | Tạo mới tài khoản Admin với Custom Grants |
| `GET` | `/v1/admins/{id}` | `admin:read` | Xem thông tin chi tiết một tài khoản Admin |
| `PATCH` | `/v1/admins/{id}` | `admin:update` | Cập nhật thông tin họ tên, email tài khoản |
| `POST` | `/v1/admins/{id}/reset-password` | `admin:reset_password` | Quản trị viên reset mật khẩu cấp dưới |
| `POST` | `/v1/admins/{id}/disable` | `admin:disable` | Vô hiệu hóa tài khoản admin cấp dưới |
| `POST` | `/v1/admins/{id}/enable` | `admin:enable` | Kích hoạt lại tài khoản admin cấp dưới |
| `PUT` | `/v1/admins/{id}/roles` | `admin:assign_role` | Cập nhật toàn bộ Role và Quyền hạn của admin |
| `GET` | `/v1/admins/{id}/sessions` | `session:read_any` | Xem danh sách các phiên của admin cấp dưới |
| `DELETE` | `/v1/admins/{id}/sessions/{sid}` | `session:revoke_any` | Kick một phiên cụ thể của admin cấp dưới |
| `DELETE` | `/v1/admins/{id}/sessions` | `session:revoke_any` | Kick toàn bộ phiên của admin cấp dưới |
| `GET` | `/v1/roles` | Authenticated | Lấy danh mục Roles và preset permissions |
| `GET` | `/v1/permissions` | Authenticated | Lấy danh mục Permissions của toàn hệ thống |
| `GET` | `/v1/audit-events` | `audit:read` | Truy vấn nhật ký kiểm toán hệ thống |

### 4.3 Giao diện Truyền thông điệp (Kafka & Schema Registry Interfaces)
* **Topic 1: `admin.auth.audit.events`** — key `String` = `actorId` (ordering per-actor), 6 partitions, retention 30 ngày.
* **Topic 2: `admin.auth.notification.events`** — key `String` = `recipientId` (ordering per-recipient), 6 partitions, retention 14 ngày.
* Value Serializer (cả 2 topic): `io.confluent.kafka.serializers.KafkaAvroSerializer` qua Schema Registry (host `8082`), subject `<topic>-value`, compatibility `BACKWARD`.
* Avro Schema: `com.example.adminauth.event.AuditEventV1` / `NotificationEventV1` (generate từ `src/main/avro/*.avsc`).

### 4.4 Giao diện CSDL & Cache (Storage Interfaces)
* **Cơ sở dữ liệu PostgreSQL:**
  * Bảng thực thể chính: `admins`, `roles`, `permissions`, `admin_roles`, `admin_permissions`, `role_permissions`, `backup_codes`, `refresh_tokens`, `audit_events`, `outbox_events`.
* **Cấu trúc lưu trữ Redis:**
  * Khóa Session: `session:{sessionId}` $\rightarrow$ JSON lưu `adminId`, `username`, `ip`, `userAgent`, `createdAt`, `lastUsedAt`, `expiresAt` (TTL/idle 30 phút).
  * Bộ đếm Session: `admin_sessions:{adminId}` $\rightarrow$ Set chứa danh sách `sessionId` đang active của admin (giới hạn 5 session, evict cũ nhất).
  * Khóa Onboarding: `onboarding:{adminId}` $\rightarrow$ giá trị = raw token (TTL 15 phút).
  * Khóa Password Reset: `pwd_reset:{adminId}` (TTL 10 phút). *Chưa có: khóa MFA challenge (GAP-01); Rate limiting chưa triển khai (GAP-12).*

---

## 5. YÊU CẦU PHI CHỨC NĂNG (NON-FUNCTIONAL REQUIREMENTS - NFR)

### 5.1 An toàn thông tin & Bảo mật (Security)
* **NFR-SEC-01 (Mật khẩu):** Mật khẩu người dùng được băm bằng thuật toán **BCrypt** với hệ số work factor tối thiểu là 10 (khuyến nghị 12). Tuyệt đối không lưu mật khẩu dạng bản rõ (plaintext).
* **NFR-SEC-02 (Ký số Token):** Access Token được ký bằng **RS256** với cặp khóa RSA 2048 bits; Public Key công bố qua JWKS. *Hiện trạng (GAP-07): key pair hiện sinh **in-memory mỗi lần khởi động** — chưa persist qua Vault/KMS như thiết kế; mọi token cũ vô hiệu khi restart. Persist key + hỗ trợ rotate (nhiều `kid`) là điều kiện bắt buộc trước go-live.*
* **NFR-SEC-03 (Bảo vệ Refresh Token):** Giá trị chuỗi Refresh Token lưu trong CSDL phải được băm bằng thuật toán **SHA-256**.
* **NFR-SEC-04 (Bảo mật TOTP):** *Hiện trạng (GAP-13):* `totpSecretKey` hiện lưu **plaintext** trong bảng `mfa_totp_secrets`. Yêu cầu đích: mã hóa tại nghỉ (AES-GCM, key từ Vault) trước khi go-live. Backup codes hiện băm SHA-256 (GAP-14 khuyến nghị nâng HMAC/BCrypt).
* **NFR-SEC-05 (Chống tấn công Brute-Force):** Tối đa 5 lần đăng nhập sai liên tiếp → khóa tài khoản 30 phút (cấu hình được); *chưa có rate-limit per-IP/toàn cục (GAP-12).*
* **NFR-SEC-06 (Phòng vệ Token Hijacking):** Kết hợp kiểm tra trạng thái phiên thời gian thực trên Redis để có thể vô hiệu hóa phiên ngay lập tức khi phát hiện rò rỉ.

### 5.2 Hiệu năng & Khả năng đáp ứng (Performance & Latency)
* **NFR-PERF-01:** Thời gian xử lý thẩm định Access Token tại các Resource Service downstream (thông qua JWKS cache nội bộ) phải đạt độ trễ $\le 5\text{ms}$.
* **NFR-PERF-02:** Thời gian phản hồi API đăng nhập (`/v1/auth/login`) hoặc xác thực MFA (`/v1/auth/mfa/verify`): **P95 ≤ 500ms** ở điều kiện tải bình thường (theo PLAN.md mục 9; chi phí chính là băm BCrypt).
* **NFR-PERF-03:** Thông lượng relay Outbox tối thiểu **200 events/giây** với cấu hình mặc định (poll 500ms × batch 100 — đủ cho khối lượng admin portal); khi cần tăng: giảm interval / tăng batch.

### 5.3 Độ tin cậy & Tính sẵn sàng (Reliability & Availability)
* **NFR-REL-01 (Tính nguyên tử trong sự kiện):** Áp dụng Transactional Outbox Pattern đảm bảo 100% không mất dữ liệu sự kiện ngay cả khi Kafka Broker gặp sự cố sập tạm thời.
* **NFR-REL-02 (Tính chất Idempotent):** Mọi sự kiện phát sinh trên Kafka đều có trường `eventId` duy nhất để các consumer phía sau xử lý chống trùng lặp.
* **NFR-REL-03 (Khả năng chịu lỗi DB Lock):** Truy vấn Outbox Poller sử dụng `SKIP LOCKED` cho phép chạy nhiều container song song mà không gây nghẽn hàng đợi (Deadlock).

### 5.4 Khả năng bảo trì & Mở rộng (Maintainability & Extensibility)
* **NFR-MAINT-01 (Quản lý Phiên bản CSDL):** 100% thay đổi cấu trúc bảng CSDL phải được quản lý thông qua các file kịch bản **Flyway Migration** có đánh số phiên bản (`V1`, `V2`, `V3`...).
* **NFR-MAINT-02 (Tương thích Schema Kafka):** Mọi phiên bản Avro Schema mới đăng ký lên Schema Registry phải tuân thủ chuẩn tương thích ngược (`BACKWARD compatibility mode`).

---

## 6. MA TRẬN TRUY VẾT YÊU CẦU (REQUIREMENTS TRACEABILITY MATRIX - RTM)

Bảng đối chiếu truy vết từ Yêu cầu Nghiệp vụ (BRD — **giữ nguyên mã và ý nghĩa gốc**) $\rightarrow$ Yêu cầu Kỹ thuật (SRS) $\rightarrow$ Kịch bản kiểm thử (`TEST_CASES.md` / `TEST_SCENARIOS.md`):

| Mã BRD | Yêu cầu Nghiệp vụ (nguyên nghĩa theo BRD) | Mã Yêu cầu SRS | Mô tả Chức năng Kỹ thuật | Kịch bản Test tương ứng | Trạng thái Nghiệm thu |
|:---:|:---|:---|:---|:---:|:---:|
| **D07** | Admin Portal: RBAC/SSO, maker-checker, audit, notification, monitoring | **FR-ADM-01/02/06**, **FR-JWKS-01**, **FR-AUD-01/02**, **FR-EVT-01/02** | Quản lý Admin, Hybrid RBAC, Tier Guardrail, JWKS, Audit & Outbox | TEST_CASES nhóm A, S, J, AU; TEST_SCENARIOS 1–9 | RBAC/Session/Audit: ✅ · Kafka/Outbox **Producer**: ✅ · Consumer: ⬜ phase sau |
| **R11** | Mọi lệnh idempotent, có trạng thái, audit trail | **FR-AUD-01**, **FR-EVT-01/02** | `eventId` duy nhất cho consumer dedupe; outbox có trạng thái end-to-end | TEST_CASES AU, CONC; TEST_SCENARIOS 9 | ✅ (Outbox Producer; Consumer phase sau) |
| **R12 / BR-14** | Thay đổi quan trọng phải maker-checker + role + audit | **FR-ADM-01/06** | Hybrid Permission tách bậc `config:write` (maker) / `config:approve` (checker) theo từng người | TEST_CASES J-07, A-07; TEST_SCENARIOS 2, 7 | ✅ phần RBAC (checker ≠ maker do service nghiệp vụ enforce) |
| **BR-13** | Admin cấu hình tham số sản phẩm (không hard-code) | **FR-JWKS-01**, **FR-ADM-01** | Cấp grants `config:*` theo scope cho SERVICE_ADMIN thao tác tại system-params-api | TEST_SCENARIOS 2, 7 | ✅ |
| **BR-15 / BR-16 / BR-17** | Đối soát / Báo cáo / Monitoring & exception | Nền tảng downstream | Audit topic + `severity=SECURITY` là nguồn cho SIEM/dashboard (phase consumer) | — | ⬜ |
| **BR-18** | Audit trail & data retention | **FR-AUD-01/02**, **FR-EVT-03** | Append-only audit + outbox cleanup | TEST_CASES AU-04, SEC-07 | Audit DB: ✅ · Retention chờ chuẩn OCB (GAP-16) |
| **—** | Tự phục vụ khôi phục quyền truy cập | **FR-AUTH-05** | Quên mật khẩu tự phục vụ qua mã TOTP Google Authenticator | **Kịch bản 8**; TEST_CASES nhóm P | ✅ Đã triển khai |

---

> **Ghi chú:** Tài liệu phản ánh **hiện trạng mã nguồn** tại thời điểm viết; các mục đánh dấu **⬜ / GAP-x** là phần chưa triển khai, bám theo `docs/TEST_CASES.md` (mục 15) và `docs/KAFKA_PLAN.md`. Mọi thay đổi về sau phải được cập nhật phiên bản vào tài liệu này trước khi lập trình.
