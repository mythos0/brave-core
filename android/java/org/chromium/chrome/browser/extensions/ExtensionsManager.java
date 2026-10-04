/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.extensions;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.jni_zero.CalledByNative;
import org.jni_zero.JNINamespace;
import org.jni_zero.NativeMethods;

import org.chromium.base.Callback;
import org.chromium.base.Log;
import org.chromium.base.task.PostTask;
import org.chromium.base.task.TaskTraits;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Java-side entry point for Brave's Android extension support.
 *
 * <p>Backed by the native extensions system, which is compiled into the
 * Android build when the {@code enable_desktop_android_extensions} GN flag is
 * set (upstream Chromium's experimental desktop-Android extensions platform,
 * https://crbug.com/356905053). When the native layer is absent (normal mobile
 * builds) {@link #isSupported()} returns false and all UI callers must no-op.
 *
 * <p>Threading: all public methods must be called from the UI thread. Native
 * work that needs background execution schedules itself on the native side.
 */
@JNINamespace("brave::android")
public class ExtensionsManager {
    private static final String TAG = "Extensions";

    /** URL of the (WebUI) extensions management page. */
    public static final String EXTENSIONS_PAGE_URL = "chrome://extensions";

    /** Install sources. Keep in sync with extensions_manager_android.cc. */
    public static final int INSTALL_SOURCE_SIDELOAD = 0;
    public static final int INSTALL_SOURCE_CHROME_WEB_STORE = 1;
    public static final int INSTALL_SOURCE_MOZILLA_AMO = 2;

    /** MIME types accepted for sideloading. */
    public static final String MIME_CRX = "application/x-chrome-extension";
    public static final String MIME_XPI = "application/x-xpinstall";

    private static ExtensionsManager sInstance;
    private static Boolean sNativeAvailability;

    private long mNativeExtensionsManager;
    private boolean mNativeInitFailed;

    private final List<Observer> mObservers = new ArrayList<>();
    private List<ExtensionInfo> mPendingExtensions;
    private Callback<List<ExtensionInfo>> mLoadCallback;

    /** Notified whenever the set/state of installed extensions changes. */
    public interface Observer {
        void onExtensionsChanged();
    }

    public static synchronized ExtensionsManager get() {
        if (sInstance == null) sInstance = new ExtensionsManager();
        return sInstance;
    }

    /**
     * @return whether the native extensions system is available in this build
     *         (i.e. the build was produced with
     *         enable_desktop_android_extensions = true). Cheap after the first
     *         call.
     */
    public static synchronized boolean isSupported() {
        if (sNativeAvailability == null) {
            try {
                ExtensionsManager manager = get();
                sNativeAvailability = !manager.mNativeInitFailed;
            } catch (Throwable t) {
                Log.w(TAG, "Native extension support not compiled in.", t);
                sNativeAvailability = false;
            }
        }
        return sNativeAvailability;
    }

    private ExtensionsManager() {
        try {
            ExtensionsManagerJni.get().init(this);
        } catch (UnsatisfiedLinkError e) {
            // Extension support is not compiled into this binary.
            Log.w(TAG, "ExtensionsManager native init failed", e);
            mNativeInitFailed = true;
        }
    }

    @CalledByNative
    private void setNativePtr(long nativePtr) {
        assert mNativeExtensionsManager == 0;
        mNativeExtensionsManager = nativePtr;
    }

    @SuppressWarnings("Finalize")
    @Override
    protected void finalize() {
        destroy();
    }

    private void destroy() {
        if (mNativeExtensionsManager != 0) {
            ExtensionsManagerJni.get().destroy(mNativeExtensionsManager);
            mNativeExtensionsManager = 0;
        }
    }

    public void addObserver(Observer observer) {
        mObservers.add(observer);
    }

    public void removeObserver(Observer observer) {
        mObservers.remove(observer);
    }

    /**
     * Asynchronously loads the list of installed extensions (enabled and
     * disabled). The callback receives an unmodifiable snapshot list.
     */
    public void loadInstalledExtensions(Callback<List<ExtensionInfo>> callback) {
        if (mNativeExtensionsManager == 0) {
            PostTask.postTask(
                    TaskTraits.UI_DEFAULT, () -> callback.onResult(new ArrayList<>()));
            return;
        }
        mPendingExtensions = new ArrayList<>();
        mLoadCallback = callback;
        ExtensionsManagerJni.get().getInstalledExtensions(mNativeExtensionsManager);
    }

    public void setExtensionEnabled(String extensionId, boolean enabled) {
        if (mNativeExtensionsManager == 0) return;
        ExtensionsManagerJni.get()
                .setExtensionEnabled(mNativeExtensionsManager, extensionId, enabled);
    }

    public void uninstallExtension(String extensionId) {
        if (mNativeExtensionsManager == 0) return;
        ExtensionsManagerJni.get().uninstallExtension(mNativeExtensionsManager, extensionId);
    }

    /**
     * Installs a .crx (or, in a later phase, an unpacked directory / .xpi)
     * from a path inside this app's cache dir. The caller is responsible for
     * having shown a confirmation dialog.
     */
    public void installFromPath(String absolutePath) {
        if (mNativeExtensionsManager == 0) return;
        ExtensionsManagerJni.get().installFromPath(mNativeExtensionsManager, absolutePath);
    }

    /** Opens the extensions management page (chrome://extensions). */
    public void openExtensionsPage(Context context) {
        Intent intent =
                new Intent(Intent.ACTION_VIEW, Uri.parse(EXTENSIONS_PAGE_URL));
        intent.setPackage(context.getPackageName());
        try {
            context.startActivity(intent);
        } catch (android.content.ActivityNotFoundException e) {
            // Phase 2 wires chrome://extensions WebUI into desktop-android
            // builds; until then, surface the manager through this activity.
            Log.w(TAG, "No handler for chrome://extensions yet", e);
        }
    }

    /**
     * @return whether the given URL points at the Chrome Web Store (detail
     *         pages, gallery root, or the direct update2/crx endpoint).
     */
    public static boolean isChromeWebStoreUrl(Uri uri) {
        String host = uri.getHost();
        if (host == null) return false;
        if (!host.equals("chrome.google.com") && !host.endsWith(".chrome.google.com")) {
            return false;
        }
        String path = uri.getPath();
        return path != null && path.startsWith("/webstore");
    }

    /** @return whether the URL is a Firefox Add-ons (AMO) detail page. */
    public static boolean isMozillaAmoUrl(Uri uri) {
        String host = uri.getHost();
        if (host == null) return false;
        String path = uri.getPath();
        return host.equals("addons.mozilla.org")
                && path != null
                && (path.startsWith("/firefox/addon/") || path.contains("/downloads/"));
    }

    /**
     * Builds the direct CRX download URL for a Chrome Web Store extension ID
     * using the same update2 endpoint that Chromium's WebstoreInstaller uses
     * on desktop.
     */
    public static String buildCwsCrxDownloadUrl(String extensionId, String browserVersion) {
        String x = "id=" + extensionId + "&installsource=ondemand&uc";
        return "https://clients2.google.com/service/update2/crx"
                + "?response=redirect"
                + "&acceptformat=crx2,crx3"
                + "&prodversion=" + browserVersion
                + "&x=" + Uri.encode(x);
    }

    /**
     * Extracts the extension ID from a Chrome Web Store detail URL such as
     * https://chrome.google.com/webstore/detail/<slug>/<id> or ...?id=<id>.
     */
    public static String extractCwsExtensionId(Uri uri) {
        String param = uri.getQueryParameter("id");
        if (param != null && param.length() == 32 && param.matches("[a-p]+")) return param;
        String path = uri.getPath();
        if (path == null) return null;
        String[] segments = path.split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            String segment = segments[i];
            if (segment.length() == 32 && segment.matches("[a-p]+")) return segment;
        }
        return null;
    }

    /**
     * Builds a direct download URL for an AMO add-on slug/GUID, e.g.
     * https://addons.mozilla.org/firefox/downloads/latest/<slug>/addon.xpi
     */
    public static String buildAmoXpiDownloadUrl(String slugOrGuid) {
        return "https://addons.mozilla.org/firefox/downloads/latest/"
                + slugOrGuid + "/addon.xpi";
    }

    /**
     * Downloads {@code url} into the app cache and returns the local file via
     * {@code callback}. Executed on a background thread; the callback is
     * posted back on the UI thread. The returned file is null on failure.
     */
    public static void downloadToFile(
            Context context, String url, String suggestedName, Callback<File> callback) {
        PostTask.postTask(
                TaskTraits.USER_VISIBLE,
                () -> {
                    File outFile = null;
                    try {
                        File dir = new File(context.getCacheDir(), "extension_installs");
                        if (!dir.exists()) dir.mkdirs();
                        outFile = new File(dir, suggestedName);

                        HttpURLConnection connection =
                                (HttpURLConnection) new URL(url).openConnection();
                        connection.setInstanceFollowRedirects(true);
                        connection.setConnectTimeout(15_000);
                        connection.setReadTimeout(30_000);
                        // A mainstream desktop-like UA: the webstore serves CRX
                        // payloads to browsers, not to bare default Java agents.
                        connection.setRequestProperty(
                                "User-Agent",
                                "Mozilla/5.0 (Linux; Android "
                                        + android.os.Build.VERSION.RELEASE
                                        + ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                                        + org.chromium.base.ChromeVersionInfo
                                                .getProductVersion()
                                        + " Mobile Safari/537.36");

                        int responseCode = connection.getResponseCode();
                        if (responseCode < 200 || responseCode >= 300) {
                            throw new IOException("HTTP " + responseCode + " for " + url);
                        }

                        try (InputStream in = connection.getInputStream();
                                FileOutputStream out = new FileOutputStream(outFile)) {
                            byte[] buffer = new byte[16 * 1024];
                            int read;
                            while ((read = in.read(buffer)) != -1) {
                                out.write(buffer, 0, read);
                            }
                        }
                    } catch (IOException | RuntimeException e) {
                        Log.w(TAG, "Extension download failed: " + url, e);
                        outFile = null;
                    }

                    final File result = outFile;
                    PostTask.postTask(
                            TaskTraits.UI_DEFAULT, () -> callback.onResult(result));
                });
    }

    // Native -> Java callbacks.

    @CalledByNative
    private void onExtensionInfoLoaded(ExtensionInfo info) {
        if (mPendingExtensions != null) mPendingExtensions.add(info);
    }

    @CalledByNative
    private void onExtensionsLoaded() {
        if (mPendingExtensions == null) return;
        List<ExtensionInfo> snapshot = new ArrayList<>(mPendingExtensions);
        mPendingExtensions = null;
        Callback<List<ExtensionInfo>> callback = mLoadCallback;
        mLoadCallback = null;
        if (callback != null) callback.onResult(snapshot);
    }

    @CalledByNative
    private void onExtensionsChanged() {
        for (Observer observer : new ArrayList<>(mObservers)) {
            observer.onExtensionsChanged();
        }
    }

    @NativeMethods
    interface Natives {
        void init(ExtensionsManager manager);
        void destroy(long nativeExtensionsManager);
        void getInstalledExtensions(long nativeExtensionsManager);
        void setExtensionEnabled(
                long nativeExtensionsManager, String extensionId, boolean enabled);
        void uninstallExtension(long nativeExtensionsManager, String extensionId);
        void installFromPath(long nativeExtensionsManager, String absolutePath);
    }
}
