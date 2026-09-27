package com.example.adminauth.dto.admin;

import com.example.adminauth.security.jwt.GrantDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response trả về khi tạo admin mới, bao gồm thông tin chi tiết và mật khẩu tạm thời.
 * Mật khẩu tạm chỉ hiển thị DUY NHẤT LẦN NÀY, không thể truy xuất lại sau đó.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateAdminResponse(
        String temporaryPassword,
        AdminDetailDto adminDetail
) {}
