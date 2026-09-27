# Admin Auth Service — OCB Auto-Earning

Service trung tâm **xác thực, phân quyền và quản lý phiên đăng nhập** cho hệ sinh thái **OCB Auto-Earning Admin Portal**.  
Xây dựng bằng **Java 21**, **Spring Boot 3**, **PostgreSQL**, **Redis** và ký JWT bằng **RS256** (asymmetric key).

---

## Mục lục

1. [Kiến trúc hệ thống](#1-kiến-trúc-hệ-thống)
2. [Hướng dẫn khởi chạy](#2-hướng-dẫn-khởi-chạy)
3. [Tài khoản kiểm thử mặc định](#3-tài-khoản-kiểm-thử-mặc-định)
4. [Luồng hoạt động của các API](#4-luồng-hoạt-động-của-các-api)
5. [Danh sách API (API Specifications)](#5-danh-sách-api-api-specifications)
6. [Chuẩn hóa Response & Mã lỗi](#6-chuẩn-hóa-response--mã-lỗi)
7. [Thiết kế cơ sở dữ liệu](#7-thiết-kế-cơ-sở-dữ-liệu)
8. [Bảo mật & Phân quyền](#8-bảo-mật--phân-quyền)
9. [Chạy Tests](#9-chạy-tests)
10. [Tech Stack](#10-tech-stack)

---

## 1. Kiến trúc hệ thống

### 1.1. Vai trò trong hệ sinh thái

```
┌──────────────────────────────────────────────────────────────┐
│                    Admin Portal (Frontend)                   │
└──────────────────┬──────────────────────┬────────────────────┘
                   │                      │
          Login/MFA/Session        API nghiệp vụ + JWT
                   │                      │
    ┌──────────────▼──────────┐    ┌──────▼──────────────────┐
    │   admin-auth-service    │    │   system-params-api     │
    │   (IAM chủ chốt)       │    │   ae-engine, reports... │
    │                         │    │                         │
    │  • Xác thực (JWT RS256) │    │  Verify JWT bằng JWKS   │
    │  • Hybrid RBAC/PBAC    │    │  Check permission+scope  │
    │  • Session (Redis)      │    │  Enforce maker-checker   │
    │  • MFA (TOTP)           │    │                         │
    │  • Audit log            │    │                         │
    └────────┬───────┬────────┘    └─────────────────────────┘
             │       │
     ┌───────▼┐  ┌───▼──────┐
     │PostgreSQL│  │  Redis   │
     │ (5433)  │  │  (6380)  │
     └─────────┘  └──────────┘
```

- **IAM chủ chốt**: Cấp JWT cho toàn bộ service. Các microservice khác verify token bằng endpoint `/.well-known/jwks.json`.
- **Tách auth khỏi service nghiệp vụ**: Service nghiệp vụ chỉ verify JWT + kiểm tra permission/scope, không quản lý password hay session.

### 1.2. Kiến trúc phân tầng (Layered Architecture)

Dự án được tổ chức theo kiến trúc **4 tầng** chuẩn mực, áp dụng **DTO Pattern** (không expose Entity ra ngoài Controller):

| Tầng | Trách nhiệm | Package |
|------|-------------|---------|
| **Controller** | Tiếp nhận HTTP Request, `@Valid`, `@PreAuthorize`, trả `ApiResponse<T>` | `controller/` |
| **Service** | Business Logic, security guardrails, session management | `service/` (interface) + `service/impl/` |
| **Mapper** | Chuyển đổi Entity ↔ DTO | `mapper/` |
| **Repository** | Spring Data JPA thao tác PostgreSQL | `repository/` |
| **Entity** | Ánh xạ bảng cơ sở dữ liệu | `entity/` |

```
Controller ──▶ Service Interface ──▶ Service Impl ──▶ Repository
                                          │
                                      Mapper (Entity ↔ DTO)
```

---

## 2. Hướng dẫn khởi chạy

### Yêu cầu môi trường
- **JDK 21**
- **Maven 3.9+**
- **Docker Desktop**

### Bước 1: Khởi động Database & Cache

Mở terminal tại thư mục dự án và chạy:
```powershell
docker-compose up -d
```
> Lệnh này sẽ khởi động **PostgreSQL** (port `5433`) và **Redis** (port `6380`).

### Bước 2: Chạy ứng dụng Spring Boot

```powershell
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Hoặc chạy trực tiếp class `AdminAuthServiceApplication.java` trong IntelliJ IDEA.

Ứng dụng sẽ chạy tại cổng **`8081`** với context-path là **`/api`**.

### Bước 3: Truy cập Swagger UI

👉 **URL:** [http://localhost:8081/api/swagger-ui/index.html](http://localhost:8081/api/swagger-ui/index.html)

> Sau khi gọi API Login, lấy chuỗi `accessToken` bấm vào nút **Authorize** ở góc trên phải để test các API yêu cầu quyền.

---

## 3. Tài khoản kiểm thử mặc định

Flyway đã nạp sẵn **4 tài khoản** để phục vụ kiểm thử:

| Tài khoản | Username | Password | Role | Tier | Mô tả |
|---|---|---|---|:---:|---|
| **Super Admin** | `superadmin` | `SuperAdmin@123456` | `SUPERADMIN` | 1 | Quản trị tối cao, toàn quyền |
| **Ops Admin** | `ops_admin` | `OpsAdmin@123456` | `OPERATIONS_ADMIN` | 2 | Quản trị viên ủy quyền |
| **Service Maker** | `svc_maker` | `Maker@123456` | `SERVICE_ADMIN` | 3 | Maker trên scope `system-params-api` |
| **Service Checker** | `svc_checker` | `Checker@123456` | `SERVICE_ADMIN` | 3 | Checker trên scope `system-params-api` |

---

## 4. Luồng hoạt động của các API

### 4.1. Luồng đăng nhập (Login — 2 bước với MFA)

```
┌──────┐        ┌──────────────────┐        ┌──────┐        ┌──────┐
│Client│        │ AuthController   │        │  DB  │        │Redis │
└──┬───┘        └────────┬─────────┘        └──┬───┘        └──┬───┘
   │  POST /v1/auth/login│                     │               │
   │  {username, password}                     │               │
   │─────────────────────▶│                     │               │
   │                      │  Kiểm tra username │               │
   │                      │───────────────────▶│               │
   │                      │  Admin + roles +   │               │
   │                      │  permissions       │               │
   │                      │◀───────────────────│               │
   │                      │                     │               │
   │                      │  Verify BCrypt password             │
   │                      │  Kiểm tra status (ACTIVE?)          │
   │                      │  Kiểm tra lockout (< 5 lần sai?)   │
   │                      │                     │               │
   │     ┌────────────────┤                     │               │
   │     │ MFA đã bật?    │                     │               │
   │     └───┬────────────┘                     │               │
   │         │                                  │               │
   │    [CÓ MFA]                                │               │
   │         │  Trả về {mfaRequired: true,      │               │
   │◀────────│          mfaToken: "abc..."}     │               │
   │         │                                  │               │
   │  POST /v1/auth/mfa/verify                  │               │
   │  {mfaToken, totpCode}                      │               │
   │─────────────────────▶│                     │               │
   │                      │  Verify TOTP code   │               │
   │                      │  Tạo session (sid)  │               │
   │                      │  ─────────────────────────────────▶│
   │                      │  Lưu session vào Redis             │
   │                      │  Ký JWT RS256 (nhúng permissions)  │
   │                      │  Lưu refresh_token hash vào DB     │
   │◀─────────────────────│                     │               │
   │  {accessToken, refreshToken,               │               │
   │   roles, permissions, expiresIn}           │               │
   │                                            │               │
   │    [KHÔNG MFA]                             │               │
   │         │  Tạo session → Redis             │               │
   │         │  Ký JWT → trả token ngay         │               │
   │◀────────│                                  │               │
```

**Tóm tắt:**
1. Client gửi `username` + `password` → API kiểm tra thông tin, trạng thái tài khoản, số lần đăng nhập sai.
2. Nếu **MFA đã bật**: trả `mfaRequired=true` kèm `mfaToken` (TTL 5 phút) → Client gửi tiếp mã TOTP/backup code qua `/mfa/verify`.
3. Nếu **không có MFA**: cấp token ngay.
4. Mỗi lần đăng nhập thành công: tạo session trên Redis (`sid`), ký JWT RS256 nhúng `permissions` + `sid`.
5. **Lockout**: Sai 5 lần liên tiếp → khóa 30 phút.

---

### 4.2. Luồng Refresh Token (Rotation + Reuse Detection)

```
┌──────┐        ┌──────────────────┐        ┌──────┐        ┌──────┐
│Client│        │ AuthController   │        │  DB  │        │Redis │
└──┬───┘        └────────┬─────────┘        └──┬───┘        └──┬───┘
   │  POST /v1/auth/refresh                    │               │
   │  {refreshToken: "RT_old"}                 │               │
   │──────────────────────▶│                    │               │
   │                       │  Hash(RT_old) →   │               │
   │                       │  tìm trong DB     │               │
   │                       │──────────────────▶│               │
   │                       │                    │               │
   │        ┌──────────────┤                    │               │
   │        │ Token hợp lệ?│                    │               │
   │        └──┬───────────┘                    │               │
   │           │                                │               │
   │     [HỢP LỆ - chưa dùng]                  │               │
   │           │  Revoke RT_old                 │               │
   │           │  Tạo RT_new (cùng family_id)   │               │
   │           │  Ký Access Token mới           │               │
   │           │──────────────────────────────▶│               │
   │◀──────────│  {accessToken, refreshToken}   │               │
   │           │                                │               │
   │     [ĐÃ DÙNG RỒI → REUSE DETECTED!]       │               │
   │           │  ⚠️ Nghi ngờ token bị đánh cắp │               │
   │           │  Thu hồi TOÀN BỘ family        │               │
   │           │  Xóa session trên Redis ───────────────────── ▶│
   │◀──────────│  HTTP 401: Token reuse detected│               │
```

**Tóm tắt:**
1. Mỗi lần refresh → **rotate**: cấp `refreshToken` mới, vô hiệu token cũ.
2. Nếu token cũ đã bị vô hiệu mà bị gửi lại → **Reuse Detection**: thu hồi toàn bộ family → xóa session Redis → bắt buộc đăng nhập lại.
3. Refresh token hết hạn sau **24 giờ** (absolute lifetime).

---

### 4.3. Luồng tạo tài khoản Admin (Tier Guardrail + Hybrid Permission)

```
┌──────────┐      ┌──────────────────┐      ┌──────┐
│SUPERADMIN│      │ AdminController  │      │  DB  │
│hoặc OPS  │      │                  │      │      │
└────┬─────┘      └────────┬─────────┘      └──┬───┘
     │  POST /v1/admins              │          │
     │  {username, email, roleCode,  │          │
     │   customGrants: [...]}        │          │
     │──────────────────────▶│                  │
     │                       │                  │
     │     ┌─────────────────┤                  │
     │     │ @PreAuthorize   │                  │
     │     │ 'admin:create'  │                  │
     │     └────┬────────────┘                  │
     │          │                               │
     │     ┌────▼────────────────────┐          │
     │     │ Tier Guardrail Check:   │          │
     │     │ • SUPERADMIN → tạo mọi │          │
     │     │   tier kể cả OPS_ADMIN │          │
     │     │ • OPS_ADMIN → chỉ tạo  │          │
     │     │   tier 3+ (SERVICE,    │          │
     │     │   THIRD_PARTY, AUDITOR)│          │
     │     │ → Vi phạm? HTTP 403    │          │
     │     └────┬────────────────────┘          │
     │          │                               │
     │          │  1. Tạo Admin (status=PENDING_ACTIVATION)
     │          │  2. Gán role (admin_roles)     │
     │          │  3. Load preset permissions từ role
     │          │  4. Merge customGrants (thêm/bớt)
     │          │  5. Lưu admin_permissions      │
     │          │──────────────────────────────▶│
     │          │                               │
     │◀─────────│  AdminDetailDto               │
     │          │  (kèm roles + permissions)    │
```

**Tóm tắt:**
1. Người tạo (`SUPERADMIN` hoặc `OPERATIONS_ADMIN`) gọi `POST /v1/admins` với `roleCode` và tùy chọn `customGrants`.
2. **Tier Guardrail**: `OPERATIONS_ADMIN` không thể tạo SUPERADMIN/OPERATIONS_ADMIN — chỉ tier 3+ trở xuống.
3. Hệ thống **preload** bộ preset permission mặc định từ role, rồi merge `customGrants` (thêm/bớt quyền).
4. Tài khoản mới có trạng thái `PENDING_ACTIVATION` + `mustChangePassword=true` → lần đầu đăng nhập phải đổi mật khẩu.

---

### 4.4. Luồng đổi mật khẩu & Reset mật khẩu

```
┌──────┐        ┌──────────────────┐        ┌──────┐        ┌──────┐
│Admin │        │  MeController /  │        │  DB  │        │Redis │
│      │        │  AdminController │        │      │        │      │
└──┬───┘        └────────┬─────────┘        └──┬───┘        └──┬───┘
   │                      │                     │               │
   │ ═══ TỰ ĐỔI (POST /v1/me/password) ═══    │               │
   │  {oldPassword, newPassword}                │               │
   │──────────────────────▶│                     │               │
   │                       │  Verify oldPassword│               │
   │                       │  Check complexity  │               │
   │                       │  Check 5 password  │               │
   │                       │  history (không    │               │
   │                       │  trùng gần nhất)   │               │
   │                       │  Lưu hash mới      │               │
   │                       │──────────────────▶│               │
   │                       │  Thu hồi TẤT CẢ   │               │
   │                       │  session khác ──────────────────▶│
   │◀──────────────────────│  "Password changed"│               │
   │                       │                     │               │
   │ ═══ RESET BỞI QUẢN TRỊ (POST /v1/admins/{id}/reset-password) ═
   │                       │                     │               │
   │  SUPERADMIN/OPS gọi   │                     │               │
   │──────────────────────▶│                     │               │
   │                       │  Tier guardrail     │               │
   │                       │  Tạo password tạm  │               │
   │                       │  mustChangePassword │               │
   │                       │  = true             │               │
   │                       │──────────────────▶│               │
   │                       │  Thu hồi TẤT CẢ   │               │
   │                       │  session ───────────────────────▶│
   │◀──────────────────────│  {temporaryPassword}│              │
```

**Tóm tắt:**
- **Tự đổi** (`POST /v1/me/password`): Kiểm tra mật khẩu cũ + complexity + lịch sử 5 lần gần nhất → thu hồi tất cả session khác.
- **Reset bởi quản trị** (`POST /v1/admins/{id}/reset-password`): SUPERADMIN/OPS_ADMIN reset → cấp mật khẩu tạm → đánh dấu `mustChangePassword=true` → thu hồi toàn bộ session ngay lập tức.

---

### 4.5. Luồng gán/thay đổi quyền (Permission Snapshot & Session Revocation)

```
┌──────────┐      ┌──────────────────┐      ┌──────┐      ┌──────┐
│SUPERADMIN│      │ AdminController  │      │  DB  │      │Redis │
└────┬─────┘      └────────┬─────────┘      └──┬───┘      └──┬───┘
     │  PUT /v1/admins/{id}/roles              │             │
     │  {roleCodes, customGrants}              │             │
     │──────────────────────▶│                  │             │
     │                       │  Tier guardrail  │             │
     │                       │  Xóa role cũ     │             │
     │                       │  Gán role mới    │             │
     │                       │  Xóa grants cũ   │             │
     │                       │  Preload preset   │             │
     │                       │  Merge customGrants│            │
     │                       │  Lưu grants mới  │             │
     │                       │─────────────────▶│             │
     │                       │                  │             │
     │                       │  ⚠️ Permission snapshot thay đổi
     │                       │  → Token cũ CÒN QUYỀN CŨ      │
     │                       │  → Phải thu hồi toàn bộ session│
     │                       │  ──────────────────────────────▶│
     │                       │  Xóa tất cả session:{admin_id} │
     │                       │                  │             │
     │◀──────────────────────│  AdminDetailDto  │             │
     │                       │  (quyền đã cập nhật)           │
```

**Tóm tắt:**
- Permission nhúng trong JWT là **bản chụp (snapshot)** tại thời điểm đăng nhập.
- Khi thay đổi role/permission → **thu hồi toàn bộ session** trên Redis → admin đó phải đăng nhập lại để nhận token mới với permission cập nhật.
- Đây là cơ chế bảo mật quan trọng: đảm bảo quyền bị gỡ sẽ **có hiệu lực tức thì**.

---

### 4.6. Luồng quản lý phiên đăng nhập (Session Management)

```
┌──────────┐      ┌──────────────────┐      ┌──────┐
│  Admin   │      │ MeController /   │      │Redis │
│          │      │ AdminController  │      │      │
└────┬─────┘      └────────┬─────────┘      └──┬───┘
     │                      │                   │
     │ ═══ XEM SESSION CỦA MÌNH ═══            │
     │  GET /v1/me/sessions │                   │
     │──────────────────────▶│                   │
     │                       │  Lấy tất cả      │
     │                       │  session:*:{id}  │
     │                       │─────────────────▶│
     │◀──────────────────────│  [{sid, ip,       │
     │                       │    userAgent,     │
     │                       │    lastUsed}]     │
     │                      │                   │
     │ ═══ KICK 1 SESSION CỦA MÌNH ═══         │
     │  DELETE /v1/me/sessions/{sid}            │
     │──────────────────────▶│                   │
     │                       │  Xóa session:{sid}│
     │                       │─────────────────▶│
     │◀──────────────────────│  "Session revoked"│
     │                      │                   │
     │ ═══ QUẢN TRỊ XEM/KICK SESSION CỦA NGƯỜI KHÁC ═══
     │  GET    /v1/admins/{id}/sessions         │
     │  DELETE /v1/admins/{id}/sessions/{sid}    │
     │  DELETE /v1/admins/{id}/sessions          │
     │──────────────────────▶│                   │
     │                       │  Tier guardrail   │
     │                       │  (chỉ cấp dưới)  │
     │                       │─────────────────▶│
     │◀──────────────────────│  OK / Revoked     │
```

**Tóm tắt:**
- Mọi admin đều có thể xem và kick session **của chính mình** qua `/v1/me/sessions`.
- `SUPERADMIN` xem/kick session của **tất cả** admin.
- `OPERATIONS_ADMIN` xem/kick session của **chỉ admin cấp dưới** (tier 3+).
- Giới hạn tối đa **5 session đồng thời** / admin (cấu hình được).

---

### 4.7. Luồng bật MFA (TOTP)

```
┌──────┐        ┌──────────────────┐        ┌──────┐
│Admin │        │  MeController    │        │  DB  │
└──┬───┘        └────────┬─────────┘        └──┬───┘
   │                      │                     │
   │ Bước 1: Khởi tạo TOTP                     │
   │  POST /v1/me/mfa/totp/setup               │
   │──────────────────────▶│                     │
   │                       │  Tạo TOTP secret   │
   │                       │  Lưu vào DB (chưa confirm)
   │                       │──────────────────▶│
   │◀──────────────────────│                     │
   │  {secretKey,           │                    │
   │   qrCodeUri}           │                    │
   │                       │                     │
   │ → Admin quét QR bằng Google Authenticator   │
   │                       │                     │
   │ Bước 2: Xác nhận kích hoạt                 │
   │  POST /v1/me/mfa/totp/enable               │
   │  {code: "123456"}     │                     │
   │──────────────────────▶│                     │
   │                       │  Verify TOTP code   │
   │                       │  confirmed = true   │
   │                       │  Tạo 10 backup codes│
   │                       │──────────────────▶│
   │◀──────────────────────│                     │
   │  {backupCodes: [...]}  │                    │
   │  ⚠️ Lưu lại backup codes, chỉ hiển thị 1 lần!
```

---

### 4.8. Luồng xác minh token tại Microservice con (JWKS)

```
┌──────┐   ┌───────────────────┐   ┌─────────────────────┐
│Client│   │ system-params-api │   │ admin-auth-service  │
└──┬───┘   └────────┬──────────┘   └──────────┬──────────┘
   │  API request    │                         │
   │  Authorization: │                         │
   │  Bearer <JWT>   │                         │
   │────────────────▶│                         │
   │                 │  Lần đầu: Lấy public key│
   │                 │  GET /.well-known/jwks.json
   │                 │────────────────────────▶│
   │                 │  RSA Public Key (cache)  │
   │                 │◀────────────────────────│
   │                 │                         │
   │                 │  Verify JWT signature    │
   │                 │  (stateless, không gọi  │
   │                 │   auth service nữa)     │
   │                 │                         │
   │                 │  @authz.hasPerm(         │
   │                 │    'config:write',       │
   │                 │    'system-params-api')  │
   │                 │                         │
   │                 │  Kiểm tra mảng permissions│
   │                 │  trong JWT claims        │
   │                 │  → {perm, scope} match?  │
   │◀────────────────│                         │
   │  Response       │                         │
```

**Tóm tắt:**
- Microservice con tải RSA public key từ `/.well-known/jwks.json` **(chỉ 1 lần, cache lại)**.
- Verify JWT hoàn toàn **stateless** — không cần gọi auth service mỗi request.
- Dùng `@authz.hasPerm(permission, serviceId)` — Custom Security Evaluator kiểm tra mảng `permissions` trong JWT claims.
- `SUPERADMIN` có `{"perm": "*", "scope": ["*"]}` → bypass mọi check.

---

## 5. Danh sách API (API Specifications)

### 5.1. Nhóm API Xác thực (Authentication) — Public

| Method | Endpoint | Mô tả |
|:---:|---|---|
| `POST` | `/v1/auth/login` | Bước 1: Đăng nhập bằng username + password |
| `POST` | `/v1/auth/mfa/verify` | Bước 2: Xác thực MFA (TOTP hoặc backup code) |
| `POST` | `/v1/auth/refresh` | Rotate refresh token, cấp access token mới |
| `POST` | `/v1/auth/logout` | Đăng xuất, thu hồi session hiện tại |
| `GET`  | `/.well-known/jwks.json` | Public key cho các service verify JWT |

**Body Login (`POST /v1/auth/login`):**
```json
{
  "username": "superadmin",
  "password": "SuperAdmin@123456"
}
```

**Response — Không có MFA (200 OK):**
```json
{
  "success": true,
  "message": "Login evaluated",
  "data": {
    "mfaRequired": false,
    "accessToken": "eyJhbGciOiJSUzI1NiJ9...",
    "refreshToken": "dGhpcyBpcyBhIHJl...",
    "expiresIn": 1800,
    "adminId": "adm-superadmin-01",
    "username": "superadmin",
    "fullName": "Root Super Administrator",
    "mustChangePassword": false,
    "roles": ["SUPERADMIN"],
    "permissions": [{"perm": "*", "scope": ["*"]}]
  },
  "timestamp": "2026-09-22T09:00:00Z"
}
```

**Response — Có MFA (200 OK):**
```json
{
  "success": true,
  "message": "Login evaluated",
  "data": {
    "mfaRequired": true,
    "mfaToken": "eyJhbGciOiJSUzI1NiJ9..."
  },
  "timestamp": "2026-09-22T09:00:00Z"
}
```

**Body MFA Verify (`POST /v1/auth/mfa/verify`):**
```json
{
  "mfaToken": "eyJhbGciOiJSUzI1NiJ9...",
  "totpCode": "123456"
}
```

**Body Refresh (`POST /v1/auth/refresh`):**
```json
{
  "refreshToken": "dGhpcyBpcyBhIHJl..."
}
```

---

### 5.2. Nhóm API Tự phục vụ (Self-Service) — Mọi admin đã đăng nhập

*Yêu cầu Header:* `Authorization: Bearer <ACCESS_TOKEN>`

| Method | Endpoint | Mô tả |
|:---:|---|---|
| `GET` | `/v1/me` | Xem thông tin tài khoản + roles + permissions |
| `POST` | `/v1/me/password` | Đổi mật khẩu (cần mật khẩu cũ) |
| `POST` | `/v1/me/mfa/totp/setup` | Khởi tạo TOTP MFA (trả secret + QR URI) |
| `POST` | `/v1/me/mfa/totp/enable` | Xác nhận kích hoạt TOTP (trả 10 backup codes) |
| `GET` | `/v1/me/sessions` | Xem danh sách session đang hoạt động |
| `DELETE` | `/v1/me/sessions/{sid}` | Kick 1 session cụ thể |
| `DELETE` | `/v1/me/sessions` | Kick tất cả session |

**Body đổi mật khẩu (`POST /v1/me/password`):**
```json
{
  "oldPassword": "SuperAdmin@123456",
  "newPassword": "NewSecurePass@2026"
}
```

**Body kích hoạt TOTP (`POST /v1/me/mfa/totp/enable`):**
```json
{
  "code": "123456"
}
```

---

### 5.3. Nhóm API Quản lý Admin — Yêu cầu `admin:*` permissions

*Yêu cầu Header:* `Authorization: Bearer <ACCESS_TOKEN>`

| Method | Endpoint | Permission | Mô tả |
|:---:|---|:---:|---|
| `POST` | `/v1/admins` | `admin:create` | Tạo tài khoản admin mới |
| `GET` | `/v1/admins` | `admin:read` | Danh sách admin (phân trang) |
| `GET` | `/v1/admins/{id}` | `admin:read` | Chi tiết admin (roles + permissions) |
| `PATCH` | `/v1/admins/{id}` | `admin:update` | Cập nhật thông tin (email, fullName) |
| `POST` | `/v1/admins/{id}/disable` | `admin:disable` | Vô hiệu hóa + thu hồi toàn bộ session |
| `POST` | `/v1/admins/{id}/enable` | `admin:enable` | Kích hoạt lại tài khoản |
| `POST` | `/v1/admins/{id}/reset-password` | `admin:reset_password` | Reset mật khẩu (cấp mật khẩu tạm) |
| `PUT` | `/v1/admins/{id}/roles` | `admin:assign_role` | Gán/thay đổi roles + permissions |

**Body tạo admin (`POST /v1/admins`):**
```json
{
  "username": "new_maker",
  "email": "new_maker@ocb.com.vn",
  "fullName": "Nguyen Van A",
  "initialPassword": "InitialPass@123",
  "roleCode": "SERVICE_ADMIN",
  "customGrants": [
    { "permissionCode": "config:read", "scopes": ["system-params-api"] },
    { "permissionCode": "config:write", "scopes": ["system-params-api"] }
  ]
}
```

**Body gán role (`PUT /v1/admins/{id}/roles`):**
```json
{
  "roleCodes": ["SERVICE_ADMIN"],
  "customGrants": [
    { "permissionCode": "config:read", "scopes": ["system-params-api"] },
    { "permissionCode": "config:approve", "scopes": ["system-params-api"] }
  ]
}
```

**Response reset password:**
```json
{
  "success": true,
  "message": "Password reset successfully",
  "data": {
    "temporaryPassword": "TmpPass@abc123"
  },
  "timestamp": "2026-09-22T09:00:00Z"
}
```

---

### 5.4. Nhóm API Quản lý Session của Admin khác — Yêu cầu `session:*` permissions

| Method | Endpoint | Permission | Mô tả |
|:---:|---|:---:|---|
| `GET` | `/v1/admins/{id}/sessions` | `session:read_any` | Xem session đang hoạt động của admin |
| `DELETE` | `/v1/admins/{id}/sessions/{sid}` | `session:revoke_any` | Kick 1 session cụ thể |
| `DELETE` | `/v1/admins/{id}/sessions` | `session:revoke_any` | Kick tất cả session |

---

### 5.5. Nhóm API Catalog — Mọi admin đã đăng nhập

| Method | Endpoint | Mô tả |
|:---:|---|---|
| `GET` | `/v1/roles` | Danh sách roles và preset permissions mặc định |
| `GET` | `/v1/permissions` | Danh sách tất cả permissions trong hệ thống |

---

### 5.6. Nhóm API Audit — Yêu cầu `audit:read`

| Method | Endpoint | Mô tả |
|:---:|---|---|
| `GET` | `/v1/audit-events` | Truy vấn audit log (hỗ trợ filter) |

**Query Parameters:**

| Param | Type | Mô tả |
|---|---|---|
| `actorId` | String | Lọc theo ID admin thực hiện |
| `action` | String | Lọc theo loại hành động (VD: `LOGIN_SUCCESS`, `ACCOUNT_CREATED`) |
| `start` | ISO DateTime | Thời gian bắt đầu |
| `end` | ISO DateTime | Thời gian kết thúc |
| `page` | int | Số trang (mặc định 0) |
| `size` | int | Số bản ghi / trang (mặc định 50) |

**Ví dụ:**
```
GET /v1/audit-events?action=LOGIN_SUCCESS&start=2026-09-01T00:00:00&size=20
```

---

## 6. Chuẩn hóa Response & Mã lỗi

Mọi phản hồi từ hệ thống đều được chuẩn hóa qua `ApiResponse<T>`:

**Thành công (HTTP 200):**
```json
{
  "success": true,
  "message": "Success",
  "data": { ... },
  "timestamp": "2026-09-22T09:00:00Z"
}
```

**Xác thực sai (HTTP 401 Unauthorized):**
```json
{
  "success": false,
  "message": "Invalid username or password",
  "timestamp": "2026-09-22T09:00:00Z"
}
```

**Không có quyền (HTTP 403 Forbidden):**
```json
{
  "success": false,
  "message": "Access denied: insufficient permissions",
  "timestamp": "2026-09-22T09:00:00Z"
}
```

**Sai định dạng (HTTP 400 Bad Request):**
```json
{
  "success": false,
  "message": "Validation failed",
  "data": {
    "username": "Username cannot be blank",
    "email": "Invalid email format"
  },
  "timestamp": "2026-09-22T09:00:00Z"
}
```

**Vi phạm nghiệp vụ (HTTP 422 Unprocessable Entity):**
```json
{
  "success": false,
  "message": "Cannot disable your own account",
  "timestamp": "2026-09-22T09:00:00Z"
}
```

**Tài khoản bị khóa (HTTP 423 Locked):**
```json
{
  "success": false,
  "message": "Account is locked due to too many failed login attempts. Try again after 30 minutes",
  "timestamp": "2026-09-22T09:00:00Z"
}
```

---

## 7. Thiết kế cơ sở dữ liệu

### 7.1. Sơ đồ quan hệ (ERD)

```
┌──────────┐       ┌───────────┐       ┌──────────────┐
│  admins  │──┐    │   roles   │──┐    │ permissions  │
│          │  │    │           │  │    │              │
│ id (PK)  │  │    │ id (PK)   │  │    │ id (PK)      │
│ username │  │    │ code      │  │    │ code         │
│ email    │  │    │ name      │  │    │ name         │
│ password │  │    │ tier      │  │    │ category     │
│ status   │  │    └─────┬─────┘  │    └──────┬───────┘
└────┬─────┘  │          │        │           │
     │        │    ┌─────▼────────▼───┐       │
     │        ├───▶│ admin_roles      │       │
     │        │    │ admin_id (FK)    │       │
     │        │    │ role_id (FK)     │       │
     │        │    └──────────────────┘       │
     │        │                               │
     │        │    ┌──────────────────────┐    │
     │        ├───▶│ admin_permissions    │◀───┘
     │        │    │ admin_id (FK)        │
     │        │    │ permission_id (FK)   │
     │        │    │ scope (TEXT/JSON)    │
     │        │    │ granted_by          │
     │        │    └──────────────────────┘
     │        │         ▲ Nguồn sự thật Authorization
     │        │
     │        │    ┌──────────────────────┐
     │        ├───▶│ refresh_tokens       │
     │        │    │ token_hash           │
     │        │    │ family_id (rotation) │
     │        │    └──────────────────────┘
     │        │
     │        │    ┌──────────────────────┐
     │        ├───▶│ mfa_totp_secrets     │
     │        │    └──────────────────────┘
     │        │
     │        │    ┌──────────────────────┐
     │        ├───▶│ mfa_backup_codes     │
     │        │    └──────────────────────┘
     │        │
     │        │    ┌──────────────────────┐
     │        └───▶│ password_history     │
     │             └──────────────────────┘
     │
     │         ┌──────────────────────┐
     └────────▶│ audit_events         │
               │ actor_id, action,    │
               │ target_id, ip,      │
               │ before/after_state  │
               └──────────────────────┘

┌─────────────────────────┐
│ role_permissions        │
│ role_id ↔ permission_id │
│ (Preset mặc định)      │
└─────────────────────────┘
```

### 7.2. Danh sách bảng (11 bảng)

| Bảng | Nội dung chính |
|---|---|
| `admins` | Tài khoản admin: username, email, password_hash, status, lockout, must_change_password |
| `roles` | Catalog role: code, name, tier (1-5) |
| `permissions` | Catalog permission: code, name, category |
| `role_permissions` | Preset mặc định: role → danh sách permission (template khi gán role) |
| `admin_roles` | Admin ↔ Role (N:N) — role = tier + preset đã apply |
| `admin_permissions` | **Nguồn sự thật authorization**: admin ↔ permission + scope + granted_by |
| `refresh_tokens` | Refresh token: hash, family_id (rotation), session_id, replaced_by |
| `mfa_totp_secrets` | TOTP secret + confirmed status |
| `mfa_backup_codes` | Backup codes (hash), used_at |
| `password_history` | Lịch sử 5 mật khẩu gần nhất (chống trùng) |
| `audit_events` | Nhật ký kiểm toán: actor, action, target, IP, user_agent, before/after state |

### 7.3. Redis Keys

| Key Pattern | TTL | Nội dung |
|---|---|---|
| `session:{sid}` | 30 phút | Session data: admin_id, ip, user_agent, created_at, last_used |
| `login_attempts:{username}` | 30 phút | Đếm số lần login sai liên tiếp |

---

## 8. Bảo mật & Phân quyền

### 8.1. JWT RS256

| Thông số | Giá trị |
|---|---|
| Thuật toán ký | **RS256** (asymmetric — RSA 2048-bit) |
| Access token TTL | **30 phút** |
| Refresh token TTL | **24 giờ** (absolute), rotation mỗi lần refresh |
| Claims | `sub`, `roles`, `permissions` (nhúng grant + scope), `sid`, `mfa_verified`, `iat`, `exp` |
| Public key endpoint | `GET /.well-known/jwks.json` |

### 8.2. Mô hình phân quyền Hybrid (RBAC + PBAC)

```
                    Role = Tier + Preset
                    ┌─────────────────────────┐
                    │ SUPERADMIN (tier 1)      │──▶ Preset: ALL permissions
                    │ OPERATIONS_ADMIN (tier 2)│──▶ Preset: admin:*, session:*
                    │ SERVICE_ADMIN (tier 3)   │──▶ Preset: config:*, ops:*, recon:*
                    │ THIRD_PARTY_ADMIN (tier 4)│──▶ Preset: recon:read, report:read
                    │ AUDITOR (tier 5)         │──▶ Preset: audit:read, report:read
                    └──────────┬──────────────┘
                               │
                    Khi gán role, preset được preload
                    SUPERADMIN/OPS_ADMIN chỉnh thêm/bớt
                               │
                               ▼
                    admin_permissions (Nguồn sự thật)
                    ┌────────────────────────────────────┐
                    │ admin_id │ permission │ scope       │
                    │ adm-001  │ config:read│ sys-params  │
                    │ adm-001  │ config:write│ sys-params │
                    │ adm-002  │ config:read│ sys-params  │
                    │ adm-002  │ config:approve│ sys-params│
                    └────────────────────────────────────┘
                               │
                    Nhúng vào JWT access token
                               │
                               ▼
                    Service con dùng @authz.hasPerm()
                    để kiểm tra permission + scope
```

### 8.3. Tier Guardrail

| Người thao tác | Phạm vi quản lý |
|---|---|
| `SUPERADMIN` (tier 1) | Quản lý **tất cả** admin, kể cả OPERATIONS_ADMIN |
| `OPERATIONS_ADMIN` (tier 2) | Chỉ quản lý **tier 3+** (SERVICE_ADMIN, THIRD_PARTY, AUDITOR) |
| Không ai | Tự đổi role/permission **chính mình** |
| Không ai | OPERATIONS_ADMIN thao tác lên SUPERADMIN hoặc OPS_ADMIN khác |

### 8.4. Danh sách Permissions (21 quyền)

| ID | Code | Mô tả | Category |
|:---:|---|---|---|
| 1 | `admin:read` | Xem danh sách admin | ADMIN |
| 2 | `admin:create` | Tạo tài khoản admin | ADMIN |
| 3 | `admin:update` | Cập nhật thông tin admin | ADMIN |
| 4 | `admin:disable` | Vô hiệu hóa admin | ADMIN |
| 5 | `admin:enable` | Kích hoạt admin | ADMIN |
| 6 | `admin:reset_password` | Reset mật khẩu | ADMIN |
| 7 | `admin:assign_role` | Gán role cho admin | ADMIN |
| 8 | `admin:assign_perm` | Gán trực tiếp permission | ADMIN |
| 9 | `session:read_any` | Xem session người khác | SESSION |
| 10 | `session:revoke_any` | Kick session người khác | SESSION |
| 11 | `role:read` | Xem catalog role/permission | ROLE |
| 12 | `audit:read` | Xem audit logs | AUDIT |
| 13 | `ops:monitor:read` | Giám sát vận hành | OPS |
| 14 | `ops:exception:handle` | Xử lý lỗi vận hành | OPS |
| 15 | `recon:read` | Xem đối soát | RECON |
| 16 | `recon:case:manage` | Quản lý case đối soát | RECON |
| 17 | `report:read` | Xem báo cáo | REPORT |
| 18 | `config:read` | Xem cấu hình tham số | CONFIG |
| 19 | `config:write` | Tạo/sửa cấu hình (Maker) | CONFIG |
| 20 | `config:approve` | Phê duyệt cấu hình (Checker) | CONFIG |
| 21 | `auth:self` | Tự phục vụ (đổi MK, MFA, session) | AUTH |

---

## 9. Chạy Tests

Dự án đi kèm bộ **21 test cases** bao phủ toàn bộ luồng nghiệp vụ, security guardrails, token lifecycle, và session management:

```powershell
mvn test
```

### Bộ test bao gồm:

| Test Class | Nội dung |
|---|---|
| `AdminAuthIntegrationTest` | End-to-end: JWKS discovery → login → access → list admins → logout → reject |
| `JwtTokenProviderTest` | RS256 sign/verify, wildcard SUPERADMIN, session ID extraction |
| `CustomAuthEvaluatorTest` | SUPERADMIN bypass, scope check, Maker-Checker isNotCreator |
| `SessionRedisServiceTest` | Create/validate/revoke session, max concurrent sessions eviction |
| `AuthServiceTest` | Login success, lockout, reuse detection, change password |
| `AdminManagementServiceTest` | Tier guardrail, SUPERADMIN can create any, self-disable prevention, disable revokes sessions |

---

## 10. Tech Stack

| Thành phần | Công nghệ | Phiên bản |
|---|---|---|
| Runtime | Java | 21 |
| Framework | Spring Boot | 3.3.4 |
| Security | Spring Security | 6.x |
| ORM | Spring Data JPA + Hibernate | — |
| Database | PostgreSQL | 16-alpine |
| Migration | Flyway | — |
| Cache / Session | Redis (Lettuce) | 7-alpine |
| JWT | Nimbus JOSE + JWT (RS256) | 9.37.3 |
| MFA | GoogleAuth (TOTP) | 1.5.0 |
| API Docs | springdoc-openapi (Swagger UI) | 2.6.0 |
| Serialization | Jackson (JavaTimeModule) | — |
| Utility | Lombok | — |
| Test | JUnit 5, Mockito, H2 (in-memory), Spring Security Test | — |
| Container | Docker Compose | — |
