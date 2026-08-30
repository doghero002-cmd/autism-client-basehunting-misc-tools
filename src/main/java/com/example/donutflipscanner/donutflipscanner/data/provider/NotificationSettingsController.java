package com.example.donutflipscanner.data.provider;

import com.example.donutflipscanner.data.NotificationSettings;

public interface NotificationSettingsController {
    NotificationSettings getNotificationSettings();

    void setNotificationsEnabled(boolean enabled);

    default void toggleNotifications() {
        setNotificationsEnabled(!getNotificationSettings().enabled());
    }
}
