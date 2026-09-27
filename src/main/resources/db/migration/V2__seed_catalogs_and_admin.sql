-- =============================================================================
-- V2: Seed Roles, Permissions, Presets and Default Admin Accounts
-- =============================================================================

-- 1. Insert Roles (Tier: 1 = SUPERADMIN, 2 = OPERATIONS_ADMIN, 3 = SERVICE_ADMIN, 4 = THIRD_PARTY_ADMIN, 5 = AUDITOR)
INSERT INTO roles (id, code, name, tier, description) VALUES
(1, 'SUPERADMIN', 'Super Administrator', 1, 'Quản trị tối cao: quản trị IAM, security settings, break-glass full access'),
(2, 'OPERATIONS_ADMIN', 'Operations Administrator', 2, 'Quản trị viên ủy quyền: quản lý admin cấp dưới (SERVICE, THIRD_PARTY, AUDITOR)'),
(3, 'SERVICE_ADMIN', 'Service Administrator', 3, 'Quản trị tham số và nghiệp vụ theo scope service được gán'),
(4, 'THIRD_PARTY_ADMIN', 'Third-Party Administrator', 4, 'Quản trị viên đối tác (SPV1/SPV2/Vendor) xem dữ liệu đối soát theo partner_id'),
(5, 'AUDITOR', 'Compliance Auditor', 5, 'Kiểm toán/Tuân thủ: xem báo cáo và truy vết audit log (read-only toàn hệ thống)');

-- Reset sequence for roles if needed
SELECT setval(pg_get_serial_sequence('roles', 'id'), 5, true);

-- 2. Insert Permissions
INSERT INTO permissions (id, code, name, category, description) VALUES
-- Admin management
(1, 'admin:read', 'Xem danh sách admin', 'ADMIN', 'Xem thông tin tài khoản admin'),
(2, 'admin:create', 'Tạo tài khoản admin', 'ADMIN', 'Tạo tài khoản admin mới'),
(3, 'admin:update', 'Cập nhật tài khoản admin', 'ADMIN', 'Cập nhật thông tin admin'),
(4, 'admin:disable', 'Vô hiệu hóa admin', 'ADMIN', 'Khóa tài khoản admin tức thì'),
(5, 'admin:enable', 'Kích hoạt admin', 'ADMIN', 'Mở khóa tài khoản admin'),
(6, 'admin:reset_password', 'Reset mật khẩu admin', 'ADMIN', 'Reset mật khẩu admin cấp dưới'),
(7, 'admin:assign_role', 'Gán role cho admin', 'ADMIN', 'Gán role preset cho admin'),
(8, 'admin:assign_perm', 'Gán permission cho admin', 'ADMIN', 'Gán trực tiếp grant permission và scope'),

-- Session management
(9, 'session:read_any', 'Xem session của người khác', 'SESSION', 'Xem phiên đăng nhập của admin khác'),
(10, 'session:revoke_any', 'Kick session của người khác', 'SESSION', 'Thu hồi phiên đăng nhập của admin khác'),

-- Catalog
(11, 'role:read', 'Xem catalog role/permission', 'ROLE', 'Xem danh mục roles và permissions'),

-- Audit
(12, 'audit:read', 'Xem audit logs', 'AUDIT', 'Truy vấn nhật ký kiểm toán hệ thống'),

-- Operations
(13, 'ops:monitor:read', 'Xem giám sát vận hành', 'OPS', 'Theo dõi queue, scheduler, job status'),
(14, 'ops:exception:handle', 'Xử lý lỗi vận hành', 'OPS', 'Xử lý dead-letter, retry lệnh lỗi'),

-- Reconciliation
(15, 'recon:read', 'Xem đối soát', 'RECON', 'Xem báo cáo đối soát OCB - Core CD - SPV'),
(16, 'recon:case:manage', 'Xử lý case đối soát', 'RECON', 'Xử lý và điều chỉnh lệch dữ liệu đối soát'),

-- Reports
(17, 'report:read', 'Xem báo cáo', 'REPORT', 'Xem báo cáo danh mục, dòng tiền, coupon'),

-- Config (Maker-Checker)
(18, 'config:read', 'Xem cấu hình tham số', 'CONFIG', 'Xem tham số hệ thống'),
(19, 'config:write', 'Tạo/sửa cấu hình (Maker)', 'CONFIG', 'Đề xuất thay đổi cấu hình tham số'),
(20, 'config:approve', 'Phê duyệt cấu hình (Checker)', 'CONFIG', 'Phê duyệt hoặc từ chối đề xuất thay đổi tham số'),

-- Self-service
(21, 'auth:self', 'Tự phục vụ', 'AUTH', 'Đổi mật khẩu, quản lý MFA, xem và kick session của mình');

SELECT setval(pg_get_serial_sequence('permissions', 'id'), 21, true);

-- 3. Preset Templates (role_permissions)
-- SUPERADMIN Preset (tất cả quyền)
INSERT INTO role_permissions (role_id, permission_id)
SELECT 1, id FROM permissions;

-- OPERATIONS_ADMIN Preset (Quản lý admin cấp dưới + audit read + catalog)
INSERT INTO role_permissions (role_id, permission_id) VALUES
(2, 1), (2, 2), (2, 3), (2, 4), (2, 5), (2, 6), (2, 7), (2, 8),
(2, 9), (2, 10), (2, 11), (2, 21);

-- SERVICE_ADMIN Preset (Maker hoặc Checker + monitor/report/recon theo scope)
INSERT INTO role_permissions (role_id, permission_id) VALUES
(3, 11), (3, 13), (3, 14), (3, 15), (3, 16), (3, 17), (3, 18), (3, 19), (3, 20), (3, 21);

-- THIRD_PARTY_ADMIN Preset (chỉ đọc recon/report theo scope partner)
INSERT INTO role_permissions (role_id, permission_id) VALUES
(4, 15), (4, 17), (4, 21);

-- AUDITOR Preset (chỉ đọc toàn hệ thống: audit, recon, report, role)
INSERT INTO role_permissions (role_id, permission_id) VALUES
(5, 11), (5, 12), (5, 15), (5, 17), (5, 18), (5, 21);


-- 4. Seed Default Admin Accounts
-- Passwords:
-- SuperAdmin@123456 -> $2a$10$lHJHULW1YUHUJJ6YhZP2luA6ZO3wG0U8yQjlhhWIR7fF5R4bB.bau
-- OpsAdmin@123456   -> $2a$10$UX0ks1UrA0DtOHwTsrIsn.se7j8vDG4ur6vSnCpCoHNm4/YUWZ.ym
-- Maker@123456      -> $2a$10$gdQQHwNt96681tQ7ZDavt.cFuBE31JXRogOy/WmjDoevPsTLkD9C6
-- Checker@123456    -> $2a$10$mcB3ew7OTZlwPKmXMmBls.M8hlFUC/iG7vR5GSv3xVscToIOW2X82

INSERT INTO admins (id, username, email, full_name, password_hash, status, must_change_password, created_by) VALUES
('adm-superadmin-01', 'superadmin', 'superadmin@ocb.com.vn', 'Root Super Administrator', '$2a$10$lHJHULW1YUHUJJ6YhZP2luA6ZO3wG0U8yQjlhhWIR7fF5R4bB.bau', 'ACTIVE', FALSE, 'SYSTEM'),
('adm-opsadmin-01',   'ops_admin',   'ops@ocb.com.vn',        'Operations Administrator',     '$2a$10$UX0ks1UrA0DtOHwTsrIsn.se7j8vDG4ur6vSnCpCoHNm4/YUWZ.ym', 'ACTIVE', FALSE, 'superadmin'),
('adm-maker-01',      'svc_maker',   'maker@ocb.com.vn',      'Service Config Maker',         '$2a$10$gdQQHwNt96681tQ7ZDavt.cFuBE31JXRogOy/WmjDoevPsTLkD9C6', 'ACTIVE', FALSE, 'ops_admin'),
('adm-checker-01',    'svc_checker', 'checker@ocb.com.vn',    'Service Config Checker',       '$2a$10$mcB3ew7OTZlwPKmXMmBls.M8hlFUC/iG7vR5GSv3xVscToIOW2X82', 'ACTIVE', FALSE, 'ops_admin');

-- 5. Assign Roles to Default Admins
INSERT INTO admin_roles (admin_id, role_id, assigned_by) VALUES
('adm-superadmin-01', 1, 'SYSTEM'),
('adm-opsadmin-01',   2, 'superadmin'),
('adm-maker-01',      3, 'ops_admin'),
('adm-checker-01',    3, 'ops_admin');

-- 6. Assign Grants (admin_permissions - Nguồn sự thật Authorization)
-- superadmin: có thể gán wildcard qua role logic, nhưng seed sẵn wildcard grant
INSERT INTO admin_permissions (admin_id, permission_id, scope, granted_by) VALUES
('adm-superadmin-01', 1, '["*"]', 'SYSTEM');

-- ops_admin: các quyền quản lý user và session
INSERT INTO admin_permissions (admin_id, permission_id, scope, granted_by) VALUES
('adm-opsadmin-01', 1, '["*"]', 'superadmin'),
('adm-opsadmin-01', 2, '["*"]', 'superadmin'),
('adm-opsadmin-01', 3, '["*"]', 'superadmin'),
('adm-opsadmin-01', 4, '["*"]', 'superadmin'),
('adm-opsadmin-01', 5, '["*"]', 'superadmin'),
('adm-opsadmin-01', 6, '["*"]', 'superadmin'),
('adm-opsadmin-01', 7, '["*"]', 'superadmin'),
('adm-opsadmin-01', 8, '["*"]', 'superadmin'),
('adm-opsadmin-01', 9, '["*"]', 'superadmin'),
('adm-opsadmin-01', 10, '["*"]', 'superadmin'),
('adm-opsadmin-01', 11, '["*"]', 'superadmin'),
('adm-opsadmin-01', 21, '["*"]', 'superadmin');

-- svc_maker: Maker trên scope "system-params-api" (chỉ read + write, KHÔNG có approve)
INSERT INTO admin_permissions (admin_id, permission_id, scope, granted_by) VALUES
('adm-maker-01', 18, '["system-params-api"]', 'ops_admin'),
('adm-maker-01', 19, '["system-params-api"]', 'ops_admin'),
('adm-maker-01', 21, '["*"]', 'ops_admin');

-- svc_checker: Checker trên scope "system-params-api" (chỉ read + approve, KHÔNG có write)
INSERT INTO admin_permissions (admin_id, permission_id, scope, granted_by) VALUES
('adm-checker-01', 18, '["system-params-api"]', 'ops_admin'),
('adm-checker-01', 20, '["system-params-api"]', 'ops_admin'),
('adm-checker-01', 21, '["*"]', 'ops_admin');
