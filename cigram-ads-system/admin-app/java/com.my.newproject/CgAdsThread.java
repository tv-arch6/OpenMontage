package com.my.newproject;

/** The conversation currently open, so the heartbeat knows where to send itself. */
final class CgAdsThread {

    private static volatile String active = "";

    private CgAdsThread() { }

    static void setActive(String user) {
        active = user == null ? "" : user;
    }

    static void clear(String user) {
        if (user != null && user.equals(active)) active = "";
    }

    static String active() {
        return active;
    }
}
