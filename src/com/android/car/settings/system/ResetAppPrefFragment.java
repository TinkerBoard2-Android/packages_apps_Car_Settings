/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.car.settings.system;

import static java.util.Objects.requireNonNull;

import android.app.AppOpsManager;
import android.app.INotificationManager;
import android.car.user.CarUserManagerHelper;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.webkit.IWebViewUpdateService;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.StringRes;
import androidx.car.widget.ListItem;
import androidx.car.widget.ListItemProvider;
import androidx.car.widget.TextListItem;

import com.android.car.settings.R;
import com.android.car.settings.common.ListItemSettingsFragment;
import com.android.car.settings.common.Logger;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Presents the user with information about resetting app preferences.
 */
public class ResetAppPrefFragment extends ListItemSettingsFragment {

    private static final Logger LOG = new Logger(ResetAppPrefFragment.class);

    /**
     * Creates new instance of {@link ResetAppPrefFragment}.
     */
    public static ResetAppPrefFragment newInstance() {
        ResetAppPrefFragment fragment = new ResetAppPrefFragment();
        Bundle bundle = ListItemSettingsFragment.getBundle();
        bundle.putInt(EXTRA_TITLE_ID, R.string.reset_app_pref_title);
        bundle.putInt(EXTRA_ACTION_BAR_LAYOUT, R.layout.action_bar_with_button);
        fragment.setArguments(bundle);
        return fragment;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        Button resetAppsButton = requireNonNull(getActivity()).findViewById(R.id.action_button1);
        resetAppsButton.setText(requireContext().getString(R.string.reset_app_pref_button_text));
        resetAppsButton.setOnClickListener(v -> resetAppPreferences());
    }

    @Override
    public ListItemProvider getItemProvider() {
        return new ListItemProvider.ListProvider(getListItems());
    }

    private List<ListItem> getListItems() {
        List<ListItem> items = new ArrayList<>();
        items.add(createTextOnlyItem(R.string.reset_app_pref_desc));
        items.add(createTextOnlyItem(R.string.reset_app_pref_items));
        items.add(createTextOnlyItem(R.string.reset_app_pref_desc_data));
        return items;
    }

    private TextListItem createTextOnlyItem(@StringRes int stringResId) {
        Context context = requireContext();
        TextListItem item = new TextListItem(context);
        item.setBody(context.getString(stringResId), /* asPrimary= */ true);
        item.setHideDivider(true);
        return item;
    }

    private void resetAppPreferences() {
        new ResetTask(requireContext().getApplicationContext()).execute();
    }

    private static class ResetTask extends AsyncTask<Void, Void, Void> {

        private final WeakReference<Context> mContext;

        ResetTask(Context context) {
            mContext = new WeakReference<>(context);
        }

        @Override
        protected Void doInBackground(Void... unused) {
            Context context = mContext.get();
            if (context == null) {
                LOG.w("Unable to reset app preferences. Null context");
                return null;
            }
            PackageManager packageManager = context.getPackageManager();
            IBinder notificationManagerServiceBinder = ServiceManager.getService(
                    Context.NOTIFICATION_SERVICE);
            if (notificationManagerServiceBinder == null) {
                LOG.w("Unable to reset app preferences. Null notification manager service");
                return null;
            }
            INotificationManager notificationManagerService =
                    INotificationManager.Stub.asInterface(notificationManagerServiceBinder);
            IBinder webViewUpdateServiceBinder = ServiceManager.getService("webviewupdate");
            if (webViewUpdateServiceBinder == null) {
                LOG.w("Unable to reset app preferences. Null web view update service");
                return null;
            }
            IWebViewUpdateService webViewUpdateService = IWebViewUpdateService.Stub.asInterface(
                    webViewUpdateServiceBinder);

            // Reset app notifications.
            // Reset disabled apps.
            List<ApplicationInfo> apps = packageManager.getInstalledApplications(
                    PackageManager.MATCH_DISABLED_COMPONENTS);
            for (ApplicationInfo app : apps) {
                try {
                    notificationManagerService.setNotificationsEnabledForPackage(
                            app.packageName,
                            app.uid, true);
                } catch (RemoteException e) {
                    LOG.w("Unable to reset notification preferences for app: " + app.name, e);
                }
                if (!app.enabled) {
                    if (packageManager.getApplicationEnabledSetting(app.packageName)
                            == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                            && !isNonEnableableFallback(webViewUpdateService,
                            app.packageName)) {
                        packageManager.setApplicationEnabledSetting(app.packageName,
                                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                                PackageManager.DONT_KILL_APP);
                    }
                }
            }

            // Reset default applications for actions.
            // Reset background data restrictions for apps.
            // Reset permission restrictions.
            try {
                IBinder packageManagerServiceBinder = ServiceManager.getService("package");
                if (packageManagerServiceBinder == null) {
                    LOG.w("Unable to reset app preferences. Null package manager service");
                    return null;
                }
                IPackageManager.Stub.asInterface(
                        packageManagerServiceBinder).resetApplicationPreferences(
                        new CarUserManagerHelper(context).getCurrentForegroundUserId());
            } catch (RemoteException e) {
                LOG.w("Unable to reset app preferences", e);
            }

            // Cleanup.
            ((AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE)).resetAllModes();

            return null;
        }

        private boolean isNonEnableableFallback(IWebViewUpdateService mWvus, String packageName) {
            try {
                return mWvus.isFallbackPackage(packageName);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        protected void onPostExecute(Void unused) {
            super.onPostExecute(unused);
            Context context = mContext.get();
            if (context != null) {
                Toast.makeText(context, R.string.reset_app_pref_complete_toast,
                        Toast.LENGTH_SHORT).show();
            }
        }
    }
}