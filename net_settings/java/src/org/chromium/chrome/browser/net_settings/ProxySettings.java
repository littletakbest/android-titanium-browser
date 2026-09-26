package org.chromium.chrome.browser.net_settings;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;

import org.chromium.base.supplier.MonotonicObservableSupplier;
import org.chromium.base.supplier.ObservableSuppliers;
import org.chromium.base.supplier.SettableMonotonicObservableSupplier;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.settings.ChromeBaseSettingsFragment;
import org.chromium.components.browser_ui.settings.ChromeSwitchPreference;
import org.chromium.components.browser_ui.settings.SettingsUtils;
import org.chromium.components.browser_ui.settings.TextMessagePreference;

/** Settings page for a browser-wide proxy that overrides the Android system proxy. */
@NullMarked
public class ProxySettings extends ChromeBaseSettingsFragment {
    static final String PREF_ENABLED = "proxy_enabled";
    static final String PREF_SERVER = "proxy_server";
    static final String PREF_BYPASS = "proxy_bypass_list";
    static final String PREF_MANAGED = "proxy_managed";

    private final SettableMonotonicObservableSupplier<String> mPageTitle =
            ObservableSuppliers.createMonotonic();

    private @Nullable ChromeSwitchPreference mEnabledPref;
    private @Nullable Preference mServerPref;
    private @Nullable Preference mBypassPref;
    private @Nullable TextMessagePreference mManagedPref;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        SettingsUtils.addPreferencesFromResource(this, R.xml.proxy_preferences);
        mPageTitle.set(getString(R.string.custom_proxy_title));

        mEnabledPref = findPreference(PREF_ENABLED);
        mServerPref = findPreference(PREF_SERVER);
        mBypassPref = findPreference(PREF_BYPASS);
        mManagedPref = findPreference(PREF_MANAGED);

        if (mEnabledPref != null) {
            mEnabledPref.setOnPreferenceChangeListener(
                    (pref, value) -> {
                        boolean enable = (boolean) value;
                        String server = NetSettingsBridge.getProxyServer(getProfile());
                        if (enable && TextUtils.isEmpty(server)) {
                            // Nothing to route through yet; ask for a server first.
                            showServerDialog(/* enableOnSave= */ true);
                            return false;
                        }
                        apply(enable, server, NetSettingsBridge.getProxyBypassList(getProfile()));
                        return true;
                    });
        }
        if (mServerPref != null) {
            mServerPref.setOnPreferenceClickListener(
                    pref -> {
                        showServerDialog(/* enableOnSave= */ false);
                        return true;
                    });
        }
        if (mBypassPref != null) {
            mBypassPref.setOnPreferenceClickListener(
                    pref -> {
                        showBypassDialog();
                        return true;
                    });
        }
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void apply(boolean enabled, String server, String bypassList) {
        NetSettingsBridge.setProxy(getProfile(), enabled, server, bypassList);
        refresh();
    }

    private void refresh() {
        Profile profile = getProfile();
        boolean managed = NetSettingsBridge.isProxyManaged(profile);
        String server = NetSettingsBridge.getProxyServer(profile);
        String bypass = NetSettingsBridge.getProxyBypassList(profile);

        if (mEnabledPref != null) {
            mEnabledPref.setChecked(NetSettingsBridge.isProxyEnabled(profile));
            mEnabledPref.setEnabled(!managed);
        }
        if (mServerPref != null) {
            mServerPref.setSummary(
                    TextUtils.isEmpty(server)
                            ? getString(R.string.custom_proxy_server_not_set)
                            : server);
            mServerPref.setEnabled(!managed);
        }
        if (mBypassPref != null) {
            mBypassPref.setSummary(
                    TextUtils.isEmpty(bypass)
                            ? getString(R.string.custom_proxy_bypass_none)
                            : bypass);
            mBypassPref.setEnabled(!managed);
        }
        if (mManagedPref != null) {
            mManagedPref.setVisible(managed);
        }
    }

    private void showServerDialog(boolean enableOnSave) {
        Profile profile = getProfile();
        EditText input =
                createInput(
                        NetSettingsBridge.getProxyServer(profile),
                        getString(R.string.custom_proxy_server_hint));
        AlertDialog dialog =
                new AlertDialog.Builder(requireContext(), R.style.ThemeOverlay_BrowserUI_AlertDialog)
                        .setTitle(R.string.custom_proxy_server_title)
                        .setView(wrap(input))
                        .setPositiveButton(android.R.string.ok, null)
                        .setNegativeButton(android.R.string.cancel, null)
                        .create();
        // Override the positive button so an invalid entry keeps the dialog open.
        dialog.setOnShowListener(
                d ->
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                                .setOnClickListener(
                                        v -> {
                                            String server = input.getText().toString().trim();
                                            if (!server.isEmpty()
                                                    && !NetSettingsBridge.isValidProxyServer(
                                                            server)) {
                                                input.setError(
                                                        getString(
                                                                R.string
                                                                        .custom_proxy_server_invalid));
                                                return;
                                            }
                                            boolean enabled =
                                                    !server.isEmpty()
                                                            && (enableOnSave
                                                                    || NetSettingsBridge
                                                                            .isProxyEnabled(
                                                                                    profile));
                                            apply(
                                                    enabled,
                                                    server,
                                                    NetSettingsBridge.getProxyBypassList(profile));
                                            dialog.dismiss();
                                        }));
        dialog.show();
    }

    private void showBypassDialog() {
        Profile profile = getProfile();
        EditText input =
                createInput(
                        NetSettingsBridge.getProxyBypassList(profile),
                        getString(R.string.custom_proxy_bypass_hint));
        new AlertDialog.Builder(requireContext(), R.style.ThemeOverlay_BrowserUI_AlertDialog)
                .setTitle(R.string.custom_proxy_bypass_title)
                .setView(wrap(input))
                .setPositiveButton(
                        android.R.string.ok,
                        (d, which) ->
                                apply(
                                        NetSettingsBridge.isProxyEnabled(profile),
                                        NetSettingsBridge.getProxyServer(profile),
                                        input.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private EditText createInput(String value, String hint) {
        EditText input = new EditText(requireContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(hint);
        input.setText(value);
        input.setSelection(input.getText().length());
        return input;
    }

    private FrameLayout wrap(EditText input) {
        FrameLayout container = new FrameLayout(requireContext());
        int padding =
                getResources().getDimensionPixelSize(R.dimen.custom_proxy_dialog_padding);
        container.setPadding(padding, padding / 2, padding, 0);
        container.addView(
                input,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return container;
    }

    /** Summary for the entry on the Privacy and security page. */
    public static String getSummary(Profile profile, Context context) {
        if (NetSettingsBridge.isProxyManaged(profile)) {
            return context.getString(R.string.custom_proxy_summary_managed);
        }
        if (NetSettingsBridge.isProxyEnabled(profile)) {
            return NetSettingsBridge.getProxyServer(profile);
        }
        return context.getString(R.string.custom_proxy_summary_system);
    }

    @Override
    public MonotonicObservableSupplier<String> getPageTitle() {
        return mPageTitle;
    }

    @Override
    public int getAnimationType() {
        return AnimationType.PROPERTY;
    }
}
