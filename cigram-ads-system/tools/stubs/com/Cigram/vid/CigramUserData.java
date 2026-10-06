package com.Cigram.vid;
import android.content.Context;
/** Compile stub mirroring the real public API used by the ads feature. */
public final class CigramUserData {
    public static final String PREFS = "cigram_user_data_v1";
    private CigramUserData() { }
    public static boolean isLoggedIn(Context context) { return false; }
    public static String token(Context context) { return ""; }
    public static String email(Context context) { return ""; }
    public static String authUserId(Context context) { return ""; }
    public static String owner(Context context) { return "guest"; }
}
