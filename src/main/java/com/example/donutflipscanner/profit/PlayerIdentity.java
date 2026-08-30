package com.example.donutflipscanner.profit;

import com.example.donutflipscanner.database.entity.SaleEntity;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Minecraft identity used only to recognize the player's completed auction sales. */
public record PlayerIdentity(String username, Optional<String> uuid) {
    public PlayerIdentity {
        username = Objects.requireNonNullElse(username, "").strip();
        uuid = (uuid == null ? Optional.<String>empty() : uuid)
                .map(PlayerIdentity::normalizeUuid)
                .filter(value -> !value.isBlank());
    }

    public boolean isUsable() {
        return !username.isBlank() || uuid.isPresent();
    }

    public boolean matches(SaleEntity sale) {
        Objects.requireNonNull(sale, "sale");
        if (uuid.isPresent() && sale.sellerUuid().isPresent()) {
            return uuid.get().equals(normalizeUuid(sale.sellerUuid().orElseThrow()));
        }
        return !username.isBlank() && sale.sellerName()
                .map(value -> value.strip().equalsIgnoreCase(username))
                .orElse(false);
    }

    private static String normalizeUuid(String value) {
        return Objects.requireNonNullElse(value, "")
                .replace("-", "").strip().toLowerCase(Locale.ROOT);
    }
}
