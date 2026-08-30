package com.example.donutflipscanner.data.provider;

import com.example.donutflipscanner.data.NotificationSettings;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class LiveNotificationSettingsController implements NotificationSettingsController {
    private final AtomicBoolean enabled;
    private final boolean animationsEnabled;
    private final Consumer<Boolean> changeListener;

    public LiveNotificationSettingsController(
            boolean enabled,
            boolean animationsEnabled,
            Consumer<Boolean> changeListener
    ) {
        this.enabled = new AtomicBoolean(enabled);
        this.animationsEnabled = animationsEnabled;
        this.changeListener = Objects.requireNonNull(changeListener, "changeListener");
    }

    @Override
    public NotificationSettings getNotificationSettings() {
        return new NotificationSettings(enabled.get(), animationsEnabled);
    }

    @Override
    public void setNotificationsEnabled(boolean enabled) {
        if (this.enabled.getAndSet(enabled) != enabled) {
            changeListener.accept(enabled);
        }
    }
}
