/*
 * Copyright (c) 2026, Shir o Khorshid contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;

/**
 * Shared UI helpers used by MainActivity and the tab fragments.
 *
 * <p>Both operations here were previously either missing or duplicated:
 * user-visible confirmation of an action, and copying a value to the
 * clipboard. Keeping them in one place means every confirmation looks the
 * same and every clipboard write is guarded the same way.
 */
public final class SkUi {

    private SkUi() {
    }

    /**
     * Show a short confirmation anchored to the activity's content area.
     */
    public static void showSnackbar(@Nullable Activity activity, @StringRes int messageRes) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        showSnackbar(activity.findViewById(android.R.id.content), messageRes);
    }

    /**
     * Show a short confirmation, preferring the content {@code CoordinatorLayout}
     * over whatever view triggered it.
     *
     * <p>Anchoring matters: a snackbar attached to {@code android.R.id.content}
     * is laid out over the bottom action bar and covers the Connect button,
     * which is the one control the user must never lose sight of. Resolving
     * {@code R.id.main_content} instead keeps the snackbar inside the tab
     * content area, and lets the CoordinatorLayout push the help FAB out of the
     * way for free.
     */
    public static void showSnackbar(@Nullable View view, @StringRes int messageRes) {
        if (view == null) {
            return;
        }
        View anchor = view.getRootView().findViewById(R.id.main_content);
        if (anchor == null) {
            anchor = view;
        }
        Context context = anchor.getContext();
        Snackbar snackbar = Snackbar.make(anchor, messageRes, Snackbar.LENGTH_SHORT);
        // Material's default snackbar is a light surface, which is jarring in a
        // dark-only app. Pin it to the palette instead.
        snackbar.setBackgroundTint(ContextCompat.getColor(context, R.color.sk_night_600));
        snackbar.setTextColor(ContextCompat.getColor(context, R.color.sk_text_primary));
        snackbar.setActionTextColor(ContextCompat.getColor(context, R.color.sk_gold));
        snackbar.show();
    }

    /**
     * Copy {@code value} to the clipboard and confirm it, doing nothing at all
     * when there is nothing to copy.
     */
    public static void copyToClipboard(@Nullable View sourceView, @StringRes int labelRes,
                                       @Nullable String value) {
        if (sourceView == null || TextUtils.isEmpty(value)) {
            return;
        }
        Context context = sourceView.getContext();
        ClipboardManager clipboard =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(labelRes), value));
        } catch (Exception ignored) {
            // A handful of OEM ROMs throw from setPrimaryClip (and Android 10+
            // rejects clipboard writes from a background window). A copy that
            // cannot happen must not take the app down with it.
            return;
        }
        // Android 13+ shows its own clipboard confirmation, so ours would be a
        // second box saying the same thing.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            showSnackbar(sourceView, R.string.sk_copied);
        }
    }
}
