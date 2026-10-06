# Kịch bản Kiểm thử & Demo Bảo mật — AE Admin Auth Service

Tài liệu này cung cấp toàn bộ các **kịch bản kiểm thử thực tế (Manual Test Scenarios)** từng bước để demo các tính năng bảo mật, phân quyền và xác thực của **`ae-admin-auth-service`** thông qua **Swagger UI** hoặc **cURL / Postman**.

---

## Mục lục

1. [Chuẩn bị môi trường & Tài khoản](#1-chuẩn-bị-môi-trường--tài-khoản)
2. [Kịch bản 1: Xác thực 2 lớp (MFA / 2FA - TOTP & Backup Codes)](#2-kịch-bản-1-xác-thực-2-lớp-mfa--2fa---totp--backup-codes)
3. [Kịch bản 2: Hàng rào phân quyền (Tier Guardrail & Hybrid RBAC/PBAC)](#3-kịch-bản-2-hàng-rào-phân-quyền-tier-guardrail--hybrid-rbacpbac)
4. [Kịch bản 3: Quản lý phiên đăng nhập trên Redis (Live Session & Kick Session)](#4-kịch-bản-3-quản-lý-phiên-đăng-nhập-trên-redis-live-session--kick-session)
5. [Kịch bản 4: Xoay vòng Refresh Token & Phòng chống đánh cắp Token (Reuse Detection)](#5-kịch-bản-4-xoay-vòng-refresh-token--phòng-chống-đánh-cắp-token-reuse-detection)
6. [Kịch bản 5: Truy vết nhật ký kiểm toán (Audit Trail)](#6-kịch-bản-5-truy-vết-nhật-ký-kiểm-toán-audit-trail)
7. [Kịch bản 6: Quản trị viên Vận hành (OPERATIONS_ADMIN Lifecycle & Guardrail Test)](#7-kịch-bản-6-quản-trị-viên-vận-hành-operations_admin-lifecycle--guardrail-test)
8. [Kịch bản 7: Tinh chỉnh quyền cho OPERATIONS_ADMIN (Chỉ cấp quyền Kick Session)](#8-kịch-bản-7-tinh-chỉnh-quyền-cho-operations_admin-chỉ-cấp-quyền-kick-session)
9. [Kịch bản 8: Quên Mật Khẩu Tự Phục Vụ Qua TOTP (Self-Service Password Reset)](#9-kịch-bản-8-quên-mật-khẩu-tự-phục-vụ-qua-totp-self-service-password-reset)
10. [Kịch bản 9: Kiểm thử Event Bus qua Kafka & Transactional Outbox (Demo với Kafka UI)](#10-kịch-bản-9-kiểm-thử-event-bus-qua-kafka--transactional-outbox-demo-với-kafka-ui)
11. [Bảng tổng hợp Checklist kiểm thử](#11-bảng-tổng-hợp-checklist-kiểm-thử)

---

## 1. Chuẩn bị môi trường & Tài khoản

### 1.1. Khởi động dịch vụ
Mở terminal tại thư mục `ae-admin-auth-service`:
```powershell
# 1. Khởi động hạ tầng: PostgreSQL (5433), Redis (6380), Kafka (9092), Schema Registry (8082), Kafka UI (8090)
docker-compose up -d

# 2. Khởi chạy ứng dụng Spring Boot
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

* **Swagger UI:** 👉 [http://localhost:8081/api/swagger-ui/index.html](http://localhost:8081/api/swagger-ui/index.html)
* **Base URL API:** `http://localhost:8081/api`
* **Kafka UI (Kafbat):** 👉 [http://localhost:8090](http://localhost:8090)
* **Schema Registry:** `http://localhost:8082`

### 1.2. Danh sách tài khoản mẫu (Pre-seeded Accounts)

| Tài khoản | Username | Password | Vai trò | Tier | Quyền hạn mặc định |
|---|---|---|---|:---:|---|
| **Super Admin** | `superadmin` | `SuperAdmin@123456` | `SUPERADMIN` | 1 | Quản trị tối cao, toàn quyền hệ thống (`*`) |
| **Operations Admin** | `ops_admin` | `OpsAdmin@123456` | `OPERATIONS_ADMIN` | 2 | Quản lý tài khoản & phiên của cấp dưới (Tier 3+) |
| **Service Maker** | `svc_maker` | `Maker@123456` | `SERVICE_ADMIN` | 3 | Tạo/sửa cấu hình trên scope `system-params-api` |
| **Service Checker** | `svc_checker` | `Checker@123456` | `SERVICE_ADMIN` | 3 | Duyệt cấu hình trên scope `system-params-api` |

---

## 2. Kịch bản 1: Xác thực 2 lớp (MFA / 2FA - TOTP & Backup Codes)

```
[Bước 1] Login thường (Chưa bật 2FA) ──▶ Nhận Access Token ngay
    │
[Bước 2] Khởi tạo TOTP Setup ─────────▶ Nhận Secret Key & QR URI
    │
[Bước 3] Kích hoạt TOTP Enable ────────▶ Nhận 10 mã Backup Codes
    │
[Bước 4] Login sau khi có 2FA ─────────▶ Bước 1: Trả mfaToken ──▶ Bước 2: Verify OTP
    │
[Bước 5] Test bảo mật & ca lỗi ────────▶ Nhập sai mã OTP / Dùng mã Backup cứu hộ
```

### 🧪 Test 1.1: Đăng nhập khi CHƯA bật 2FA (Baseline)
* **Mục đích:** Xác nhận tài khoản mặc định chưa kích hoạt 2FA sẽ nhận Access Token ngay.
* **API:** `POST /v1/auth/login`
* **Body:**
  ```json
  {
    "username": "superadmin",
    "password": "SuperAdmin@123456"
  }
  ```
* **Kết quả mong đợi (200 OK):**
  * `mfaRequired`: `false`
  * Trả về ngay `accessToken` và `refreshToken`.
* **Thao tác:** Copy `accessToken` $\rightarrow$ Bấm nút **Authorize** ở góc trên phải Swagger UI $\rightarrow$ Dán vào ô: `Bearer <accessToken>` $\rightarrow$ Bấm **Authorize**.

---

### 🧪 Test 1.2: Khởi tạo thiết lập TOTP (MFA Setup)
* **API:** `POST /v1/me/mfa/totp/setup` *(Cần Bearer Token)*
* **Body:** Không cần body.
* **Kết quả mong đợi (200 OK):**
  ```json
  {
    "success": true,
    "message": "MFA setup initialized",
    "data": {
      "secretKey": "JBSWY3DPEHPK3PXP...",
      "qrCodeUri": "otpauth://totp/OCB-AutoEarning-Admin:superadmin?secret=JBSWY3DPEHPK3PXP...&issuer=OCB-AutoEarning-Admin"
    }
  }
  ```
* **Thao tác:** Mở app **Google Authenticator** trên điện thoại $\rightarrow$ Chọn dấu **(+)** $\rightarrow$ **"Nhập khóa thiết lập" (Enter setup key)** $\rightarrow$ Nhập Key là chuỗi `secretKey` ở trên $\rightarrow$ Bấm **Thêm**.

---

### 🧪 Test 1.3: Xác nhận & Kích hoạt 2FA (Enable TOTP)
* **API:** `POST /v1/me/mfa/totp/enable` *(Cần Bearer Token)*
* **Body:** Nhập mã 6 chữ số đang hiển thị trên app Google Authenticator:
  ```json
  {
    "code": "482910"
  }
  ```
* **Kết quả mong đợi (200 OK):**
  * Hệ thống kích hoạt 2FA thành công và cấp **10 mã dự phòng (Backup Codes)**:
  ```json
  {
    "success": true,
    "message": "MFA enabled successfully",
    "data": {
      "backupCodes": [
        "a1b2c3d4", "e5f6g7h8", "i9j0k1l2", "m3n4o5p6", "q7r8s9t0",
        "u1v2w3x4", "y5z6a7b8", "c9d0e1f2", "g3h4i5j6", "k7l8m9n0"
      ]
    }
  }
  ```
* **Thao tác:** Lưu lại 1-2 mã backup code ra Notepad (ví dụ: `a1b2c3d4`) để kiểm tra ở bước sau.

---

### 🧪 Test 1.4: Đăng nhập sau khi ĐÃ BẬT 2FA (Luồng 2 bước)

#### 👉 Bước A: Gửi Username + Password
* **API:** `POST /v1/auth/login`
* **Body:**
  ```json
  {
    "username": "superadmin",
    "password": "SuperAdmin@123456"
  }
  ```
* **Kết quả mong đợi (200 OK):**
  ```json
  {
    "success": true,
    "message": "Login evaluated",
    "data": {
      "mfaRequired": true,
      "mfaToken": "eyJhbGciOiJSUzI1NiJ9..."
    }
  }
  ```
  *(Hệ thống chặn cấp Access Token, chỉ cấp `mfaToken` có hiệu lực 5 phút).*

#### 👉 Bước B: Xác thực mã 2FA
* **API:** `POST /v1/auth/mfa/verify`
* **Body:**
  ```json
  {
    "mfaToken": "<mfaToken_ở_bước_A>",
    "totpCode": "739102"
  }
  ```
* **Kết quả mong đợi (200 OK):**
  * Xác thực thành công $\rightarrow$ Cấp `accessToken` (có claim `mfa_verified: true`) và `refreshToken`.

---

### 🧪 Test 1.5: Nhập sai mã OTP (Ca lỗi)
* Lấy `mfaToken` mới qua `login`.
* Gọi `POST /v1/auth/mfa/verify` với mã sai: `{"mfaToken": "...", "totpCode": "000000"}`.
* **Kết quả mong đợi:** HTTP **401 Unauthorized** (`"Invalid MFA verification code"`).

---

### 🧪 Test 1.6: Cứu hộ khi mất điện thoại bằng Backup Code
* Lấy `mfaToken` mới qua `login`.
* Gọi `POST /v1/auth/mfa/verify` bằng mã dự phòng (để trống `totpCode`):
  ```json
  {
    "mfaToken": "...",
    "backupCode": "a1b2c3d4"
  }
  ```
* **Kết quả mong đợi:** HTTP **200 OK** $\rightarrow$ Đăng nhập thành công!

---

### 🧪 Test 1.7: Chống dùng lại mã Backup Code (One-time Use)
* Lấy `mfaToken` mới qua `login`.
* Gửi lại chính mã `a1b2c3d4` đã dùng ở Test 1.6.
* **Kết quả mong đợi:** HTTP **401 Unauthorized** $\rightarrow$ Hệ thống từ chối vì mã đã bị đánh dấu sử dụng (`used_at`).

---

## 3. Kịch bản 2: Hàng rào phân quyền (Tier Guardrail & Hybrid RBAC/PBAC)

> **Mục tiêu:** Kiểm tra các quy tắc:
> 1. Cấp dưới không được thao tác lên cấp trên hoặc ngang hàng.
> 2. Không ai được tự vô hiệu hóa tài khoản của chính mình.
> 3. Cấp quyền Hybrid chính xác theo `customGrants` (không bị nạp thừa preset).

### 🧪 Test 2.1: `ops_admin` (Tier 2) thử tạo tài khoản `SUPERADMIN` (Tier 1)
1. Đăng nhập tài khoản `ops_admin` (`OpsAdmin@123456`) $\rightarrow$ Gán token vào Swagger.
2. Thử gọi API tạo tài khoản `POST /v1/admins`:
   ```json
   {
     "username": "fake_superadmin",
     "email": "fake_super@ocb.com.vn",
     "roleCode": "SUPERADMIN"
   }
   ```
* **Kết quả mong đợi:** HTTP **403 Forbidden**  
  `{"success": false, "message": "Access denied: Cannot assign role with higher or equal tier"}`

---

### 🧪 Test 2.2: `ops_admin` tự vô hiệu hóa chính mình
Vẫn dùng token của `ops_admin`:
* **API:** `POST /v1/admins/adm-opsadmin-01/disable`
* **Kết quả mong đợi:** HTTP **422 Unprocessable Entity**  
  `{"success": false, "message": "Cannot disable your own account"}`

---

### 🧪 Test 2.3: Tạo Maker với phân quyền Hybrid (Chỉ định quyền rõ ràng)
Dùng `ops_admin` tạo tài khoản `demo_maker` với quyền Maker giới hạn trên `system-params-api`:
* **API:** `POST /v1/admins`
* **Body:**
  ```json
  {
    "username": "demo_maker",
    "email": "demo_maker@ocb.com.vn",
    "fullName": "Nguyen Van Demo",
    "initialPassword": "DemoPassword@123",
    "roleCode": "SERVICE_ADMIN",
    "customGrants": [
      {
        "permissionCode": "config:read",
        "scopes": ["system-params-api"]
      },
      {
        "permissionCode": "config:write",
        "scopes": ["system-params-api"]
      }
    ]
  }
  ```
* **Kết quả mong đợi (200 OK):**
  * Tài khoản được tạo thành công với trạng thái `PENDING_ACTIVATION`.
  * **Mảng `permissions` trả về chỉ có đúng 3 quyền:**
    1. `config:read` với scope `["system-params-api"]`
    2. `config:write` với scope `["system-params-api"]`
    3. `auth:self` với scope `["*"]` (tự động kèm theo để admin quản lý tài khoản)
  * Tuyệt đối **KHÔNG CÒN** bị nạp thừa các quyền `config:approve`, `ops:*`, `recon:*` từ Preset.
  * Lưu lại trường `id` của admin mới tạo để sử dụng cho các bài test tiếp theo.

---

## 4. Kịch bản 3: Quản lý phiên đăng nhập trên Redis (Live Session & Kick Session)

> **Mục tiêu:** Chứng minh vai trò của Redis Session Store. Khi một session bị kick, dù JWT trên máy client còn hạn 30 phút nhưng gọi API sẽ bị từ chối ngay lập tức.

### 🧪 Test 3.1: Mở 2 phiên đăng nhập đồng thời
1. Đăng nhập tài khoản `svc_maker` (`Maker@123456`) lần 1 $\rightarrow$ Lưu Access Token thành **Token_A** (Phiên 1).
2. Đăng nhập tài khoản `svc_maker` lần 2 (mở tab ẩn danh khác) $\rightarrow$ Lưu Access Token thành **Token_B** (Phiên 2).

---

### 🧪 Test 3.2: Xem danh sách các phiên đang hoạt động
Dùng **Token_A** gọi:
* **API:** `GET /v1/me/sessions`
* **Kết quả mong đợi (200 OK):**
  * Trả về danh sách gồm 2 phiên (`AdminSessionDto`), mỗi phiên có `sessionId` riêng (ví dụ `sid_1` và `sid_2`), IP truy cập và thời gian `lastUsed`.

---

### 🧪 Test 3.3: Kick một phiên từ xa
Dùng **Token_B** gọi API để kick phiên của **Token_A**:
* **API:** `DELETE /v1/me/sessions/{sid_1}`
* **Kết quả mong đợi:** HTTP **200 OK** (`"Session revoked successfully"`).

---

### 🧪 Test 3.4: Bằng chứng bảo mật (Kiểm tra Token bị kick)
1. Lấy **Token_A** (phiên vừa bị kick) dán vào Swagger Authorize.
2. Thử gọi bất kỳ API nào, ví dụ: `GET /v1/me`.
* **Kết quả mong đợi:** HTTP **401 Unauthorized**  
  `{"success": false, "message": "Session has expired or was revoked"}`  
  *(Dù chữ ký JWT RS256 còn hạn sử dụng, nhưng do `sid_1` đã bị xóa trên Redis, request bị chặn đứng ngay lập tức!)*

---

## 5. Kịch bản 4: Xoay vòng Refresh Token & Phòng chống đánh cắp Token (Reuse Detection)

> **Mục tiêu:** Chứng minh cơ chế Rotation tự động cấp mới Refresh Token và phát hiện kẻ tấn công sử dụng lại token cũ để thu hồi toàn bộ chuỗi phiên (Token Family).

```
Người dùng hợp lệ:
[RT_Goc] ──POST /refresh──▶ Cấp [RT_Moi] + [Access Token 2]
                                (RT_Goc bị vô hiệu hóa)

Kẻ tấn công (Replay Attack):
[RT_Goc] ──POST /refresh──▶ ⚠️ Phát hiện REUSE DETECTED!
                            ──▶ Thu hồi toàn bộ Family!
                            ──▶ Xóa phiên [RT_Moi] trên Redis!
```

### 🧪 Test 4.1: Đăng nhập lấy Refresh Token ban đầu
* Gọi `POST /v1/auth/login` với `svc_checker` / `Checker@123456`.
* Lưu lại:
  * `refreshToken` $\rightarrow$ gọi là **RT_Goc**.

---

### 🧪 Test 4.2: Refresh Token lần đầu (Người dùng hợp lệ)
* **API:** `POST /v1/auth/refresh`
* **Body:**
  ```json
  {
    "refreshToken": "<RT_Goc>"
  }
  ```
* **Kết quả mong đợi (200 OK):**
  * Hệ thống trả về `accessToken` mới và **`refreshToken` mới** (gọi là **RT_Moi**).
  * Trong cơ sở dữ liệu, **RT_Goc** đã được đánh dấu là `revoked` và liên kết với `replaced_by`.

---

### 🧪 Test 4.3: Tái sử dụng Refresh Token cũ (Giả lập Hacker)
Giả sử Hacker đánh cắp được chuỗi **RT_Goc** trước đó và gửi yêu cầu refresh:
* **API:** `POST /v1/auth/refresh`
* **Body:** Gửi lại chính **RT_Goc**:
  ```json
  {
    "refreshToken": "<RT_Goc>"
  }
  ```
* **Kết quả mong đợi:** HTTP **401 Unauthorized**  
  `{"success": false, "message": "Refresh token reuse detected. All sessions terminated."}`
* **Hiệu ứng bảo mật tức thì:**
  * Hệ thống nhận diện hành vi tấn công Replay Attack.
  * Toàn bộ chuỗi token (Token Family) bị thu hồi vĩnh viễn.
  * Key session tương ứng của **RT_Moi trên Redis bị xóa ngay lập tức** $\rightarrow$ Người dùng hợp lệ sẽ được yêu cầu đăng nhập lại để đảm bảo an toàn.

---

## 6. Kịch bản 5: Truy vết nhật ký kiểm toán (Audit Trail)

> **Mục tiêu:** Kiểm tra xem mọi hành động quan trọng (đăng nhập, MFA, tạo tài khoản, kick phiên, phát hiện tấn công) đều được ghi nhận đầy đủ vào bảng `audit_events`.

### 🧪 Test 5.1: Truy vấn danh sách Audit Events (Phân trang Pageable & Bộ lọc)
1. Đăng nhập bằng tài khoản `superadmin` (hoặc tài khoản có quyền `audit:read`) $\rightarrow$ Gán token vào Swagger.
2. Tìm đến endpoint: **`GET /v1/audit-events`** $\rightarrow$ Bấm **Try it out**.

#### 📋 Các tham số truyền vào (Query Parameters):

| Nhóm | Tên tham số | Kiểu | Mẫu giá trị | Mô tả |
|---|---|:---:|---|---|
| **Phân trang (Pageable)** | **`page`** | int | `0` | Số thứ tự trang (bắt đầu từ 0) |
| | **`size`** | int | `10` | Số bản ghi trên mỗi trang (mặc định 50 nếu không điền) |
| | **`sort`** | string | `createdAt,desc` | Sắp xếp theo trường và chiều (`asc` hoặc `desc`) |
| **Bộ lọc (Filters - Tùy chọn)** | **`actorId`** | string | `ops_admin` | Lọc theo username người thực hiện (để trống nếu xem tất cả) |
| | **`action`** | string | `ACCOUNT_CREATED` | Lọc theo hành động (VD: `LOGIN_SUCCESS`, `SESSION_REVOKED`, `TOKEN_REUSE_DETECTED`...) |
| | **`start`** | date-time | `2026-09-01T00:00:00` | Lọc sự kiện từ thời điểm bắt đầu (định dạng ISO) |
| | **`end`** | date-time | `2026-09-30T23:59:59` | Lọc sự kiện đến thời điểm kết thúc |

#### 🌐 Ví dụ URL gọi trực tiếp (cURL / Browser):
* **Xem trang đầu tiên, 10 bản ghi mới nhất:**
  ```http
  GET /api/v1/audit-events?page=0&size=10&sort=createdAt,desc
  ```
* **Lọc các hành động tạo tài khoản của `ops_admin` có phân trang:**
  ```http
  GET /api/v1/audit-events?actorId=ops_admin&action=ACCOUNT_CREATED&page=0&size=10&sort=createdAt,desc
  ```

#### 📦 Kết quả mong đợi (200 OK):
Trả về đối tượng `Page` chuẩn của Spring Data với đầy đủ thông tin dữ liệu (`content`) và metadata phân trang (`pageable`, `totalElements`, `totalPages`):

```json
{
  "success": true,
  "message": "Success",
  "data": {
    "content": [
      {
        "id": 15,
        "actorId": "ops_admin",
        "action": "ACCOUNT_CREATED",
        "targetId": "6b921bab-9e6d-4417-aa7c-56d8d921fddb",
        "beforeState": null,
        "afterState": "{\"role\":\"SERVICE_ADMIN\",\"username\":\"demo_maker\"}",
        "ipAddress": "0:0:0:0:0:0:0:1",
        "userAgent": "Mozilla/5.0 ...",
        "correlationId": "corr-4fa9b2-...",
        "createdAt": "2026-09-23T10:38:07"
      },
      {
        "id": 14,
        "actorId": "svc_maker",
        "action": "SESSION_REVOKED",
        "targetId": "sid_1",
        "beforeState": null,
        "afterState": null,
        "ipAddress": "0:0:0:0:0:0:0:1",
        "userAgent": "Mozilla/5.0 ...",
        "correlationId": null,
        "createdAt": "2026-09-23T10:25:14"
      },
      {
        "id": 13,
        "actorId": "svc_checker",
        "action": "TOKEN_REUSE_DETECTED",
        "targetId": "family-99",
        "beforeState": null,
        "afterState": null,
        "ipAddress": "0:0:0:0:0:0:0:1",
        "userAgent": "Mozilla/5.0 ...",
        "correlationId": null,
        "createdAt": "2026-09-23T10:15:30"
      }
    ],
    "pageable": {
      "pageNumber": 0,
      "pageSize": 10,
      "sort": {
        "sorted": true,
        "empty": false,
        "unsorted": false
      },
      "offset": 0,
      "paged": true,
      "unpaged": false
    },
    "totalPages": 2,
    "totalElements": 15,
    "last": false,
    "size": 10,
    "number": 0,
    "sort": {
      "sorted": true,
      "empty": false,
      "unsorted": false
    },
    "numberOfElements": 3,
    "first": true,
    "empty": false
  },
  "timestamp": "2026-09-23T04:12:00.000Z"
}
```

---

## 7. Kịch bản 6: Quản trị viên Vận hành (OPERATIONS_ADMIN Lifecycle & Guardrail Test)

> **Mục tiêu:** Kiểm tra trọn vẹn vòng đời và ranh giới quyền lực của vai trò `OPERATIONS_ADMIN` (Tier 2):
> 1. SUPERADMIN tạo tài khoản `OPERATIONS_ADMIN`.
> 2. `OPERATIONS_ADMIN` đăng nhập lần đầu và đổi mật khẩu bắt buộc.
> 3. Kiểm thử toàn bộ quyền được phép trên các tài khoản cấp dưới (Tier 3+: `SERVICE_ADMIN`, `AUDITOR`).
> 4. Kiểm thử hàng rào Tier Guardrail chặn các hành vi vượt quyền (tạo Superadmin, tạo Ops khác, tự khóa chính mình, xem Audit log).

### 🧪 Test 6.1: SUPERADMIN tạo tài khoản `ops_demo` (Mật khẩu tự động Random)
1. Đăng nhập bằng `superadmin` $\rightarrow$ Gán Bearer Token vào Swagger UI.
2. Gọi API tạo tài khoản:
   * **API:** `POST /v1/admins`
   * **Body:**
     ```json
     {
       "username": "ops_demo",
       "email": "ops_demo@ocb.com.vn",
       "fullName": "Operations Demo Admin",
       "roleCode": "OPERATIONS_ADMIN"
     }
     ```
* **Kết quả mong đợi (200 OK):**
  * Hệ thống tự động sinh `temporaryPassword` (14 ký tự ngẫu nhiên).
  * Trạng thái `PENDING_ACTIVATION`, `mustChangePassword: true`.
  * Copy `temporaryPassword` trả về trong response để sử dụng cho lần đăng nhập đầu tiên.

---

### 🧪 Test 6.2: `ops_demo` kích hoạt tài khoản & cài đặt MFA bắt buộc (Mandatory Onboarding)
1. Gọi `POST /v1/auth/login` với `ops_demo` và `temporaryPassword` vừa nhận được:
   * **Kết quả mong đợi (200 OK):**
     * `onboardingRequired`: `true`
     * `onboardingToken`: Chuỗi token kích hoạt (TTL 15 phút)
     * `totpSecretKey` & `totpQrCodeUri`: Dùng để liên kết Google Authenticator
2. Mở app **Google Authenticator** $\rightarrow$ Quét mã `totpQrCodeUri` (hoặc nhập thủ công `totpSecretKey`).
3. Gọi API hoàn tất Onboarding:
   * **API:** `POST /v1/auth/onboarding/complete`
   * **Body:**
     ```json
     {
       "onboardingToken": "<dán_onboardingToken_ở_bước_1>",
       "newPassword": "NewOpsPassword@2026",
       "totpCode": 123456
     }
     ```
* **Kết quả mong đợi (200 OK):**
  * Mật khẩu đổi thành công, trạng thái tài khoản chuyển sang `ACTIVE`.
  * MFA được kích hoạt chính thức (`confirmed = true`).
  * Trả về **10 mã dự phòng (Backup Codes)** và cấp trực tiếp **Access Token**!
  * Copy `accessToken` này dán vào Swagger UI để thực hiện các bài test quản trị tiếp theo.

---

### 🧪 Test 6.2.1: Kiểm chứng MFA trở thành BẮT BUỘC cho các lần đăng nhập sau
1. Gọi `POST /v1/auth/login` với `ops_demo` / `NewOpsPassword@2026`:
   * **Kết quả mong đợi (200 OK):**
     * `mfaRequired`: `true` (Không còn được cấp token trực tiếp nữa!)
     * `mfaToken`: Chuỗi challenge token
2. Gọi tiếp `POST /v1/auth/mfa/verify` với mã 6 số từ Google Authenticator $\rightarrow$ Nhận Access Token mới. Đảm bảo 100% MFA đã biến từ tự chọn thành bắt buộc!

---

### 🧪 Test 6.3: Kiểm thử các quyền ĐƯỢC PHÉP của `ops_demo` (Quản lý Tier 3+)
*(Sử dụng token của `ops_demo`)*

1. **Xem danh sách Admin:** `GET /v1/admins?page=0&size=10` $\rightarrow$ **200 OK**.
2. **Tạo tài khoản SERVICE_ADMIN cấp dưới:**
   * **API:** `POST /v1/admins`
   * **Body:**
     ```json
     {
       "username": "maker_by_ops",
       "email": "maker_ops@ocb.com.vn",
       "fullName": "Service Maker by Ops",
       "initialPassword": "MakerPass@123456",
       "roleCode": "SERVICE_ADMIN",
       "customGrants": [
         { "permissionCode": "config:read", "scopes": ["system-params-api"] },
         { "permissionCode": "config:write", "scopes": ["system-params-api"] }
       ]
     }
     ```
   * **Kết quả (200 OK):** Tạo thành công cấp dưới. Lưu lại `id` của admin này.
3. **Cập nhật thông tin cấp dưới:** `PATCH /v1/admins/{id}` với `{"fullName": "Updated Maker"}` $\rightarrow$ **200 OK**.
4. **Reset mật khẩu cấp dưới:** `POST /v1/admins/{id}/reset-password` $\rightarrow$ **200 OK** (trả `temporaryPassword` và hủy các session cũ của cấp dưới).
5. **Vô hiệu hóa (Disable) cấp dưới:** `POST /v1/admins/{id}/disable` $\rightarrow$ **200 OK** (trạng thái thành `DISABLED`, session bị xóa).
6. **Kích hoạt lại (Enable) cấp dưới:** `POST /v1/admins/{id}/enable` $\rightarrow$ **200 OK** (trạng thái quay lại `ACTIVE`).

---

### 🧪 Test 6.4: Kiểm thử Hàng rào bảo mật (Tier Guardrails - Các hành vi BỊ CHẶN của `ops_demo`)

| Mã Test | Hành động thử nghiệm | Endpoint & Tham số | Kết quả mong đợi |
|:---:|---|---|:---:|
| **6.4.1** | Thử tạo `SUPERADMIN` (Tier 1) | `POST /v1/admins` với role `SUPERADMIN` | **403 Forbidden** |
| **6.4.2** | Thử tạo `OPERATIONS_ADMIN` khác (Tier 2) | `POST /v1/admins` với role `OPERATIONS_ADMIN` | **403 Forbidden** |
| **6.4.3** | Thử khóa tài khoản `superadmin` | `POST /v1/admins/adm-superadmin-01/disable` | **403 Forbidden** |
| **6.4.4** | Thử tự khóa chính mình | `POST /v1/admins/{id_ops_demo}/disable` | **422 Unprocessable Entity** |
| **6.4.5** | Thử xem Audit logs | `GET /v1/audit-events?page=0&size=10` | **403 Forbidden** |

---

## 8. Kịch bản 7: Tinh chỉnh quyền cho OPERATIONS_ADMIN (Chỉ cấp quyền Kick Session)

> **Mục tiêu:** Kiểm chứng cơ chế **Hybrid Permission (bóp hẹp quyền so với Preset mặc định của Role)**:
> 1. SUPERADMIN tạo tài khoản `ops_kicker` mang role `OPERATIONS_ADMIN` nhưng chỉ cấp duy nhất 2 quyền:
>    * `session:read_any` (Xem session người khác)
>    * `session:revoke_any` (Kick session người khác)
>    * *(kèm `auth:self` được backend tự động đính kèm)*
> 2. Chứng minh `ops_kicker`:
>    * ✅ **LÀM ĐƯỢC:** Xem và kick session của tài khoản cấp dưới (`svc_maker`).
>    * ❌ **BỊ CHẶN (403):** Không thể tạo tài khoản, không thể reset mật khẩu, không thể khóa tài khoản (dù mang role `OPERATIONS_ADMIN`).

### 🧪 Test 7.1: SUPERADMIN tạo tài khoản `ops_kicker` với Custom Grants
1. Đăng nhập bằng `superadmin` $\rightarrow$ Gán Bearer Token vào Swagger UI.
2. Gọi API tạo tài khoản:
   * **API:** `POST /v1/admins`
   * **Body:**
     ```json
     {
       "username": "ops_kicker",
       "email": "ops_kicker@ocb.com.vn",
       "fullName": "Ops Admin Only Kick Session",
       "roleCode": "OPERATIONS_ADMIN",
       "customGrants": [
         {
           "permissionCode": "session:read_any",
           "scopes": ["*"]
         },
         {
           "permissionCode": "session:revoke_any",
           "scopes": ["*"]
         }
       ]
     }
     ```
* **Kết quả mong đợi (200 OK):**
  * Tự động sinh `temporaryPassword` (14 ký tự).
  * Quan sát mảng `permissions` trả về sẽ **chỉ có duy nhất 3 quyền**, hoàn toàn sạch sẽ:
  ```json
  "permissions": [
    { "perm": "session:read_any", "scope": ["*"] },
    { "perm": "session:revoke_any", "scope": ["*"] },
    { "perm": "auth:self", "scope": ["*"] }
  ]
  ```

---

### 🧪 Test 7.2: `ops_kicker` kích hoạt tài khoản & cài đặt MFA bắt buộc (Mandatory Onboarding)
1. Gọi `POST /v1/auth/login` với `ops_kicker` và `temporaryPassword` nhận được ở Test 7.1.
   * **Kết quả:** Trả về `onboardingRequired: true`, `onboardingToken`, `totpSecretKey`, `totpQrCodeUri`.
2. Mở **Google Authenticator** quét mã QR.
3. Hoàn tất Onboarding qua `POST /v1/auth/onboarding/complete`:
   ```json
   {
     "onboardingToken": "<dán_onboardingToken_ở_bước_1>",
     "newPassword": "NewKickerPass@2026",
     "totpCode": 123456
   }
   ```
* **Kết quả mong đợi (200 OK):**
  * Kích hoạt thành công, trạng thái chuyển sang `ACTIVE`, trả về `backupCodes` và `accessToken`.
  * Gán `accessToken` này vào Swagger UI để thực hiện các bài test kick session tiếp theo.

---

### 🧪 Test 7.3: Kiểm chứng chức năng ĐƯỢC CẤP (Kick session cấp dưới)
1. Mở một tab ẩn danh khác, đăng nhập tài khoản `svc_maker` (`Maker@123456`) để sinh ra một phiên làm việc trên Redis.
2. Quay lại tab của `ops_kicker`, gọi API xem session của `svc_maker`:
   * **API:** `GET /v1/admins/adm-maker-01/sessions`
   * **Kết quả (200 OK):** Trả về danh sách session đang sống của `svc_maker` kèm `sessionId` (ví dụ `sid_xyz123`).
3. Dùng `ops_kicker` gọi lệnh Kick phiên đó:
   * **API:** `DELETE /v1/admins/adm-maker-01/sessions/sid_xyz123`
   * **Kết quả (200 OK):** `"Admin session revoked successfully"`.
4. **Bằng chứng:** Ở tab của `svc_maker`, bấm gọi bất kỳ API nào $\rightarrow$ Lập tức nhận mã lỗi **`401 Unauthorized`** (phiên đã bị kick)!

---

### 🧪 Test 7.4: Kiểm chứng các quyền BỊ TƯỚC BỎ (Bị chặn 403 Forbidden)
Dù `ops_kicker` mang role `OPERATIONS_ADMIN`, nhưng vì chỉ được cấp quyền session, mọi hành động quản trị khác đều bị chặn đứng:

| Mã Test | Hành động thử nghiệm | Endpoint & Tham số | Kết quả mong đợi | Lý do bị chặn |
|:---:|---|---|:---:|---|
| **7.4.1** | Thử tạo tài khoản admin mới | `POST /v1/admins` với role `SERVICE_ADMIN` | **403 Forbidden** | Thiếu quyền `admin:create` |
| **7.4.2** | Thử reset mật khẩu của `svc_maker` | `POST /v1/admins/adm-maker-01/reset-password` | **403 Forbidden** | Thiếu quyền `admin:reset_password` |
| **7.4.3** | Thử khóa tài khoản của `svc_maker` | `POST /v1/admins/adm-maker-01/disable` | **403 Forbidden** | Thiếu quyền `admin:disable` |

---

### 💡 Mở rộng: Nâng quyền lại cho `ops_kicker` về sau (nếu muốn)
Nếu sau này SUPERADMIN muốn cấp thêm quyền tạo tài khoản cho `ops_kicker`, dùng tài khoản `superadmin` gọi:
* **API:** `PUT /v1/admins/{ops_kicker_id}/roles`
* **Body:** Thêm `admin:create` vào mảng `customGrants`.

---

## 8. Kịch bản 8: Quên Mật Khẩu Tự Phục Vụ Qua TOTP (Self-Service Password Reset)

> **Mục tiêu:** Kiểm chứng tính năng **Quên mật khẩu tự phục vụ** dành cho Admin. Không cần chờ Superadmin hay gửi Email, admin tự xác minh danh tính bằng ứng dụng **Google Authenticator** trên điện thoại vật lý để đặt lại mật khẩu an toàn.

### 🧪 Test 8.1: Yêu cầu đặt lại mật khẩu (`POST /v1/auth/password/forgot`)
*(Không cần đăng nhập, API public)*

1. Gọi API:
   * **API:** `POST /v1/auth/password/forgot`
   * **Body:**
     ```json
     {
       "username": "ops_demo"
     }
     ```
* **Kết quả mong đợi (200 OK):**
  ```json
  {
    "success": true,
    "message": "Reset token issued",
    "data": {
      "resetToken": "9f8e7d...:adm-uuid-ops",
      "method": "TOTP",
      "message": "Please submit the 6-digit TOTP code from your authenticator app along with your new password to complete the reset process."
    }
  }
  ```
  * Copy chuỗi `resetToken` (có hiệu lực 10 phút trên Redis).

---

### 🧪 Test 8.2: Đặt lại mật khẩu bằng mã OTP 6 số (`POST /v1/auth/password/reset`)
1. Mở app **Google Authenticator** trên điện thoại lấy mã OTP 6 số hiện tại của `ops_demo`.
2. Gọi API đặt lại mật khẩu:
   * **API:** `POST /v1/auth/password/reset`
   * **Body:**
     ```json
     {
       "resetToken": "<dán_resetToken_ở_Test_8.1>",
       "totpCode": "482910",
       "newPassword": "NewResetPass@2026"
     }
     ```
* **Kết quả mong đợi (200 OK):**
  * Đặt lại mật khẩu thành công.
  * Hệ thống tự động thu hồi toàn bộ session và refresh token cũ (nếu có).
  * Mở khóa tài khoản (nếu trước đó đang bị khóa do gõ sai mật khẩu quá 5 lần).

---

### 🧪 Test 8.3: Kiểm chứng đăng nhập bằng mật khẩu mới
1. Gọi `POST /v1/auth/login` với `ops_demo` / `NewResetPass@2026`:
   * **Kết quả:** Trả về `mfaRequired: true` và `mfaToken`.
2. Mở Google Authenticator lấy mã 6 số gọi `POST /v1/auth/mfa/verify`:
   * **Kết quả (200 OK):** Nhận Access Token mới và đăng nhập thành công vào hệ thống.

---

### 🧪 Test 8.4: Kiểm chứng các hàng rào an toàn khi Quên Mật Khẩu
| Mã Test | Hành động thử nghiệm | Dữ liệu gửi lên | Kết quả mong đợi |
|:---:|---|---|:---:|
| **8.4.1** | Nhập sai mã OTP 6 số | `totpCode: "000000"` | **401 Unauthorized** (Mã OTP không hợp lệ) |
| **8.4.2** | Đặt mật khẩu trùng mật khẩu vừa đổi | `newPassword: "NewResetPass@2026"` | **400/422 Bad Request** (Không được trùng mật khẩu hiện tại) |
| **8.4.3** | Mật khẩu yếu (< 12 ký tự) | `newPassword: "Weak123"` | **400 Bad Request** (Vi phạm chính sách mật khẩu) |

---

---

## 10. Kịch bản 9: Kiểm thử Event Bus qua Kafka & Transactional Outbox (Demo với Kafka UI)

```
Nghiệp vụ (POST API) ──[1 Tx]──▶ DB (audit_events + INSERT outbox_events PENDING)
                                         │
                                         ▼ (Async poll 500ms + SKIP LOCKED)
                                    OutboxRelay
                                         │
                                         ▼ (Avro + Schema Registry)
                                       Kafka
                                         ├── admin.auth.audit.events (key=actorId)
                                         └── admin.auth.notification.events (key=recipientId)
                                         │
                                         ▼
                                   Kafka UI (Kafbat - Port 8090)
```

### 10.1. Kiểm tra trạng thái Cluster & Topics trên Kafka UI
1. Mở trình duyệt truy cập: 👉 **[http://localhost:8090](http://localhost:8090)**
2. Kiểm tra Dashboard:
   - Cluster **`admin-auth-dev`** trạng thái **Online**.
   - Mục **Topics**: Đã tạo 2 topic:
     - `admin.auth.audit.events` (6 partitions, cleanup policy: `delete`)
     - `admin.auth.notification.events` (6 partitions, cleanup policy: `delete`)
   - Mục **Schema Registry**: Đã có 2 schema đăng ký:
     - `admin.auth.audit.events-value` (Avro compatibility: `BACKWARD`)
     - `admin.auth.notification.events-value` (Avro compatibility: `BACKWARD`)

---

### 🧪 Test 9.1: Đăng nhập sinh AuditEvent trên Kafka
1. Đăng nhập với tài khoản `svc_maker`:
   * **API:** `POST /api/v1/auth/login`
   * **Body:**
     ```json
     {
       "username": "svc_maker",
       "password": "Maker@123456"
     }
     ```
   * **Kết quả:** Đăng nhập thành công trả về Access Token.
2. Kiểm tra trên Kafka UI:
   * Vào Topic: **`admin.auth.audit.events`** $\rightarrow$ Tab **Messages**.
   * **Kết quả:** Xuất hiện message mới được tự động giải mã Avro:
     - `key`: ID hoặc username của `svc_maker`
     - `eventType`: `"LOGIN_SUCCESS"`
     - `severity`: `"INFO"`
     - `serviceName`: `"ae-admin-auth-service"`
     - `occurredAt`: Timestamp thời gian thực

---

### 🧪 Test 9.2: Tạo Admin sinh đồng thời AuditEvent và NotificationEvent
1. Dùng token của `superadmin` gọi tạo tài khoản mới:
   * **API:** `POST /api/v1/admins`
   * **Body:**
     ```json
     {
       "username": "kafka_demo_admin",
       "email": "kafka_demo@ocb.com.vn",
       "fullName": "Kafka Demo Admin",
       "roleCode": "OPERATIONS_ADMIN",
       "customGrants": []
     }
     ```
   * **Kết quả:** 201 Created kèm mật khẩu tạm (ví dụ: `Aa1@...`).
2. Kiểm tra trên Kafka UI:
   * **Topic `admin.auth.audit.events`**:
     - `eventType`: `"ACCOUNT_CREATED"`
     - `severity`: `"INFO"`
     - `actorId`: `"superadmin"`
     - `afterState`: Chứa JSON thông tin tài khoản vừa tạo.
   * **Topic `admin.auth.notification.events`**:
     - `key`: ID của `kafka_demo_admin`
     - `eventType`: `"ADMIN_ACCOUNT_CREATED"`
     - `channels`: `["EMAIL"]`
     - `templateCode`: `"ADMIN_ACCOUNT_CREATED"`
     - `params`: Chứa `temporaryPassword` và `role`
     - `sensitive`: `true`

---

### 🧪 Test 9.3: Vô hiệu hóa tài khoản sinh Security Notification đa kênh
1. `superadmin` gọi disable tài khoản vừa tạo:
   * **API:** `POST /api/v1/admins/{adminId}/disable`
   * **Kết quả:** 200 OK.
2. Kiểm tra trên Kafka UI:
   * **Topic `admin.auth.audit.events`**:
     - `eventType`: `"ACCOUNT_DISABLED"`
     - `severity`: `"WARN"`
   * **Topic `admin.auth.notification.events`**:
     - `eventType`: `"ACCOUNT_DISABLED"`
     - `channels`: `["EMAIL", "IN_APP", "SECURITY_ALERT"]` (Báo động bảo mật đa kênh)
     - `sensitive`: `false`

---

### 🧪 Test 9.4: Kích hoạt MFA sinh sự kiện `MFA_ENABLED`
1. Admin thực hiện kích hoạt TOTP qua `POST /api/v1/mfa/confirm`:
   * **Kết quả:** Cấp 10 backup codes.
2. Kiểm tra trên Kafka UI:
   * **Topic `admin.auth.audit.events`**: `eventType: "MFA_ENABLED"`, `severity: "INFO"`.
   * **Topic `admin.auth.notification.events`**: `eventType: "MFA_ENABLED"`, `channels: ["EMAIL"]`.

---

### 🧪 Test 9.5: Kiểm chứng Transactional Outbox trong Cơ sở dữ liệu
1. Mở công cụ truy vấn PostgreSQL (DBeaver, pgAdmin hoặc terminal container):
   ```sql
   SELECT id, topic, event_type, status, attempts, created_at, published_at
   FROM outbox_events
   ORDER BY created_at DESC
   LIMIT 10;
   ```
2. **Kết quả mong đợi:**
   * Các sự kiện tương ứng vừa sinh ra đều được ghi nhận trong bảng `outbox_events`.
   * Cột `status` hiển thị **`PUBLISHED`** và `published_at` có giá trị thời gian (được `OutboxRelay` xử lý thành công).
   * Cột `attempts` có giá trị `0` (không có lỗi retry).

---

## 11. Bảng tổng hợp Checklist kiểm thử

Bạn có thể sử dụng bảng sau để theo dõi tiến độ kiểm thử toàn bộ hệ thống:

| Nhóm chức năng | Mã Test | Tên kịch bản | Kết quả mong đợi | Trạng thái |
|---|:---:|---|---|:---:|
| **Xác thực 2 lớp (2FA)** | 1.1 | Login khi chưa bật 2FA | Nhận Access Token trực tiếp | ✅ / ⬜ |
| | 1.2 | Khởi tạo TOTP Setup | Trả về `secretKey` và `qrCodeUri` | ✅ / ⬜ |
| | 1.3 | Kích hoạt TOTP Enable | Xác nhận OTP thành công + cấp 10 backup codes | ✅ / ⬜ |
| | 1.4 | Login luồng 2 bước | Bước 1 nhận `mfaToken`, Bước 2 nhận Access Token | ✅ / ⬜ |
| | 1.5 | Nhập sai OTP 6 số | Trả lỗi 401 Unauthorized | ✅ / ⬜ |
| | 1.6 | Cứu hộ bằng Backup Code | Đăng nhập thành công với mã backup | ✅ / ⬜ |
| | 1.7 | Tái sử dụng Backup Code | Bị từ chối 401 vì mã chỉ dùng 1 lần | ✅ / ⬜ |
| **Phân quyền (RBAC/PBAC)** | 2.1 | OPS_ADMIN tạo SUPERADMIN | Bị chặn 403 Forbidden (Tier guardrail) | ✅ / ⬜ |
| | 2.2 | Tự vô hiệu hóa chính mình | Bị chặn 422 Unprocessable Entity | ✅ / ⬜ |
| | 2.3 | Tạo Admin với Custom Grants | Chỉ cấp đúng các quyền được chọn + `auth:self` | ✅ / ⬜ |
| **Quản lý Phiên (Redis)** | 3.1 | Mở 2 phiên đồng thời | Cả 2 phiên đều hoạt động bình thường | ✅ / ⬜ |
| | 3.2 | Xem danh sách Live Sessions | `GET /v1/me/sessions` hiển thị đủ 2 session | ✅ / ⬜ |
| | 3.3 | Kick phiên từ xa | Xóa thành công session được chọn | ✅ / ⬜ |
| | 3.4 | Token bị kick gọi API | Bị chặn 401 Unauthorized ngay lập tức | ✅ / ⬜ |
| **Refresh Token** | 4.1 | Đăng nhập nhận Refresh Token | Nhận chuỗi Refresh Token ban đầu | ✅ / ⬜ |
| | 4.2 | Refresh Token hợp lệ | Cấp Access Token mới + xoay vòng Refresh Token mới | ✅ / ⬜ |
| | 4.3 | Tái sử dụng Refresh Token cũ | Phát hiện Reuse $\rightarrow$ Hủy toàn bộ family & session | ✅ / ⬜ |
| **Kiểm toán (Audit)** | 5.1 | Truy vấn Audit Events | Xem được lịch sử truy vết chi tiết mọi hành vi | ✅ / ⬜ |
| **Vòng đời OPS_ADMIN (Đầy đủ)** | 6.1 | SUPERADMIN tạo OPS_ADMIN | Tạo thành công PENDING_ACTIVATION + 12 permissions | ✅ / ⬜ |
| | 6.2 | Đổi mật khẩu bắt buộc lần đầu | Đổi mật khẩu thành công | ✅ / ⬜ |
| | 6.3 | Quản lý cấp dưới Tier 3+ | Tạo, sửa, reset pass, kick session, disable/enable | ✅ / ⬜ |
| | 6.4 | Tier Guardrails chặn vượt quyền | Chặn tạo Superadmin, tạo Ops, tự khóa, xem Audit | ✅ / ⬜ |
| **Bóp quyền OPS_ADMIN (Chỉ Kick Session)** | 7.1 | Tạo OPS chỉ có quyền session | Mảng permissions chỉ có 3 quyền sạch sẽ | ✅ / ⬜ |
| | 7.2 | Kích hoạt Onboarding | Đổi mật khẩu + xác nhận TOTP | ✅ / ⬜ |
| | 7.3 | Thử kick session cấp dưới | Thành công (200 OK) | ✅ / ⬜ |
| | 7.4 | Thử các quyền khác | Bị chặn 403 Forbidden | ✅ / ⬜ |
| **Quên Mật Khẩu (Self-Service TOTP)** | 8.1 | Yêu cầu cấp resetToken | Nhận `resetToken` qua `POST /v1/auth/password/forgot` | ✅ / ⬜ |
| | 8.2 | Đặt lại mật khẩu với OTP 6 số | Thành công qua `POST /v1/auth/password/reset` | ✅ / ⬜ |
| | 8.3 | Đăng nhập bằng mật khẩu mới | Đăng nhập thành công với mật khẩu mới | ✅ / ⬜ |
| | 8.4 | Hàng rào bảo mật OTP & Policy | Chặn OTP sai, chặn trùng mật khẩu cũ | ✅ / ⬜ |
| **Kafka & Transactional Outbox** | 9.1 | Đăng nhập sinh AuditEvent | Message Avro giải mã đúng trên Kafka UI | ✅ / ⬜ |
| | 9.2 | Tạo Admin sinh 2 events | Sinh `ACCOUNT_CREATED` & `ADMIN_ACCOUNT_CREATED` | ✅ / ⬜ |
| | 9.3 | Khóa tài khoản sinh thông báo đa kênh | Channels `[EMAIL, IN_APP, SECURITY_ALERT]` | ✅ / ⬜ |
| | 9.4 | Bật MFA sinh event | `MFA_ENABLED` trên cả audit & notification topic | ✅ / ⬜ |
| | 9.5 | Kiểm chứng Outbox DB | Bản ghi chuyển từ `PENDING` sang `PUBLISHED` | ✅ / ⬜ |

