/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

#include "brave/browser/android/extensions/extensions_manager_android.h"

#include <vector>

#include "base/android/jni_array.h"
#include "base/android/jni_string.h"
#include "base/files/file_path.h"
#include "base/functional/bind.h"
#include "base/task/single_thread_task_runner.h"
#include "chrome/android/chrome_jni_headers/ExtensionInfo_jni.h"
#include "chrome/android/chrome_jni_headers/ExtensionsManager_jni.h"
#include "chrome/browser/extensions/extension_service.h"
#include "chrome/browser/profiles/profile.h"
#include "chrome/browser/profiles/profile_manager.h"
#include "content/public/browser/browser_context.h"
#include "content/public/browser/browser_thread.h"
#include "extensions/browser/crx_installer.h"
#include "extensions/browser/extension_system.h"
#include "extensions/common/extension.h"
#include "extensions/common/manifest.h"

// Additional headers for follow-up phases (left commented so the bootstrap
// build does not depend on them until each phase lands):
// #include "chrome/browser/extensions/webstore_installer.h"   (phase 2)
// #include "extensions/browser/unpacked_installer.h"          (phase 2, .xpi)
// #include "extensions/browser/extension_action_manager.h"    (phase 4)

using base::android::ConvertJavaStringToUTF8;
using base::android::ConvertUTF8ToJavaString;
using base::android::JavaParamRef;
using base::android::JavaRef;
using base::android::ScopedJavaLocalRef;

namespace brave {
namespace android {

namespace {

// Mirrors the Java-side ExtensionsManager.INSTALL_SOURCE_* constants.
enum class InstallSource {
  kSideload = 0,
  kChromeWebStore = 1,
  kMozillaAmo = 2,
};

Profile* GetActiveProfile() {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  return ProfileManager::GetLastUsedProfile();
}

}  // namespace

ExtensionsManager::ExtensionsManager(JNIEnv* env, const JavaRef<jobject>& obj)
    : weak_java_manager_(env, obj) {
  registry_observation_.Observe(
      extensions::ExtensionRegistry::Get(GetActiveProfile()));
}

ExtensionsManager::~ExtensionsManager() = default;

void ExtensionsManager::Destroy(JNIEnv* env) {
  delete this;
}

content::BrowserContext* ExtensionsManager::GetBrowserContext() {
  return GetActiveProfile();
}

void ExtensionsManager::GetInstalledExtensions(JNIEnv* env) {
  auto* registry = extensions::ExtensionRegistry::Get(GetActiveProfile());
  ScopedJavaLocalRef<jobject> jcaller = weak_java_manager_.get(env);
  if (!jcaller) {
    return;
  }

  for (const auto& extension : registry->enabled_extensions()) {
    EmitExtensionInfo(env, jcaller, extension, /*enabled=*/true);
  }
  for (const auto& extension : registry->disabled_extensions()) {
    EmitExtensionInfo(env, jcaller, extension, /*enabled=*/false);
  }

  Java_ExtensionsManager_onExtensionsLoaded(env, jcaller);
}

void ExtensionsManager::EmitExtensionInfo(
    JNIEnv* env,
    const JavaRef<jobject>& jcaller,
    scoped_refptr<const extensions::Extension> extension,
    bool enabled) {
  ScopedJavaLocalRef<jstring> j_id =
      ConvertUTF8ToJavaString(env, extension->id());
  ScopedJavaLocalRef<jstring> j_name = ConvertUTF8ToJavaString(
      env, extension->non_localized_name().empty()
              ? extension->name()
              : extension->non_localized_name());
  ScopedJavaLocalRef<jstring> j_version =
      ConvertUTF8ToJavaString(env, extension->version().GetString());

  ScopedJavaLocalRef<jobject> j_info = Java_ExtensionInfo_Constructor(
      env, j_id, j_name, j_version, enabled,
      static_cast<int>(extension->location()));

  Java_ExtensionsManager_onExtensionInfoLoaded(env, jcaller, j_info);
}

void ExtensionsManager::SetExtensionEnabled(
    JNIEnv* env,
    const JavaRef<jstring>& extension_id,
    jboolean enabled) {
  const std::string id = ConvertJavaStringToUTF8(env, extension_id);
  auto* service =
      extensions::ExtensionSystem::Get(GetBrowserContext())->extension_service();
  if (!service) {
    return;
  }
  // NOTE: verify disable-reason signature against the pinned chromium at
  // first build; newer branches use extensions::disable_reason::DisableReasonSet.
  if (enabled) {
    service->EnableExtension(id);
  } else {
    service->DisableExtension(
        id, extensions::disable_reason::DISABLE_USER_ACTION);
  }
}

void ExtensionsManager::UninstallExtension(
    JNIEnv* env,
    const JavaRef<jstring>& extension_id) {
  const std::string id = ConvertJavaStringToUTF8(env, extension_id);
  auto* service =
      extensions::ExtensionSystem::Get(GetBrowserContext())->extension_service();
  if (!service) {
    return;
  }
  // NOTE: verify the removal-callback signature at first build; upstream has
  // churned on this API between branches.
  service->UninstallExtension(
      id, extensions::UNINSTALL_REASON_USER_INITIATED, base::DoNothing(),
      /*dummy=*/nullptr);
}

void ExtensionsManager::InstallFromPath(JNIEnv* env,
                                        const JavaRef<jstring>& absolute_path) {
  base::FilePath path(ConvertJavaStringToUTF8(env, absolute_path));
  if (path.empty() || !base::PathExists(path)) {
    return;
  }

  // The user explicitly confirmed the install in the Java dialog, so a silent
  // installer is appropriate here.
  scoped_refptr<extensions::CrxInstaller> installer =
      extensions::CrxInstaller::CreateSilent(GetBrowserContext());
  installer->set_allow_silent_install(true);
  installer->set_is_gallery_install(false);
  installer->AddInstallerCallback(base::BindOnce(
      &ExtensionsManager::OnCrxInstallFinished, weak_factory_.GetWeakPtr()));
  installer->InstallCrx(path);
}

void ExtensionsManager::OnCrxInstallFinished(const base::FilePath& file_path,
                                             const std::string& error) {
  JNIEnv* env = base::android::AttachCurrentThread();
  ScopedJavaLocalRef<jobject> jcaller = weak_java_manager_.get(env);
  if (!jcaller) {
    return;
  }
  if (!error.empty()) {
    LOG(WARNING) << "CRX install failed: " << error;
  }
  // Either way the registry (and therefore the observer) reflects the new
  // state; nudge the Java UI.
  Java_ExtensionsManager_onExtensionsChanged(env, jcaller);
}

void ExtensionsManager::OnExtensionLoaded(
    content::BrowserContext* browser_context,
    const scoped_refptr<const extensions::Extension>& extension) {
  JNIEnv* env = base::android::AttachCurrentThread();
  ScopedJavaLocalRef<jobject> jcaller = weak_java_manager_.get(env);
  if (jcaller) {
    Java_ExtensionsManager_onExtensionsChanged(env, jcaller);
  }
}

void ExtensionsManager::OnExtensionUnloaded(
    content::BrowserContext* browser_context,
    const scoped_refptr<const extensions::Extension>& extension) {
  JNIEnv* env = base::android::AttachCurrentThread();
  ScopedJavaLocalRef<jobject> jcaller = weak_java_manager_.get(env);
  if (jcaller) {
    Java_ExtensionsManager_onExtensionsChanged(env, jcaller);
  }
}

void ExtensionsManager::OnExtensionUninstalled(
    content::BrowserContext* browser_context,
    const extensions::Extension* extension,
    extensions::UninstallReason reason) {
  JNIEnv* env = base::android::AttachCurrentThread();
  ScopedJavaLocalRef<jobject> jcaller = weak_java_manager_.get(env);
  if (jcaller) {
    Java_ExtensionsManager_onExtensionsChanged(env, jcaller);
  }
}

static void JNI_ExtensionsManager_Init(
    JNIEnv* env,
    const JavaParamRef<jobject>& obj) {
  Java_ExtensionsManager_setNativePtr(
      env, obj,
      reinterpret_cast<intptr_t>(new ExtensionsManager(env, obj)));
}

}  // namespace android
}  // namespace brave

DEFINE_JNI(ExtensionsManager)
