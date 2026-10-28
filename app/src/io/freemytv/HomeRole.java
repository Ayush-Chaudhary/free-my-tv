package io.freemytv;

import android.content.Context;
import android.os.Build;

/**
 * Whether this app holds the HOME role. The role API is Android 10 (API 29); the class that names it is loaded only
 * then, so this stays safe on Android 8 and 9 (minSdk 26), where that class does not exist.
 */
final class HomeRole {
    private HomeRole() {}

    static boolean held(Context c) {
        return Build.VERSION.SDK_INT >= 29 && Held.now(c);
    }

    private static final class Held {
        static boolean now(Context c) {
            android.app.role.RoleManager roles = c.getSystemService(android.app.role.RoleManager.class);
            return roles != null && roles.isRoleHeld(android.app.role.RoleManager.ROLE_HOME);
        }
    }
}
