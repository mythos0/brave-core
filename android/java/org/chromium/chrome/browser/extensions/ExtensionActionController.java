/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.extensions;

import android.app.Activity;

import org.chromium.base.Callback;

import java.util.ArrayList;
import java.util.List;

/**
 * Glue between the Brave Android toolbar/menu and extension browser actions
 * (MV2 page actions & browser actions, MV3 action buttons).
 *
 * <p>Phase 4 of the Android-extensions roadmap wires this controller into
 * Brave's menu adapter: call {@link #getActionsForCurrentTab} while building
 * the toolbar menu and route taps through {@link #onActionSelected}. Popup
 * rendering (a real anchor popup with the extension's WebContents) lands in
 * that phase; until then clicking an action opens the extension's popup page
 * in the extensions page flow.
 */
public class ExtensionActionController {
    /** Delivers the actions that should be surfaced for the current tab. */
    public interface ActionsCallback {
        void onActionsReady(List<ExtensionInfo> actions);
    }

    private ExtensionActionController() {}

    /**
     * Loads installed & enabled extensions. Per-tab declarative visibility
     * (pageAction conditions, activeTab grants) is resolved natively in phase
     * 4; this scaffold returns all enabled extensions so the UI can be built
     * and tested end-to-end early.
     */
    public static void getActionsForCurrentTab(
            ExtensionsManager manager, ActionsCallback callback) {
        manager.loadInstalledExtensions(
                (extensions) -> {
                    List<ExtensionInfo> actions = new ArrayList<>();
                    for (ExtensionInfo info : extensions) {
                        if (info.isEnabled()) actions.add(info);
                    }
                    callback.onActionsReady(actions);
                });
    }

    /**
     * Handles a tap on an extension's toolbar action. Falls back to opening
     * the extensions management page when the extension does not expose a
     * popup.
     */
    public static void onActionSelected(Activity activity, ExtensionInfo extension) {
        ExtensionsManager manager = ExtensionsManager.get();
        // Phase 4: native ExtensionActionManager resolves the popup URL and
        // Brave shows it in an anchored dialog WebContents.
        manager.openExtensionsPage(activity);
    }
}
