package org.chromium.chrome.browser.net_settings;

import org.jni_zero.JniType;
import org.jni_zero.NativeMethods;

import org.chromium.build.annotations.NullMarked;
import org.chromium.chrome.browser.profiles.Profile;

/** Access to the profile proxy pref, which is a dictionary and not settable via PrefService. */
@NullMarked
public final class NetSettingsBridge {
    private NetSettingsBridge() {}

    public static boolean isProxyEnabled(Profile profile) {
        return NetSettingsBridgeJni.get().isProxyEnabled(profile);
    }

    /** True if policy, an extension or chrome://flags overrides the proxy setting. */
    public static boolean isProxyManaged(Profile profile) {
        return NetSettingsBridgeJni.get().isProxyManaged(profile);
    }

    public static String getProxyServer(Profile profile) {
        return NetSettingsBridgeJni.get().getProxyServer(profile);
    }

    public static String getProxyBypassList(Profile profile) {
        return NetSettingsBridgeJni.get().getProxyBypassList(profile);
    }

    public static boolean isValidProxyServer(String server) {
        return NetSettingsBridgeJni.get().isValidProxyServer(server);
    }

    public static void setProxy(
            Profile profile, boolean enabled, String server, String bypassList) {
        NetSettingsBridgeJni.get().setProxy(profile, enabled, server, bypassList);
    }

    @NativeMethods
    interface Natives {
        boolean isProxyEnabled(@JniType("Profile*") Profile profile);

        boolean isProxyManaged(@JniType("Profile*") Profile profile);

        @JniType("std::string")
        String getProxyServer(@JniType("Profile*") Profile profile);

        @JniType("std::string")
        String getProxyBypassList(@JniType("Profile*") Profile profile);

        boolean isValidProxyServer(@JniType("std::string") String server);

        void setProxy(
                @JniType("Profile*") Profile profile,
                boolean enabled,
                @JniType("std::string") String server,
                @JniType("std::string") String bypassList);
    }
}
