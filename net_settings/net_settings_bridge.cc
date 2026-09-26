// Native side of NetSettingsBridge: reads/writes the profile "proxy" pref
// (a dictionary, which the Java PrefService API cannot set). The pref is
// observed by PrefProxyConfigTracker, so changes apply without a restart.

#include <string>

#include "base/android/jni_string.h"
#include "base/values.h"
#include "chrome/browser/profiles/profile.h"
#include "components/prefs/pref_service.h"
#include "components/proxy_config/proxy_config_dictionary.h"
#include "components/proxy_config/proxy_config_pref_names.h"
#include "components/proxy_config/proxy_prefs.h"
#include "net/proxy_resolution/proxy_config.h"

// Must come after the includes above (needs the Profile* JNI conversion).
#include "titanium/chromium_src/chrome/browser/net_settings/android/jni_headers/NetSettingsBridge_jni.h"

namespace {

// Keys used by ProxyConfigDictionary (components/proxy_config).
constexpr char kModeKey[] = "mode";
constexpr char kServerKey[] = "server";
constexpr char kBypassListKey[] = "bypass_list";

PrefService* GetPrefs(Profile* profile) {
  // Incognito profiles read through to the original profile's user prefs.
  return profile->GetOriginalProfile()->GetPrefs();
}

ProxyConfigDictionary GetProxyDict(Profile* profile) {
  return ProxyConfigDictionary(
      GetPrefs(profile)->GetDict(proxy_config::prefs::kProxy).Clone());
}

}  // namespace

static bool JNI_NetSettingsBridge_IsProxyEnabled(JNIEnv* env,
                                                 Profile* profile) {
  ProxyPrefs::ProxyMode mode;
  return GetProxyDict(profile).GetMode(&mode) &&
         mode == ProxyPrefs::MODE_FIXED_SERVERS;
}

// True when the proxy pref is set by a higher-priority source (policy,
// extension, or the --proxy-server command line / chrome://flags entry).
static bool JNI_NetSettingsBridge_IsProxyManaged(JNIEnv* env,
                                                 Profile* profile) {
  const PrefService::Preference* pref =
      GetPrefs(profile)->FindPreference(proxy_config::prefs::kProxy);
  return pref && !pref->IsUserModifiable();
}

static std::string JNI_NetSettingsBridge_GetProxyServer(JNIEnv* env,
                                                        Profile* profile) {
  std::string server;
  GetProxyDict(profile).GetProxyServer(&server);
  return server;
}

static std::string JNI_NetSettingsBridge_GetProxyBypassList(JNIEnv* env,
                                                            Profile* profile) {
  std::string bypass_list;
  GetProxyDict(profile).GetBypassList(&bypass_list);
  return bypass_list;
}

static bool JNI_NetSettingsBridge_IsValidProxyServer(
    JNIEnv* env,
    const std::string& server) {
  net::ProxyConfig::ProxyRules rules;
  rules.ParseFromString(server);
  return !rules.empty();
}

// When disabled, the dictionary is left in "system" mode but keeps the
// server/bypass strings so the UI can restore them; "system" mode ignores them.
static void JNI_NetSettingsBridge_SetProxy(JNIEnv* env,
                                           Profile* profile,
                                           bool enabled,
                                           const std::string& server,
                                           const std::string& bypass_list) {
  base::DictValue dict;
  if (enabled && !server.empty()) {
    dict = ProxyConfigDictionary::CreateFixedServers(server, bypass_list);
  } else {
    dict = ProxyConfigDictionary::CreateSystem();
    if (!server.empty()) {
      dict.Set(kServerKey, server);
    }
    if (!bypass_list.empty()) {
      dict.Set(kBypassListKey, bypass_list);
    }
  }
  DCHECK(dict.FindString(kModeKey));
  GetPrefs(profile)->SetDict(proxy_config::prefs::kProxy, std::move(dict));
}

DEFINE_JNI(NetSettingsBridge)
