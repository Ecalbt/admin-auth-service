# Kịch bản Kiểm thử Tự động — Admin Auth Service

Tài liệu này là **bảng đặc tả kịch bản để viết test tự động** (JUnit 5 + Mockito + MockMvc, profile `test` với H2; `SessionRedisService` có fallback in-memory khi không có Redis nên integration test chạy không cần Docker).

Khác với `TEST_SCENARIOS.md` (kịch bản **manual** qua Swagger UI), mỗi kịch bản dưới đây ghi rõ: điều kiện (Arrange), thao tác (Act), kết quả mong đợi (Assert) — gồm HTTP status, message, và **side-effect** trên DB/Redis/audit.

**Quy ước:**
- Trạng thái: `✓` đã có trong suite hiện tại (21 test) — theo README mục 9; `⬜` cần viết mới.
- Ưu tiên: **P0** = security lõi (phải có trước go-live), **P1** = nghiệp vụ chính, **P2** = edge case.
- Body lỗi chuẩn: 401/403 từ SecurityConfig có dạng `{status, error, message, timestamp}`; 404/422/400 từ `GlobalExceptionHandler` có dạng `{status, error, message, details, timestamp}`.

---

## 0. Bộ fixture chuẩn

Seed (Flyway `V2`):

| Username | Password | Role/Tier | Grants |
|---|---|:---:|---|
| `superadmin` | `SuperAdmin@123456` | SUPERADMIN / 1 | wildcard (bypass mọi check) |
| `ops_admin` | `OpsAdmin@123456` | OPERATIONS_ADMIN / 2 | `admin:*`, `session:*`, `role:read`, `auth:self` (scope `*`) |
| `svc_maker` | `Maker@123456` | SERVICE_ADMIN / 3 | `config:read`, `config:write` @ `["system-params-api"]` + `auth:self` |
| `svc_checker` | `Checker@123456` | SERVICE_ADMIN / 3 | `config:read`, `config:approve` @ `["system-params-api"]` + `auth:self` |

Hằng số cấu hình (`@Value` default): access TTL 1800s, refresh TTL 86400s, lockout 5 lần/30 phút, session idle 30 phút, max 5 session/admin, onboarding token TTL 15 phút, BCrypt cost 10, temp password 14 ký tự, 10 backup code × 8 ký tự.

---

## 1. Login — `POST /v1/auth/login` → `AuthServiceTest` / `AdminAuthIntegrationTest`

| ID | Kịch bản | Arrange & Act | Expected | P | STT |
|:---:|---|---|---|:---:|:---:|
| L-01 | Login thành công (chưa MFA) | Seeded ACTIVE admin đúng user/pass | 200; `mfaRequired=false`; có `accessToken`, `refreshToken`, `expiresIn=1800`, `roles`, `permissions`; Redis có `session:{sid}`; DB có refresh hash + `familyId`; audit `LOGIN_SUCCESS`; JWT claims: `sub`, `sid`, `roles`, `permissions[{perm,scope}]`, `mfa_verified=false`, `iss`, `jti` | P0 | ✓ |
| L-02 | Username không tồn tại | Login user lạ | 401 `"Invalid username or password"` (không tiết lộ user tồn tại hay không) | P0 | ✓ |
| L-03 | Sai mật khẩu (1 lần) | ACTIVE admin + sai pass | 401; `failedLoginAttempts=1`; audit `LOGIN_FAILED` | P1 | ✓ |
| L-04 | Lockout sau 5 lần sai | Sai pass 5 lần liên tiếp | Lần 5: 401 `"Account is locked due to too many failed login attempts. Try again in 30 minutes."`; `status=LOCKED`, `lockedUntil≈now+30m`; audit `ACCOUNT_LOCKED` | P0 | ✓ |
| L-05 | Tài khoản đang LOCKED | Login khi `lockedUntil` còn hạn | 401 `"Account is locked until {lockedUntil}"` | P0 | ✓ |
| L-06 | LOCKED hết hạn tự mở | Set `lockedUntil` quá khứ + login đúng pass | 200 login thành công; `status=ACTIVE`, attempts=0 | P1 | ✓ |
| L-07 | Tài khoản DISABLED | Disable trước đó, login đúng pass | 403 `"Account has been disabled by administrator"` | P0 | ✓ |
| L-08 | Login đúng sau lần sai | Sai 1 lần rồi đúng | 200; attempts reset về 0 | P2 | ✓ |
| L-09 | Tài khoản mới → Onboarding | Admin `PENDING_ACTIVATION` + temp password | 200; `onboardingRequired=true`, `mfaRequired=false`, KHÔNG accessToken; có `onboardingToken="<random>:<adminId>"`, `totpSecretKey`, `totpQrCodeUri`, `mustChangePassword=true`; Redis `onboarding:{adminId}` TTL 15m; TOTP `isConfirmed=false`; audit `ONBOARDING_REQUIRED` | P0 | ✓ |
| L-10 | Sau reset-password (chưa MFA) | `mustChangePassword=true`, chưa có TOTP confirmed | 200 `onboardingRequired=true` (đi onboarding như L-09) | P1 | ✓ |
| L-11 | `mustChangePassword=true` + ĐÃ có MFA | Reset pass nhưng TOTP confirmed | 200 `mfaRequired=true` + `mfaToken` (không onboarding) | P1 | ✓ |
| L-12 | Login khi đã bật MFA | TOTP confirmed + đúng pass | 200; `mfaRequired=true`, có `mfaToken="<random>:<adminId>"`, KHÔNG accessToken | P0 | ✓ |
| L-13 | Thiếu field begin_binding | Body thiếu `username`/`password` | 400 `"Validation failed"` + details field errors | P2 | ✓ |
| L-14 | Ghi nhận IP từ X-Forwarded-For | Header `X-Forwarded-For: 10.0.0.5, 172.16.0.1` | Audit `LOGIN_*` lưu IP `10.0.0.5` (phần tử đầu) | P2 | ✓ |

---

## 2. MFA Verify — `POST /v1/auth/mfa/verify` → `AuthServiceTest`

| ID | Kịch bản | Arrange & Act | Expected | P | STT |
|:---:|---|---|---|:---:|:---:|
| M-01 | TOTP đúng | `mfaToken` từ login + mã TOTP hợp lệ (sinh từ secret bằng GoogleAuthenticator) | 200; accessToken `mfa_verified=true`; roles/permissions nạp từ DB; audit `MFA_VERIFIED`; session mới trên Redis | P0 | ✓ |
| M-02 | mfaToken sai format | `"tokenthuicolon"` (không có `:`) | 401 `"Invalid MFA token format"` | P1 | ✓ |
| M-03 | TOTP sai | Mã `000000` (không hợp lệ) | 401 `"Invalid MFA code"`; audit `MFA_FAILED` | P0 | ✓ |
| M-04 | Backup code cứu hộ | `backupCode` đúng, input lowercase | 200 (code được trim + uppercase trước khi hash); `used_at` được ghi | P0 | ✓ |
| M-05 | Backup code chỉ dùng 1 lần | Dùng lại chính mã vừa dùng | 401 `"Invalid MFA code"` | P0 | ✓ |
| M-06 | totpCode không phải số + backup sai | `"abc"` + backup sai | 401 (fallback cả hai đều thất bại) | P2 | ✓ |
| M-07 | ⚠️ Đã fix GAP-01: validate mfaToken | `mfaToken="fake:adm-maker-01"` (bịa) | 401 `"MFA session is invalid or has expired"` | P0 | ✓ |

---

## 3. Onboarding — `/v1/auth/onboarding/**` → `AuthServiceTest`

| ID | Kịch bản | Arrange & Act | Expected | P | STT |
|:---:|---|---|---|:---:|:---:|
| O-01 | Hoàn tất onboarding | Token từ L-09 + pass hợp lệ + TOTP đúng | 200; `status=ACTIVE`, `mustChangePassword=false`; có accessToken+refreshToken (`mfa_verified=true`) + 10 backup codes; `password_history` có bản ghi; Redis xóa `onboarding:{id}`; audit `ONBOARDING_COMPLETED` | P0 | ✓ |
| O-02 | Onboarding token sai/hết hạn | Token bịa / TTL 15m đã quá | 401 `"Onboarding token is invalid or has expired"` (hoặc `"Invalid onboarding token format"` nếu thiếu `:`) | P0 | ✓ |
| O-03 | Mật khẩu yếu | `<12 ký tự` / thiếu chữ hoa / thiếu ký tự đặc biệt (`@$!%*?&`) | 422 `"Password must be at least 12 characters and contain uppercase, lowercase, digit, and special character (@$!%*?&)"` | P1 | ✓ |
| O-04 | Trùng mật khẩu tạm | `newPassword` = temp password | 422 `"New password cannot be the same as the temporary password"` | P1 | ✓ |
| O-05 | Trùng lịch sử mật khẩu | Trùng 1 trong 5 gần nhất | 422 `"Password has been used recently. Please choose a different password."` | P1 | ✓ |
| O-06 | TOTP sai lúc kích hoạt | Mã không hợp lệ | 422 `"Invalid verification code. Please check your authenticator app."` | P1 | ✓ |
| O-07 | Lấy lại QR giữa chừng | `POST /onboarding/mfa/setup` với token hợp lệ | 200 secret + QR mới (secret được regenerate); token hết hạn → 401 | P2 | ✓ |
| O-08 | MFA thành bắt buộc | Login lại sau O-01 | 200 `mfaRequired=true` (không còn được cấp token trực tiếp) | P0 | ✓ |

---

## 4. Refresh & Logout — `AuthServiceTest` / `AdminAuthIntegrationTest`

| ID | Kịch bản | Arrange & Act | Expected | P | STT |
|:---:|---|---|---|:---:|:---:|
| R-01 | Rotation hợp lệ | Refresh bằng RT vừa cấp | 200; access mới + **RT mới**; RT cũ có `revokedAt` + `replacedBy`; RT mới cùng `familyId` + `sessionId`; session được touch; roles/permissions **nạp lại từ DB** | P0 | ✓ |
| R-02 | Refresh token không tồn tại | Chuỗi random | 401 `"Invalid refresh token"` | P1 | ✓ |
| R-03 | Reuse detection (replay RT cũ) | Refresh RT1 → OK (nhận RT2); gửi lại RT1 | 401 `"Security Alert: Token reuse detected. All active tokens have been revoked."`; **revoke cả family** (RT2 chết); session của family bị xóa trên Redis; access token của phiên chết theo `sid` → 401; audit `TOKEN_REUSE_DETECTED` | P0 | ✓ |
| R-04 | RT hết hạn | `expiresAt` quá khứ | 401 `"Refresh token has expired. Please login again."` | P1 | ✓ |
| R-05 | Account bị disable giữa chừng | Disable admin rồi refresh bằng RT còn hạn | 403 `"Account is not active"` | P1 | ✓ |
| R-06 | Refresh phản ánh quyền mới | Đổi grants (PUT roles) rồi refresh bằng RT còn sống | Access token mới mang grants cập nhật (không cần login lại) | P1 | ✓ |
| X-01 | Logout | Token hợp lệ → `POST /v1/auth/logout` | 200; `session:{sid}` xóa; refresh theo `sessionId` revoked; audit `LOGOUT`; mọi API sau đó 401 | P0 | ✓ |
| X-02 | Logout không có token | Không header Authorization | 401 (endpoint yêu cầu authentication) | P2 | ✓ |

---

## 5. Đổi mật khẩu — `POST /v1/me/password` → `AuthServiceTest`

| ID | Kịch bản | Arrange & Act | Expected | P | STT |
|:---:|---|---|---|:---:|:---:|
| P-01 | Đổi mật khẩu thành công | old đúng + new đạt policy | 200; hash mới; `password_history` thêm hash cũ; **mọi session của admin chết (kể cả hiện tại)** → API kế tiếp 401; refresh tokens của admin bị revoke; audit `PASSWORD_CHANGED` | P0 | ✓ |
| P-02 | Old password sai | Sai `oldPassword` | 401 `"Old password does not match"` | P0 | ✓ |
| P-03 | New không đạt policy | Thiếu lớp ký tự | 422 (message policy) | P1 | ✓ |
| P-04 | New chứa username | `Superadmin@123456` cho user `superadmin` | 422 `"Password must not contain your username"` | P1 | ✓ |
| P-05 | Trùng 5 mật khẩu gần nhất | New = mật khẩu cũ đã dùng | 422 `"New password cannot be identical to any of your last 5 passwords"` | P1 | ✓ |

---

## 6. Quản lý Admin + Tier Guardrail → `AdminManagementServiceTest` / `AdminAuthIntegrationTest`

| ID | Kịch bản | Arrange & Act | Expected | P | STT |
|:---:|---|---|---|:---:|:---:|
| A-01 | SUPERADMIN tạo admin (preset) | `POST /v1/admins` không `customGrants` | 200; `temporaryPassword` 14 ký tự; `PENDING_ACTIVATION`; `mustChangePassword=true`; grants = **preset của role** (scope `["*"]`); audit `ACCOUNT_CREATED` | P0 | ✓ |
| A-02 | Username/email trùng | Tạo lại username/email đã có | 422 `"Username '...' is already taken"` / `"Email '...' is already in use"` | P1 | ✓ |
| A-03 | OPS tạo role tier ≤ 2 | `ops_admin` tạo với `roleCode=SUPERADMIN` hoặc `OPERATIONS_ADMIN` | 403 `"OPERATIONS_ADMIN is not authorized to create accounts of tier 1|2"` | P0 | ✓ |
| A-04 | SUPERADMIN tạo OPERATIONS_ADMIN | Superadmin tạo tier 2 | 200 (SUPERADMIN bypass tier) | P1 | ✓ |
| A-05 | Tier chặn cả khi có permission | SERVICE_ADMIN được gán `admin:create` (custom grant) rồi tạo tài khoản | 403 `"You do not have permission to create accounts"` (defense-in-depth) | P0 | ✓ |
| A-06 | `roleCode` không tồn tại | `roleCode=HACKER` | 404 `"Role not found with code: HACKER"` | P2 | ✓ |
| A-07 | Hybrid custom grants | Tạo với `customGrants` 2 quyền (như TEST_SCENARIOS 7.1) | Grants đúng 2 quyền được chỉ định + `auth:self` (scope `*`) tự thêm; **KHÔNG nạp preset thừa**; grant không khai báo scope → mặc định `["*"]` | P0 | ✓ |
| A-08 | Xem danh sách/chi tiết | `GET /v1/admins` (phân trang) + `GET /{id}` | 200 Page; detail có roles + grants; `{id}` lạ → 404 `"Admin not found with id: ..."` | P1 | ✓ |
| A-09 | PATCH cập nhật | Đổi `fullName`/`email` | 200; audit `ACCOUNT_UPDATED`; email trùng người khác → 422 | P2 | ✓ |
| A-10 | Tự disable chính mình | `POST /v1/admins/{mình}/disable` | 422 `"You cannot disable your own account"` | P0 | ✓ |
| A-11 | OPS disable SUPERADMIN | `ops_admin` disable `adm-superadmin-01` | 403 (tier 1) | P0 | ✓ |
| A-12 | Disable cấp dưới | OPS disable `svc_maker` | 200; `status=DISABLED`; **mọi session + refresh chết ngay**; audit `ACCOUNT_DISABLED`; target login lại → 403 | P0 | ✓ |
| A-13 | Enable lại | Enable sau disable | 200; `ACTIVE`; `failedLoginAttempts=0`, `lockedUntil=null`; audit `ACCOUNT_ENABLED` | P1 | ✓ |
| A-14 | Reset mật khẩu cấp dưới | OPS reset `svc_maker` | 200 + `temporaryPassword` 14 ký tự; `mustChangePassword=true`; mọi session chết; audit `PASSWORD_RESET`; target login bằng temp → vào onboarding (L-09/L-10) | P0 | ✓ |
| A-15 | PUT roles thay quyền hoàn toàn | Superadmin PUT `{roleCodes, customGrants}` mới cho svc_maker | 200; roles + grants **thay thế hoàn toàn** (grants cũ bị xóa); **mọi session chết** (snapshot rule) → phải login lại nhận quyền mới; audit `ROLE_ASSIGNED` | P0 | ✓ |
| A-16 | OPS tự gán role tier 2 | OPS PUT roles với `OPERATIONS_ADMIN` | 403 `"...assign role OPERATIONS_ADMIN to accounts of tier 2"` | P0 | ✓ |
| A-17 | Thiếu permission method-level | `svc_maker` (chỉ config:*) gọi `POST /v1/admins` | 403 `"Access denied: insufficient privileges"` (accessDeniedHandler) | P0 | ⬜ |

---

## 7. Session Management → `SessionRedisServiceTest` / `AdminAuthIntegrationTest`

| ID | Kịch bản | Arrange & Act | Expected | P | STT |
|:---:|---|---|---|:---:|:---:|
| S-01 | Vòng đời session | create → isValid → touch → revoke | Create sinh sid + TTL; touch reset `lastUsedAt`/`expiresAt`; revoke → `isValid=false` | P0 | ✓ |
| S-02 | Max 5 session, evict cũ nhất | Login/refresh lần thứ 6 | Session có `lastUsedAt` nhỏ nhất bị thu hồi (5 session còn lại hợp lệ) | P1 | ✓ |
| S-03 | Xem session của mình | `GET /v1/me/sessions` (2 phiên) | 200; sort `lastUsedAt` desc; không chứa session hết hạn | P1 | ✓ |
| S-04 | Kick 1 session của mình | `DELETE /v1/me/sessions/{sid_khác}` | 200; token phiên bị kick → 401 ngay; audit `SESSION_REVOKED` | P0 | ✓ |
| S-05 | Kick tất cả session của mình | `DELETE /v1/me/sessions` | 200; mọi phiên chết; audit `SESSION_REVOKED_ALL` | P1 | ✓ |
| S-06 | Quản trị xem/kick session người khác | SUPERADMIN `GET/DELETE /v1/admins/{id}/sessions` | 200; kick từng sid / tất cả OK; audit đúng event | P0 | ✓ |
| S-07 | OPS xem session cấp dưới | `ops_admin` GET sessions của `svc_maker` | 200 (có `session:read_any`) | P1 | ✓ |
| S-08 | ⚠️ Đã fix GAP-02: OPS xem/kick session SUPERADMIN | `ops_admin` GET `/v1/admins/adm-superadmin-01/sessions` | 403 (Tier Guardrail chặn cấp 2 đụng cấp 1) | P0 | ✓ |
| S-09 | Tự xem session mình không cần permission | Admin chỉ có `auth:self` GET sessions chính mình | 200 (bypass khi `adminId == actor.id`) | P2 | ✓ |

---

## 8. JWT / Filter / Evaluator / JWKS → `JwtTokenProviderTest` / `CustomAuthEvaluatorTest` / `AdminAuthIntegrationTest`

| ID | Kịch bản | Arrange & Act | Expected | P | STT |
|:---:|---|---|---|:---:|:---:|
| J-01 | Ký & verify RS256 | generateAccessToken → validateToken | `true`; header `alg=RS256`, `kid` trùng JWKS; `exp = iat + 1800`; claims đủ: `sub`, `username`, `email`, `sid`, `roles`, `permissions[{perm,scope}]`, `mfa_verified`, `jti` | P0 | ✓ |
| J-02 | Sửa 1 ký tự token | Tamper payload/signature | `validateToken=false` → request 401 | P0 | ✓ |
| J-03 | Token hết hạn | Token với `exp` quá khứ | `false` → 401 | P0 | ✓ |
| J-04 | Không có Authorization header | Gọi `/v1/me` không token | 401 `"Full authentication is required..."` (authenticationEntryPoint) | P0 | ✓ |
| J-05 | SUPERADMIN wildcard | Token của superadmin | Grants = `[{perm:"*", scope:["*"]}]`; evaluator bypass mọi `hasPerm`; session sống là đủ | P0 | ✓ |
| J-06 | `hasPerm` + scope check | Parameterized: đúng perm+scope / scope khác service / scope `["*"]` / scope rỗng / grant `"*"` / thiếu perm | `true`/`false` tương ứng (case-insensitive perm) | P0 | ✓ |
| J-07 | Maker-checker `isNotCreator` | `creatorId` = `sub` / = username / `null` / người khác | `false` / `false` / `true` / `true` (case-insensitive) — dùng cho checker không duyệt request của mình | P0 | ✓ |
| J-08 | JWKS public | `GET /.well-known/jwks.json` không token | 200; `keys[0].kty=RSA`, `alg=RS256`, có `kid` | P0 | ✓ |
| J-09 | Token đúng chữ ký, sid đã chết | Kick session rồi gọi API bằng token cũ | 401 ngay (filter kiểm tra `sid` trên session store); request hợp lệ → `touchSession` cập nhật `lastUsedAt` | P0 | ✓ |
| J-10 | `mfa_verified=false` (login không MFA) | Login seeded admin (chưa TOTP) | Token có `mfa_verified=false` — hiện tại KHÔNG bị chặn ở service này; hành vi ghi nhận để service下游 quyết định | P2 | ⬜ |

---

## 9. Audit — `AuditServiceTest` / `AdminAuthIntegrationTest`

| ID | Kịch bản | Arrange & Act | Expected | P | STT |
|:---:|---|---|---|:---:|:---:|
| AU-01 | Truy vấn có `audit:read` | SUPERADMIN `GET /v1/audit-events` | 200 Page (`content`, `totalElements`, `pageable`); default size 50 | P0 | ✓ |
| AU-02 | Filter | `actorId=ops_admin&action=ACCOUNT_CREATED&start&end&sort=createdAt,desc` | Chỉ trả event khớp filter, sort desc | P1 | ✓ |
| AU-03 | Thiếu `audit:read` | `svc_maker` GET audit-events | 403 | P0 | ⬜ |
| AU-04 | Đủ event cho từng nghiệp vụ | Parameterized qua các nhóm kịch bản trên | Mỗi hành động sinh đúng event: `LOGIN_SUCCESS/FAILED`, `ACCOUNT_LOCKED`, `ONBOARDING_REQUIRED/COMPLETED`, `MFA_VERIFIED/FAILED`, `TOKEN_REUSE_DETECTED`, `LOGOUT`, `PASSWORD_CHANGED/RESET`, `ACCOUNT_CREATED/UPDATED/DISABLED/ENABLED`, `ROLE_ASSIGNED`, `SESSION_REVOKED(_ALL)` | P1 | ✓ |

---

## 10. Validation & bảng mã lỗi (quick reference khi assert)

| Tình huống | HTTP | Nguồn xử lý | Message đặc trưng |
|---|:---:|---|---|
| Thiếu field `@Valid` | 400 | GlobalExceptionHandler | `"Validation failed"` + `details{field: msg}` |
| Sai credentials / token sai / OTP sai | 401 | GlobalExceptionHandler | `"Invalid username or password"`, `"Invalid refresh token"`, `"Invalid MFA code"` |
| Chưa đăng nhập | 401 | authenticationEntryPoint | `"Full authentication is required"` |
| Lockout / account locked | 401 | GlobalExceptionHandler (LockedException) | `"Account is locked..."` |
| Thiếu permission (`@PreAuthorize`) | 403 | accessDeniedHandler | `"Access denied: insufficient privileges"` |
| Tier guardrail (service ném AccessDenied) | 403 | GlobalExceptionHandler | `"Access denied: OPERATIONS_ADMIN is not authorized to..."` |
| Account disabled (login/refresh) | 403 | GlobalExceptionHandler (DisabledException) | `"Account has been disabled by administrator"` / `"Account is not active"` |
| Không tìm thấy resource | 404 | GlobalExceptionHandler | `"Admin not found with id:..."` |
| Vi phạm nghiệp vụ (policy, self-disable, trùng username) | 422 | GlobalExceptionHandler (BusinessException) | Xem từng kịch bản |

---

## 11. Concurrency & Idempotency (R11) — `AuthServiceTest` / `AdminManagementServiceTest`

> Ngân hàng yêu cầu đúng đếm + idempotent dưới tải song song (BRD R11). Dùng `ExecutorService` + `CountDownLatch`; assert trạng thái cuối trên DB/Redis.

| ID | Kịch bản | Arrange & Act | Expected | P | STT |
|:---:|---|---|---|:---:|:---:|
| CONC-01 | Lockout counter không lost-update | 10 request login sai song song cùng username | `failedLoginAttempts` đếm đủ (không mất cập nhật); chạm 5 → LOCKED. Nếu lost-update → fix bằng UPDATE atomic (`attempts = attempts + 1`) trước khi viết test pass | P0 | ⬜ |
| CONC-02 | Double-submit tạo admin | 2 request `POST /v1/admins` cùng username song song | Đúng 1 bản ghi; request thua → 422 (DB unique là tuyến phòng thủ cuối) | P0 | ⬜ |
| CONC-03 | Refresh cùng RT bằng 2 thread | 2 thread `POST /v1/auth/refresh` cùng token | Chỉ 1 thread 200; thread thua → reuse detection → family revoke (an toàn) — tuyệt đối không 2×200 | P0 | ✓ |
| CONC-04 | Disable đua request đang bay | Disable admin trong khi access token hợp lệ đang dùng | Request sau disable → 401 ngay (sid chết); request đã qua filter có thể hoàn tất — ghi nhận hành vi | P1 | ⬜ |
| CONC-05 | Onboarding complete 2 lần | 2 request complete cùng onboardingToken | Lần 2 → 401 (token đã xóa khỏi Redis) | P1 | ✓ |
| CONC-06 | 6 login song song (max session) | 6 thread login cùng admin | Kết thúc còn ≤ 5 session sống, evict cũ nhất | P2 | ⬜ |

---

## 12. Boundary & Time-edge — `JwtTokenProviderTest` / `SessionRedisServiceTest` / `AuthServiceTest`

> Assert biên ±1 giây; mock `Clock`/`Instant` khi có thể.

| ID | Kịch bản | Arrange & Act | Expected | P | STT |
|:---:|---|---|---|:---:|:---:|
| BND-01 | Access token biên hạn | Token còn 1s / quá 1s | 200 / 401 (`validateToken` check `exp`) | P1 | ✓ |
| BND-02 | Refresh token biên 24h | `expiresAt` = now+1s / now-1s | 200 / 401 `"Refresh token has expired. Please login again."` | P1 | ✓ |
| BND-03 | Session idle 30 phút biên | Touch lúc 29m59s / không touch đến 30m01s | Còn sống (`expiresAt` reset) / `isValidSession=false` → 401 | P1 | ✓ |
| BND-04 | Lockout biên 30 phút | `lockedUntil` quá khứ 1ms + login đúng | 200 (tự ACTIVE, attempts=0) | P2 | ✓ |
| BND-05 | Onboarding token biên 15 phút | Token vừa hết TTL | 401 `"Onboarding token is invalid or has expired"` | P2 | ✓ |
| BND-06 | TOTP window ±1 | Mã của khoảng liền trước | GoogleAuthenticator mặc định window ±1 → mã cũ **được chấp nhận** — quyết định policy (khuyến nghị window 0); xem GAP-15 | P1 | ⬜ |
| BND-07 | Page/size biên | `page=-1`, `size=0`, `size=1000` | Ghi nhận hành vi hiện tại; khuyến nghị validate (chặn page âm, size > max) | P2 | ⬜ |
| BND-08 | Sort field lạ | `sort=passwordHash,asc` trên `GET /v1/admins` | Ghi nhận hành vi; khuyến nghị whitelist sort field | P2 | ⬜ |

---

## 13. Input Hardening & Security Hygiene — `AdminAuthIntegrationTest`

> Mức ASVS: V2/V3/V5/V7. Log scrubbing assert bằng Logback `ListAppender`.

| ID | Kịch bản | Arrange & Act | Expected | P | STT |
|:---:|---|---|---|:---:|:---:|
| SEC-01 | SQL injection | username `admin' OR '1'='1 --`, payload trong mọi input tìm kiếm | Theo nghiệp vụ 401/422 bình thường; không 500, không bypass (JPA parameterized) | P0 | ✓ |
| SEC-02 | XSS lưu trữ | `fullName` chứa `<script>alert(1)</script>` khi tạo admin | Trả về nguyên dạng trong JSON (không execute); portal HTML phải tự encode — ghi nhận | P1 | ⬜ |
| SEC-03 | Không lộ secret trong log | Capture log trong login/MFA/onboarding/refresh | Mật khẩu, TOTP secret, backup codes, refreshToken **không** xuất hiện trong bất kỳ dòng log nào | P0 | ⬜ |
| SEC-04 | Username case uniqueness | Tạo `SUPERADMIN` khi đã có `superadmin` | Khuyến nghị normalize lowercase (→ 422); hiện tại DB unique phân biệt hoa/thường — cần quyết định + test | P1 | ⬜ |
| SEC-05 | Unicode/normalization | Username full-width `ＡＤＭＩＮ`, zero-width space | Chờ policy regex username (khuyến nghị ASCII) — hiện chưa có ràng buộc format | P2 | ⬜ |
| SEC-06 | customGrants lạ | `permissionCode` không tồn tại; `scopes:["*"]` | Permission lạ bị **bỏ qua im lặng** (`ifPresent`) → khuyến nghị 422; scope `*` chấp nhận (chỉ SUPERADMIN/OPS gọi được API) | P1 | ⬜ |
| SEC-07 | Audit tamper-proof (tầng API) | Gọi mọi method POST/PUT/PATCH/DELETE trên `/v1/audit-events` | Không tồn tại API sửa/xóa → 405/404; chỉ GET — append-only tầng API (khuyến nghị thêm `REVOKE UPDATE, DELETE` ở tầng DB) | P0 | ✓ |
| SEC-08 | TOTP secret không lộ | `GET /v1/me`, `GET /v1/admins/{id}`, audit query | Secret không xuất hiện trong response nào sau khi setup | P0 | ✓ |
| SEC-09 | Backup code entropy | Thống kê 1000 mã sinh ra | 8 ký tự × alphabet 31 ≈ 40 bit — ghi nhận kèm GAP-14 | P1 | ⬜ |
| SEC-10 | JWT alg manipulation | Token sửa header `alg:none` / `alg:HS256` | 401 (`RSASSAVerifier` chỉ chấp nhận RS256) | P0 | ✓ |

---

## 14. Resilience / DR — `SessionRedisServiceTest` / `AuditServiceTest`

| ID | Kịch bản | Arrange & Act | Expected | P | STT |
|:---:|---|---|---|:---:|:---:|
| RES-01 | Redis chết → fallback | Ép Redis client ném exception | Login/gọi API vẫn chạy qua fallback in-memory (code có sẵn); ghi nhận: fallback **per-instance** → nhiều replica sẽ lệch session — cần Redis HA thật (PLAN mục 9) | P0 | ✓ |
| RES-02 | Ghi audit fail | Mock repository ném lỗi khi `recordEvent` | Hiện tại: nuốt lỗi, request vẫn OK — kỳ vọng ngân hàng: event security-critical fail → chặn hành động hoặc alert (GAP-09) | P0 | ✓ |
| RES-03 | Restart service | Restart giữa các test | Hiện tại: key mới mỗi lần khởi động → mọi token cũ 401 (GAP-07). Sau khi fix persist key: token cũ còn hạn vẫn verify được qua JWKS | P0 | ⬜ |
| RES-04 | DB race trùng username | Như CONC-02 nhưng khoá DB bắn `DataIntegrityViolationException` | Map thành 422 sạch — không 500, không lộ stacktrace | P1 | ⬜ |

---

## 15. ⚠️ Các khoảng cách phát hiện khi đọc code (kịch bản GAP)

> Những điểm này **nên quyết định trước khi viết test pass** — hiện tại code hành xử khác PLAN/kỳ vọng ở nhiều điểm, sắp theo mức độ.

| ID | Mô tả & bằng chứng trong code | Khuyến nghị |
|:---:|---|---|
| GAP-01 | **M-07: `mfa/verify` không validate `mfaToken` với Redis** — chỉ split lấy `adminId`. Ai biết `adminId` + sở hữu mã TOTP hợp lệ có thể **bỏ qua bước 1 (password)** và nhận token (`AuthServiceImpl.verifyMfa` dòng 231–238) | Fix: lưu `mfaToken` vào Redis như `onboardingToken` (login đã tạo session `mfa:{adminId}` nhưng chưa dùng để validate) rồi mới verify. Viết test M-07 kỳ vọng **401** sau khi fix |
| GAP-02 | **S-08: session API không enforce tier** — `enforceSessionPermission` (SessionManagementServiceImpl dòng 74–84) chỉ check permission, không check tier → `ops_admin` (có `session:read_any`) xem/kick được session của SUPERADMIN — lệch PLAN mục 8.3 ("OPS chỉ cấp dưới") và TEST_SCENARIOS kỳ vọng OPS chỉ tier 3+ | Fix: thêm tier check như `enforceTierGuardrail`; test S-08 kỳ vọng 403 sau khi fix |
| GAP-03 | `/v1/auth/password/forgot` + `/reset` đã permitAll trong SecurityConfig nhưng **chưa có controller** (PLAN mục 5.1 có, chưa code) | Khi code: rate limit + audit + revoke session như reset-password |
| GAP-04 | README ghi "thu hồi tất cả session **khác**" khi đổi mật khẩu, nhưng code revoke **tất cả** (kể cả hiện tại — `AuthServiceImpl.changePassword`) | Giữ hành vi của code (an toàn hơn), test P-01 assert 401 ngay sau đổi; cập nhật README |
| GAP-05 | Refresh luôn cấp access token với `mfa_verified=true` (`AuthServiceImpl.refreshToken` dòng 317–320) bất kể MFA có bật hay không | Chấp nhận được (session đã qua xác thực); test R-01 ghi nhận hành vi |
| GAP-06 | BCrypt cost 10 (SecurityConfig) vs PLAN ghi "cost 12" | Thống nhất config; không ảnh hưởng test |
| GAP-07 | **CRITICAL — RSA key sinh in-memory mỗi startup**: `JwtKeyPairManager.@PostConstruct` gọi `generateKeyPair()`, không load/persist từ vault/KMS (PLAN 3.1 yêu cầu); restart → **mọi access token cũ vô hiệu** + JWKS cache của service khác giữ key cũ → lỗi hàng loạt; không hỗ trợ rotate (1 `kid` duy nhất) | Load/persist key từ vault/KMS hoặc volume mount; JWKS hỗ trợ đồng thời ≥2 `kid` trong giai đoạn rotate; test RES-03 |
| GAP-08 | **HIGH — CORS**: `setAllowedOriginPatterns(List.of("*"))` kèm `setAllowCredentials(true)` (SecurityConfig) — PLAN 3.5 yêu cầu whitelist đúng domain Admin Portal | Giới hạn origin theo environment (chỉ domain portal) |
| GAP-09 | **HIGH — Audit nuốt lỗi ghi**: `AuditServiceImpl.recordEvent` catch `Exception` chỉ log — sự kiện bảo mật mất âm thầm (vi phạm tinh thần BR-18); bản `@Async` có thể mất event khi shutdown | Ghi đồng bộ cho event security-critical (login/lockout/reuse/disable/permission); fail request hoặc alert khi ghi lỗi |
| GAP-10 | **HIGH — Audit filter không kết hợp điều kiện**: `AuditServiceImpl.getAuditLogs` là chuỗi `else-if` → `actorId=X&action=Y` chỉ lọc theo `actorId`, `action` bị bỏ qua (sai TEST_SCENARIOS 5.1) | Dùng JPA Specification kết hợp mọi điều kiện; test AU-02 phải lọc đúng cả hai |
| GAP-11 | **MEDIUM — `/actuator/**` permitAll** (SecurityConfig) | Chỉ mở `health` (`management.endpoints.web.exposure.include=health`); phần còn lại internal/authenticated |
| GAP-12 | **MEDIUM — Rate limiting chưa code** (PLAN 3.5 có): login/MFA/refresh/forgot không throttle per-IP/tổng thể; lockout hiện chỉ per-account | Thêm rate limit (Bucket4j/Redis) trước go-live; test brute-force 1 IP × nhiều username |
| GAP-13 | **MEDIUM — TOTP secret lưu plaintext** trong DB (PLAN ghi "encrypted") | Mã hóa tại nghỉ (AES-GCM, key từ vault) |
| GAP-14 | **MEDIUM — Backup code SHA-256 không key, 8 ký tự (~40 bit entropy)** — brute-force offline khả thi nếu DB bị lộ | HMAC-SHA256 với server secret hoặc BCrypt; tăng chiều dài ≥ 10 |
| GAP-15 | **LOW — TOTP không chống replay trong window**: GoogleAuthenticator mặc định window ±1, không track "mã vừa dùng" — cùng 1 mã verify được 2 lần trong window | Lưu (timestamp, mã) thành công gần nhất + từ chối trùng; quyết định kèm BND-06 |
| GAP-16 | **INFO — Audit retention chưa cấu hình** (BR-18 yêu cầu theo quy định OCB — PLAN open question) | Bổ sung policy retention/cleanup sau khi chốt số năm với OCB |

---

## 16. Traceability & Quality Gates (chuẩn fintech)

**Map với BRD/PLAN — trả lời được câu audit "test này chứng minh yêu cầu nào":**

| Yêu cầu | Nhóm kịch bản chứng minh |
|---|---|
| D07 (RBAC, maker-checker, audit, DR) | A, J-07, AU, RES |
| R11 (idempotent + audit trail) | CONC, AU, SEC-07 |
| R12 / BR-14 (maker-checker) | J-07, A-07 (tách `config:write`/`config:approve` theo người) |
| BR-13 (admin cấu hình tham số) | A-07 + downstream verify grants trong JWT |
| BR-18 (audit & retention) | AU, SEC-07, GAP-16 |
| PLAN mục 3 (JWT/MFA/password/session/hardening) | L, M, O, P, R/X, S, J, BND, SEC + GAP-07..15 |

**Chuẩn tham chiếu (OWASP ASVS):** L/M/O/P ≈ V2 Authentication; R/X/S/J ≈ V3 Session Management; A + CONC-02 ≈ V4 Access Control; AU + SEC-03/SEC-07 ≈ V7 Logging & Monitoring; SEC-01/02/10 ≈ V5 Validation.

**Quality gates cho CI:**

- JaCoCo ≥ 80% line & branch cho package `security`, `service` (rào ở `mvn verify`).
- PIT mutation testing: kill ≥ 85% mutant cho `JwtTokenProvider`, `CustomAuthEvaluator`, `AuthServiceImpl` (login/lockout/reuse).
- IT chính chạy trên **PostgreSQL thật (Testcontainers)** — H2 lệch hành vi với PostgreSQL (JSONB, unique case-sensitivity); profile riêng `ci-postgres`.
- Lưu evidence mỗi lần chạy (surefire + coverage + commit SHA) — ngân hàng yêu cầu chứng từ kiểm thử theo batch release.
- Performance tách khỏi unit test: k6/Gatling đo login P95 < 500ms, refresh, JWKS (PLAN mục 9).
- Test tự động không thay thế pentest định kỳ theo chu kỳ ngân hàng.

---

## 17. Ma trận phủ đề xuất

| Nhóm | Số kịch bản | Test class đề xuất | Đã có / Cần mới |
|---|:---:|---|:---:|
| Login (L) | 14 | AuthServiceTest + AdminAuthIntegrationTest | 3 / 11 |
| MFA (M) | 7 | AuthServiceTest | 0 / 7 |
| Onboarding (O) | 8 | AuthServiceTest | 0 / 8 |
| Refresh & Logout (R, X) | 8 | AuthServiceTest + AdminAuthIntegrationTest | 2 / 6 |
| Đổi mật khẩu (P) | 5 | AuthServiceTest | 1 / 4 |
| Quản lý Admin (A) | 17 | AdminManagementServiceTest + AdminAuthIntegrationTest | 4 / 13 |
| Session (S) | 9 | SessionRedisServiceTest + AdminAuthIntegrationTest | 2 / 7 |
| JWT/Evaluator/JWKS (J) | 10 | JwtTokenProviderTest + CustomAuthEvaluatorTest | 6 / 4 |
| Audit (AU) | 4 | AuditServiceTest (mới) | 0 / 4 |
| Concurrency (CONC) | 6 | AuthServiceTest + AdminManagementServiceTest | 0 / 6 |
| Boundary (BND) | 8 | JwtTokenProviderTest + SessionRedisServiceTest | 0 / 8 |
| Input hardening (SEC) | 10 | AdminAuthIntegrationTest | 0 / 10 |
| Resilience (RES) | 4 | SessionRedisServiceTest + AuditServiceTest | 0 / 4 |
| **Tổng** | **110** | | **18 / 92** |

**Thứ tự viết đề xuất:** Fix trước theo mức độ: GAP-07 (key persistence) → GAP-01, GAP-02, GAP-09, GAP-10 → rồi P0 theo thứ tự L → M → R/X → A → S → CONC → SEC → J → AU → RES → BND → P1 → P2.

Chạy: `mvn test` (profile `test` dùng H2; session dùng fallback in-memory của `SessionRedisService`, không cần Redis/PostgreSQL chạy). IT batch Testcontainers chạy ở CI: `mvn verify -Pci-postgres`.
