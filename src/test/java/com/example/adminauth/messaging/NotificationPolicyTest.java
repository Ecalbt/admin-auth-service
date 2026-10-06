package com.example.adminauth.messaging;

import com.example.adminauth.event.NotificationChannel;
import com.example.adminauth.event.NotificationEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationPolicyTest {

    private final NotificationPolicy policy = new NotificationPolicy();

    @Test
    @DisplayName("NotificationPolicy determines correct channels according to Section 5 policy matrix")
    void testDetermineChannels() {
        assertThat(policy.determineChannels(NotificationEventType.ADMIN_ACCOUNT_CREATED))
                .containsExactly(NotificationChannel.EMAIL);

        assertThat(policy.determineChannels(NotificationEventType.PASSWORD_RESET))
                .containsExactly(NotificationChannel.EMAIL);

        assertThat(policy.determineChannels(NotificationEventType.PASSWORD_CHANGED))
                .containsExactly(NotificationChannel.EMAIL);

        assertThat(policy.determineChannels(NotificationEventType.MFA_ENABLED))
                .containsExactly(NotificationChannel.EMAIL);

        assertThat(policy.determineChannels(NotificationEventType.ACCOUNT_ENABLED))
                .containsExactly(NotificationChannel.IN_APP);

        assertThat(policy.determineChannels(NotificationEventType.SESSION_REVOKED))
                .containsExactly(NotificationChannel.IN_APP);

        assertThat(policy.determineChannels(NotificationEventType.SESSION_REVOKED_ALL))
                .containsExactly(NotificationChannel.EMAIL, NotificationChannel.IN_APP);

        assertThat(policy.determineChannels(NotificationEventType.TOKEN_REUSE_DETECTED))
                .containsExactly(NotificationChannel.EMAIL, NotificationChannel.SECURITY_ALERT);

        assertThat(policy.determineChannels(NotificationEventType.ACCOUNT_DISABLED))
                .containsExactly(NotificationChannel.EMAIL, NotificationChannel.IN_APP, NotificationChannel.SECURITY_ALERT);

        assertThat(policy.determineChannels(NotificationEventType.ROLES_PERMISSIONS_CHANGED))
                .containsExactly(NotificationChannel.EMAIL, NotificationChannel.IN_APP, NotificationChannel.SECURITY_ALERT);

        assertThat(policy.determineChannels(NotificationEventType.ACCOUNT_LOCKED))
                .containsExactly(NotificationChannel.EMAIL, NotificationChannel.IN_APP, NotificationChannel.SECURITY_ALERT);
    }

    @Test
    @DisplayName("NotificationPolicy marks temporary passwords as sensitive")
    void testSensitiveFlag() {
        assertThat(policy.isSensitive(NotificationEventType.ADMIN_ACCOUNT_CREATED)).isTrue();
        assertThat(policy.isSensitive(NotificationEventType.PASSWORD_RESET)).isTrue();

        assertThat(policy.isSensitive(NotificationEventType.PASSWORD_CHANGED)).isFalse();
        assertThat(policy.isSensitive(NotificationEventType.ACCOUNT_DISABLED)).isFalse();
        assertThat(policy.isSensitive(NotificationEventType.TOKEN_REUSE_DETECTED)).isFalse();
    }
}
