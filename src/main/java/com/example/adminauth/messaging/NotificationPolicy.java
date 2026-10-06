package com.example.adminauth.messaging;

import com.example.adminauth.event.NotificationChannel;
import com.example.adminauth.event.NotificationEventType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Định nghĩa kênh gửi (channels) và cờ nhạy cảm (sensitive) cho từng loại thông báo.
 */
@Component
public class NotificationPolicy {

    public List<NotificationChannel> determineChannels(NotificationEventType eventType) {
        return switch (eventType) {
            case ADMIN_ACCOUNT_CREATED, PASSWORD_RESET, PASSWORD_CHANGED, MFA_ENABLED ->
                    List.of(NotificationChannel.EMAIL);
            case ACCOUNT_ENABLED, SESSION_REVOKED ->
                    List.of(NotificationChannel.IN_APP);
            case SESSION_REVOKED_ALL ->
                    List.of(NotificationChannel.EMAIL, NotificationChannel.IN_APP);
            case TOKEN_REUSE_DETECTED ->
                    List.of(NotificationChannel.EMAIL, NotificationChannel.SECURITY_ALERT);
            case ACCOUNT_DISABLED, ROLES_PERMISSIONS_CHANGED, ACCOUNT_LOCKED ->
                    List.of(NotificationChannel.EMAIL, NotificationChannel.IN_APP, NotificationChannel.SECURITY_ALERT);
        };
    }

    public boolean isSensitive(NotificationEventType eventType) {
        return eventType == NotificationEventType.ADMIN_ACCOUNT_CREATED
                || eventType == NotificationEventType.PASSWORD_RESET;
    }

    public String getTemplateCode(NotificationEventType eventType) {
        return eventType.name();
    }
}
