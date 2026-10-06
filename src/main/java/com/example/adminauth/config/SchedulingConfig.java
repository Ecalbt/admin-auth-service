package com.example.adminauth.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Cấu hình lập lịch (Scheduling) cho OutboxRelay và OutboxCleaner.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
