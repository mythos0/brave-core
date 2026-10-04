/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

#ifndef BRAVE_BROWSER_ANDROID_EXTENSIONS_EXTENSIONS_MANAGER_ANDROID_H_
#define BRAVE_BROWSER_ANDROID_EXTENSIONS_EXTENSIONS_MANAGER_ANDROID_H_

#include <jni.h>

#include <string>

#include "base/android/jni_android.h"
#include "base/files/file_path.h"
#include "base/memory/scoped_refptr.h"
#include "base/memory/weak_ptr.h"
#include "base/scoped_observation.h"
#include "extensions/browser/extension_registry.h"
#include "extensions/browser/extension_registry_observer.h"

namespace content {
class BrowserContext;
}  // namespace content

namespace extensions {
class Extension;
}  // namespace extensions

namespace brave {
namespace android {

/*
 * Native backing for org.chromium.chrome.browser.extensions.ExtensionsManager.
 *
 * Only compiled when enable_extensions_core is true (i.e. desktop builds and
 * Android builds made with enable_desktop_android_extensions = true); the
 * corresponding Java sources are gated on the same feature in
 * brave/android/brave_java_sources.gni.
 *
 * Provides: listing installed extensions, enable/disable, uninstall and
 * .crx sideload installation. Service-worker lifecycle (MV3), Webstore
 * installer integration and .xpi conversion are follow-up phases.
 */
class ExtensionsManager : public extensions::ExtensionRegistryObserver {
 public:
  ExtensionsManager(JNIEnv* env, const base::android::JavaRef<jobject>& obj);
  ExtensionsManager(const ExtensionsManager&) = delete;
  ExtensionsManager& operator=(const ExtensionsManager&) = delete;
  ~ExtensionsManager() override;

  void Destroy(JNIEnv* env);

  void GetInstalledExtensions(JNIEnv* env);
  void SetExtensionEnabled(JNIEnv* env,
                           const base::android::JavaRef<jstring>& extension_id,
                           jboolean enabled);
  void UninstallExtension(JNIEnv* env,
                          const base::android::JavaRef<jstring>& extension_id);
  void InstallFromPath(JNIEnv* env,
                       const base::android::JavaRef<jstring>& absolute_path);

 private:
  // extensions::ExtensionRegistryObserver:
  void OnExtensionLoaded(
      content::BrowserContext* browser_context,
      const scoped_refptr<const extensions::Extension>& extension) override;
  void OnExtensionUnloaded(
      content::BrowserContext* browser_context,
      const scoped_refptr<const extensions::Extension>& extension) override;
  void OnExtensionUninstalled(content::BrowserContext* browser_context,
                              const extensions::Extension* extension,
                              extensions::UninstallReason reason) override;

  // Emits one ExtensionInfo object to the Java caller.
  void EmitExtensionInfo(JNIEnv* env,
                         const base::android::JavaRef<jobject>& jcaller,
                         scoped_refptr<const extensions::Extension> extension,
                         bool enabled);

  // extensions::CrxInstaller callback.
  void OnCrxInstallFinished(const base::FilePath& file_path,
                            const std::string& error);

  content::BrowserContext* GetBrowserContext();

  base::android::JavaObjectWeakGlobalRef weak_java_manager_;
  base::ScopedObservation<extensions::ExtensionRegistry,
                          extensions::ExtensionRegistryObserver>
      registry_observation_{this};
  base::WeakPtrFactory<ExtensionsManager> weak_factory_{this};
};

}  // namespace android
}  // namespace brave

#endif  // BRAVE_BROWSER_ANDROID_EXTENSIONS_EXTENSIONS_MANAGER_ANDROID_H_
