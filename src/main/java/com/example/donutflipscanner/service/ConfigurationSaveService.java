package com.example.donutflipscanner.service;

import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface ConfigurationSaveService {
    CompletableFuture<Void> save();
}
