package com.nuvio.tv.domain.model

enum class SettingsUiStyle(val displayName: String) {
    /** RCTV: a side panel grouped by section, each option its own soft card with a pill or a switch (the default). */
    GLASS("Glass"),
    CLASSIC("Classic"),
    ZEN("Zen"),
    HORIZON("Horizon")
}
