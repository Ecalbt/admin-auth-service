package com.example.adminauth.dto.mfa;

import java.util.List;

public record BackupCodesResponse(
        List<String> backupCodes
) {}
