package com.example.donutflipscanner.api.model;

import java.util.Optional;

public record ApiSeller(Optional<String> name, Optional<String> uuid) {
    public ApiSeller {
        name = name == null ? Optional.empty() : name;
        uuid = uuid == null ? Optional.empty() : uuid;
    }
}
