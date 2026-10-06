package com.my.newproject;

import android.app.Activity;

/** Compile stub mirroring the members CgBase uses from the real CgData. */
final class CgData {
    String warning = "";
    private CgData() { }
    static boolean loaded() { return true; }
    static CgData cur() { return null; }
    static void load(Activity a, CgHttp.Done<CgData> done) { }
}
