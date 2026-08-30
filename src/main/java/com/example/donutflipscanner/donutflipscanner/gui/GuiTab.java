package com.example.donutflipscanner.gui;

public enum GuiTab {
    DASHBOARD("Dashboard", "Market and scanner overview"),
    OPPORTUNITIES("Opportunities", "Review detected market opportunities"),
    ITEM_FILTERS("Item Filters", "Manage whitelist and blacklist"),
    HISTORY("History", "Reviewed and dismissed opportunities"),
    AUTOMATION("Automation", "Authorization-gated execution controls"),
    SETTINGS("Settings", "Configure scanner and interface");

    private final String displayName;
    private final String description;

    GuiTab(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }
}
