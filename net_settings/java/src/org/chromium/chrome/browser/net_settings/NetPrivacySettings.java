package org.chromium.chrome.browser.net_settings;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import org.chromium.build.annotations.NullMarked;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.prefs.LocalStatePrefs;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.components.browser_ui.settings.ChromeSwitchPreference;
import org.chromium.components.browser_ui.settings.SettingsUtils;
import org.chromium.components.prefs.PrefService;

/**
 * Adds the Encrypted Client Hello toggle and the Proxy entry to Privacy and security. Called from
 * PrivacySettingsExt (hook inserted by patch.sh).
 */
@NullMarked
public final class NetPrivacySettings {
    private static final String PREF_ECH = "encrypted_client_hello";
    private static final String PREF_PROXY = "custom_proxy";
    // chrome/common/pref_names.h kEncryptedClientHelloEnabled (local state). Observed live by
    // SSLConfigServiceManager, so toggling applies to new connections without a restart.
    private static final String LOCAL_STATE_ECH_ENABLED = "ssl.ech_enabled";

    private NetPrivacySettings() {}

    public static void initializePreferences(
            PreferenceFragmentCompat fragment, Profile profile, int order) {
        SettingsUtils.addPreferencesFromResource(fragment, R.xml.net_privacy_preferences);

        ChromeSwitchPreference echPref = fragment.findPreference(PREF_ECH);
        if (echPref != null) {
            echPref.setOrder(order);
            echPref.setOnPreferenceChangeListener(
                    (pref, value) -> {
                        PrefService localState = LocalStatePrefs.get();
                        if (localState == null) return false;
                        localState.setBoolean(LOCAL_STATE_ECH_ENABLED, (boolean) value);
                        return true;
                    });
        }
        Preference proxyPref = fragment.findPreference(PREF_PROXY);
        if (proxyPref != null) {
            proxyPref.setOrder(order);
        }
    }

    public static void updatePreferences(PreferenceFragmentCompat fragment, Profile profile) {
        ChromeSwitchPreference echPref = fragment.findPreference(PREF_ECH);
        PrefService localState = LocalStatePrefs.get();
        if (echPref != null && localState != null) {
            echPref.setChecked(localState.getBoolean(LOCAL_STATE_ECH_ENABLED));
            // Policy (EncryptedClientHelloEnabled) wins over the user choice.
            echPref.setEnabled(!localState.isManagedPreference(LOCAL_STATE_ECH_ENABLED));
        }
        Preference proxyPref = fragment.findPreference(PREF_PROXY);
        if (proxyPref != null) {
            proxyPref.setSummary(ProxySettings.getSummary(profile, fragment.requireContext()));
        }
    }
}
