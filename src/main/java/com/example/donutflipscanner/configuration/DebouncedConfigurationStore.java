package com.example.donutflipscanner.configuration;

import com.example.donutflipscanner.service.ConfigurationSaveService;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Coalesces rapid UI changes and never writes from a render callback. */
public final class DebouncedConfigurationStore implements ConfigurationSaveService, AutoCloseable {
    private final ConfigurationManager manager;
    private final Duration delay;
    private final AtomicReference<AppConfig> current;
    private final ScheduledThreadPoolExecutor executor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private ScheduledFuture<?> pending;

    public DebouncedConfigurationStore(ConfigurationManager manager, AppConfig initial, Duration delay) {
        this.manager = Objects.requireNonNull(manager, "manager");
        current = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
        this.delay = Objects.requireNonNull(delay, "delay");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "donut-configuration-save");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
    }

    public synchronized void update(AppConfig configuration) {
        requireOpen();
        current.set(Objects.requireNonNull(configuration, "configuration"));
        if (pending != null) {
            pending.cancel(false);
        }
        pending = executor.schedule(() -> manager.save(current.get()), delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    public AppConfig snapshot() {
        return current.get();
    }

    @Override
    public synchronized CompletableFuture<Void> save() {
        requireOpen();
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
        return CompletableFuture.runAsync(() -> manager.save(current.get()), executor);
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
        manager.save(current.get());
        executor.shutdown();
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("configuration store is closed");
        }
    }
}
