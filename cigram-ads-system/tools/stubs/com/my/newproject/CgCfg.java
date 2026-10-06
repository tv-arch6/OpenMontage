package com.my.newproject;

/**
 * CIGRAM ADMIN v2 - central configuration.
 * The admin token lives in ONE place only: CigramAdminAuthProvider.ADMIN_TOKEN.
 */
final class CgCfg {

    private CgCfg() {}

    static final String API = "https://cigram-admin-api.wwq-mixtv.workers.dev";
    static final String R2 = "https://pub-eabe41d14a0644e5800344c99ef4d971.r2.dev";
    static final String CHANNELS_API = "https://sigram-channels-api.wwq-mixtv.workers.dev";
    static final String METADATA_API = "https://cigram-metadata-api.wwq-mixtv.workers.dev";
    static final String TOKEN = CigramAdminAuthProvider.ADMIN_TOKEN;

    static final String PREFS = "cigram_admin_v2";

    // Same visual identity as the existing admin screens.
    static final int PAGE = 0xFF00151C;
    static final int CARD = 0xFF0B2733;
    static final int CARD2 = 0xFF102F3B;
    static final int STROKE = 0xFF31515C;
    static final int TEXT = 0xFFFFFFFF;
    static final int MUTED = 0xFFA9BAC1;
    static final int ACCENT = 0xFF29A3B2;
    static final int ACCENT_D = 0xFF218390;
    static final int GOOD = 0xFF5FCB8A;
    static final int WARN = 0xFFE6B85C;
    static final int BAD = 0xFFE67878;
    static final int GRAY = 0xFF7C8B92;
}
