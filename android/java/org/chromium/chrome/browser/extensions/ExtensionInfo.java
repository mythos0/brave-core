/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.extensions;

import org.jni_zero.CalledByNative;

/**
 * Immutable snapshot of an installed browser extension, marshalled from
 * extensions::Extension on the native side. Instances are created by
 * brave::android::ExtensionsManager (see
 * brave/browser/android/extensions/extensions_manager_android.cc) and are
 * consumed by the Android extensions UI (manager page, toolbar actions).
 */
public class ExtensionInfo {
    /** Stable extension ID (32 chars, 'a'-'p'). */
    public final String id;

    /** Display name from the extension manifest. */
    public final String name;

    /** Version string from the extension manifest, e.g. "1.84.2". */
    public final String version;

    /** Whether the extension is currently enabled in its profile. */
    public final boolean enabled;

    /**
     * One of extensions::Manifest::Location values (INTERNAL, UNPACKED,
     * COMPONENT, EXTERNAL_POLICY, ...). Useful for UI hints (e.g. hiding the
     * remove button for policy-installed extensions).
     */
    public final int location;

    @CalledByNative
    public ExtensionInfo(
            String id, String name, String version, boolean enabled, int location) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.enabled = enabled;
        this.location = location;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getLocation() {
        return location;
    }

    @Override
    public String toString() {
        return name + " " + version + " (" + id + ")";
    }
}
