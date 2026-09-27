package com.example.adminauth.security.session;

import com.example.adminauth.dto.session.AdminSessionDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SessionRedisServiceTest {

    private SessionRedisService sessionService;

    @BeforeEach
    void setUp() {
        sessionService = new SessionRedisService();
        ReflectionTestUtils.setField(sessionService, "idleTimeoutMinutes", 30L);
        ReflectionTestUtils.setField(sessionService, "maxConcurrentSessions", 3); // test with max 3
    }

    @Test
    @DisplayName("Create session and verify validity and details")
    void testCreateAndValidateSession() {
        AdminSessionDto session = sessionService.createSession("adm-1", "testuser", "192.168.1.100", "Mozilla/5.0");

        assertThat(session).isNotNull();
        assertThat(session.sessionId()).isNotBlank();
        assertThat(sessionService.isValidSession(session.sessionId())).isTrue();

        AdminSessionDto fetched = sessionService.getSession(session.sessionId());
        assertThat(fetched).isNotNull();
        assertThat(fetched.username()).isEqualTo("testuser");
        assertThat(fetched.ipAddress()).isEqualTo("192.168.1.100");
    }

    @Test
    @DisplayName("Revoke single session and verify it is no longer valid")
    void testRevokeSingleSession() {
        AdminSessionDto session = sessionService.createSession("adm-2", "user2", "127.0.0.1", "Chrome");
        assertThat(sessionService.isValidSession(session.sessionId())).isTrue();

        sessionService.revokeSession(session.sessionId());
        assertThat(sessionService.isValidSession(session.sessionId())).isFalse();
    }

    @Test
    @DisplayName("Enforce max concurrent sessions: oldest session is evicted")
    void testMaxConcurrentSessionsEviction() throws InterruptedException {
        AdminSessionDto s1 = sessionService.createSession("adm-3", "user3", "10.0.0.1", "Agent1");
        Thread.sleep(10);
        AdminSessionDto s2 = sessionService.createSession("adm-3", "user3", "10.0.0.2", "Agent2");
        Thread.sleep(10);
        AdminSessionDto s3 = sessionService.createSession("adm-3", "user3", "10.0.0.3", "Agent3");

        List<AdminSessionDto> activeBefore = sessionService.getSessionsForAdmin("adm-3");
        assertThat(activeBefore).hasSize(3);

        // Create 4th session -> exceeds max 3, should evict s1 (oldest)
        Thread.sleep(10);
        AdminSessionDto s4 = sessionService.createSession("adm-3", "user3", "10.0.0.4", "Agent4");

        assertThat(sessionService.isValidSession(s1.sessionId())).isFalse();
        assertThat(sessionService.isValidSession(s2.sessionId())).isTrue();
        assertThat(sessionService.isValidSession(s3.sessionId())).isTrue();
        assertThat(sessionService.isValidSession(s4.sessionId())).isTrue();

        List<AdminSessionDto> activeAfter = sessionService.getSessionsForAdmin("adm-3");
        assertThat(activeAfter).hasSize(3);
    }

    @Test
    @DisplayName("Revoke all sessions for admin")
    void testRevokeAllSessions() {
        AdminSessionDto s1 = sessionService.createSession("adm-4", "user4", "10.0.0.1", "Agent1");
        AdminSessionDto s2 = sessionService.createSession("adm-4", "user4", "10.0.0.2", "Agent2");

        assertThat(sessionService.isValidSession(s1.sessionId())).isTrue();
        assertThat(sessionService.isValidSession(s2.sessionId())).isTrue();

        sessionService.revokeAllSessionsForAdmin("adm-4");

        assertThat(sessionService.isValidSession(s1.sessionId())).isFalse();
        assertThat(sessionService.isValidSession(s2.sessionId())).isFalse();
        assertThat(sessionService.getSessionsForAdmin("adm-4")).isEmpty();
    }
}
