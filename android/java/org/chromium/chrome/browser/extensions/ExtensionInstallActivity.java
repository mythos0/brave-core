/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.extensions;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import org.chromium.base.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Handles "install extension" requests coming from outside the browser:
 *
 * <ul>
 *   <li>ACTION_VIEW with {@code application/x-chrome-extension} (.crx) or
 *       {@code application/x-xpinstall} (.xpi) content, e.g. opened from a
 *       file manager or a download.
 *   <li>ACTION_VIEW of a Chrome Web Store or Firefox Add-ons (AMO) URL via
 *       {@link #EXTRA_INSTALL_URL}, used by in-browser install buttons.
 * </ul>
 *
 * The activity shows a confirmation dialog and, once the user accepts, hands
 * the file path to the native extensions installer through
 * {@link ExtensionsManager#installFromPath}. It is declared in Brave's
 * AndroidManifest fragment (//brave/android/java/AndroidManifest.xml).
 */
public class ExtensionInstallActivity extends Activity {
    private static final String TAG = "Extensions";

    /** Extra carrying an http(s) URL to install from (CWS detail page or AMO). */
    public static final String EXTRA_INSTALL_URL =
            "org.chromium.chrome.extensions.EXTRA_INSTALL_URL";

    /** Extra carrying an install source (one of ExtensionsManager.INSTALL_SOURCE_*). */
    public static final String EXTRA_INSTALL_SOURCE =
            "org.chromium.chrome.extensions.EXTRA_INSTALL_SOURCE";

    private static final int MAX_CRX_BYTES = 128 * 1024 * 1024;

    private AlertDialog mProgressDialog;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!ExtensionsManager.isSupported()) {
            Toast.makeText(
                            this,
                            "Extension support requires a desktop-android build "
                                    + "(enable_desktop_android_extensions).",
                            Toast.LENGTH_LONG)
                    .show();
            finish();
            return;
        }

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            finish();
            return;
        }

        String installUrl = intent.getStringExtra(EXTRA_INSTALL_URL);
        Uri data = intent.getData();

        if (data != null && ("http".equals(data.getScheme()) || "https".equals(data.getScheme()))) {
            handleWebUrl(data);
        } else if (installUrl != null) {
            handleWebUrl(Uri.parse(installUrl));
        } else if (data != null) {
            handleContentUri(data);
        } else {
            Log.w(TAG, "ExtensionInstallActivity started without a payload");
            finish();
        }
    }

    /** Chrome Web Store detail pages and AMO pages. */
    private void handleWebUrl(Uri uri) {
        if (ExtensionsManager.isChromeWebStoreUrl(uri)) {
            String extensionId = ExtensionsManager.extractCwsExtensionId(uri);
            if (extensionId == null) {
                showError("Could not determine the extension ID from the web store URL.");
                return;
            }
            confirmAndInstall(
                    /* title */ "Install from Chrome Web Store?",
                    "Extension ID: " + extensionId,
                    ExtensionsManager.buildCwsCrxDownloadUrl(
                            extensionId,
                            org.chromium.base.ChromeVersionInfo.getProductVersion()),
                    extensionId + ".crx",
                    ExtensionsManager.INSTALL_SOURCE_CHROME_WEB_STORE);
        } else if (ExtensionsManager.isMozillaAmoUrl(uri)) {
            // https://addons.mozilla.org/<locale>/firefox/addon/<slug>/
            String[] segments = uri.getPath() != null ? uri.getPath().split("/") : new String[0];
            String slug = null;
            for (int i = 0; i < segments.length - 1; i++) {
                if ("addon".equals(segments[i])) slug = segments[i + 1];
            }
            if (slug == null) {
                showError("Could not determine the add-on slug from the AMO URL.");
                return;
            }
            confirmAndInstall(
                    /* title */ "Install from Firefox Add-ons?",
                    "Add-on: " + slug + " (.xpi conversion lands in phase 2)",
                    ExtensionsManager.buildAmoXpiDownloadUrl(slug),
                    slug + ".xpi",
                    ExtensionsManager.INSTALL_SOURCE_MOZILLA_AMO);
        } else {
            showError("Unsupported install URL.");
        }
    }

    /** file:// or content:// payloads (.crx / .xpi from file managers). */
    private void handleContentUri(Uri uri) {
        String fileName = guessFileName(uri);
        File target = new File(getCacheDir(), "extension_installs/" + fileName);
        target.getParentFile().mkdirs();

        boolean copied;
        try (InputStream in = getContentResolver().openInputStream(uri);
                OutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IOException("Cannot open " + uri);
            byte[] buffer = new byte[16 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_CRX_BYTES) throw new IOException("Payload too large");
                out.write(buffer, 0, read);
            }
            copied = total > 0;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Failed to copy extension payload", e);
            showError("Could not read the extension file.");
            return;
        }

        if (!copied) {
            showError("The extension file is empty.");
            return;
        }

        String mime = getContentResolver().getType(uri);
        boolean isXpi = ExtensionsManager.MIME_XPI.equals(mime)
                || fileName.toLowerCase().endsWith(".xpi");

        confirmAndInstall(
                /* title */ isXpi ? "Install add-on (.xpi)?" : "Install extension (.crx)?",
                "File: " + fileName,
                /* url */ null,
                target.getAbsolutePath(),
                ExtensionsManager.INSTALL_SOURCE_SIDELOAD);
    }

    private void confirmAndInstall(
            String title, String detail, String url, String payload, int source) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setMessage(
                        detail
                                + "\n\nExtensions run with access to the pages you visit. "
                                + "Only install extensions you trust.")
                .setPositiveButton(
                        "Install",
                        (dialog, which) -> {
                            if (url != null) {
                                downloadAndInstall(url, payload, source);
                            } else {
                                ExtensionsManager.get().installFromPath(payload);
                                Toast.makeText(this, "Installing…", Toast.LENGTH_SHORT).show();
                                finish();
                            }
                        })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    private void downloadAndInstall(String url, String suggestedName, int source) {
        mProgressDialog =
                new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Downloading extension…")
                        .setMessage(url)
                        .setCancelable(false)
                        .show();

        ExtensionsManager.downloadToFile(
                this,
                url,
                suggestedName,
                (file) -> {
                    if (mProgressDialog != null) {
                        mProgressDialog.dismiss();
                        mProgressDialog = null;
                    }
                    if (file == null || !file.exists()) {
                        showError("Download failed. Try again or sideload a .crx file.");
                        return;
                    }
                    ExtensionsManager.get().installFromPath(file.getAbsolutePath());
                    Toast.makeText(this, "Installing…", Toast.LENGTH_SHORT).show();
                    finish();
                });
    }

    private void showError(String message) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Can't install extension")
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    private String guessFileName(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty()) return "extension.crx";
        name = name.substring(Math.max(0, name.lastIndexOf('/')));
        // content:// providers often hand back opaque tokens; make sure the
        // cached file carries a recognizable extension for the native side.
        String lower = name.toLowerCase();
        if (!lower.endsWith(".crx") && !lower.endsWith(".xpi")) {
            String mime = getContentResolver().getType(uri);
            name += ExtensionsManager.MIME_XPI.equals(mime) ? ".xpi" : ".crx";
        }
        return name;
    }
}
