package com.example.adminauth.security.jwt;

import java.util.List;

public record GrantDto(
        String perm,
        List<String> scope
) {}
