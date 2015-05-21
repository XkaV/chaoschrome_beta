// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.document;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.AppTask;
import android.app.ActivityManager.RecentTaskInfo;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Browser;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import org.chromium.base.ApiCompatibilityUtils;
import org.chromium.base.ApplicationStatus;
import org.chromium.base.CommandLine;
import org.chromium.chrome.browser.BookmarkUtils;
import org.chromium.chrome.browser.ChromeMobileApplication;
import org.chromium.chrome.browser.ChromeSwitches;
import org.chromium.chrome.browser.ChromeTabbedActivity;
import org.chromium.chrome.browser.IntentHandler;
import org.chromium.chrome.browser.IntentHandler.TabOpenType;
import org.chromium.chrome.browser.ShortcutHelper;
import org.chromium.chrome.browser.Tab;
import org.chromium.chrome.browser.UrlConstants;
import org.chromium.chrome.browser.WarmupManager;
import org.chromium.chrome.browser.WebappAuthenticator;
import org.chromium.chrome.browser.firstrun.FirstRunFlowSequencer;
import org.chromium.chrome.browser.hosted.HostedActivity;
import org.chromium.chrome.browser.metrics.LaunchHistogram;
import org.chromium.chrome.browser.metrics.LaunchMetrics;
import org.chromium.chrome.browser.multiwindow.MultiWindowUtils;
import org.chromium.chrome.browser.notifications.NotificationUIManager;
import org.chromium.chrome.browser.partnercustomizations.HomepageManager;
import org.chromium.chrome.browser.partnercustomizations.PartnerBrowserCustomizations;
import org.chromium.chrome.browser.preferences.DocumentModeManager;
import org.chromium.chrome.browser.tabmodel.document.ActivityDelegate;
import org.chromium.chrome.browser.tabmodel.document.DocumentTabModel;
import org.chromium.chrome.browser.tabmodel.document.DocumentTabModelSelector;
import org.chromium.chrome.browser.util.FeatureUtilities;
import org.chromium.chrome.browser.util.IntentUtils;
import org.chromium.chrome.browser.webapps.WebappActivity;
import org.chromium.content.browser.crypto.CipherFactory;
import org.chromium.content_public.common.ScreenOrientationValues;
import org.chromium.ui.base.PageTransition;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * Dispatches incoming intents to the appropriate activity based on the current configuration and
 * Intent fired.
 */
public class ChromeLauncherActivity extends Activity
        implements IntentHandler.IntentHandlerDelegate {
    /**
     * Action fired when an Intent is trying to launch a WebappActivity.
     * Never change the package name or the Intents will fail to launch.
     */
    public static final String ACTION_START_WEBAPP =
            "com.google.android.apps.chrome.webapps.WebappManager.ACTION_START_WEBAPP";

    /**
     * Extra indicating that a Tab is trying to bring its WebappActivity to the foreground.
     * Never change the package name or the Intents will fail to launch.
     */
    public static final String EXTRA_BRING_WEBAPP_TO_FRONT =
            "com.google.android.apps.chrome.EXTRA_BRING_WEBAPP_TO_FRONT";

    /**
     * Extra indicating launch mode used.
     */
    public static final String EXTRA_LAUNCH_MODE =
            "com.google.android.apps.chrome.EXTRA_LAUNCH_MODE";

    /**
     * Action fired when the user selects the "Close all incognito tabs" notification.
     */
    static final String ACTION_CLOSE_ALL_INCOGNITO =
            "com.google.android.apps.chrome.document.CLOSE_ALL_INCOGNITO";

    private static final String TAG = "ChromeLauncherActivity";

    /** New instance should be launched in the foreground. */
    public static final int LAUNCH_MODE_FOREGROUND = 0;

    /** New instance should be launched as an affiliated task. */
    public static final int LAUNCH_MODE_AFFILIATED = 1;

    /** Existing instance should be retargetted, if possible. */
    public static final int LAUNCH_MODE_RETARGET = 2;

    private static final int FIRST_RUN_EXPERIENCE_REQUEST_CODE = 101;

    /**
     * Timeout in ms for reading PartnerBrowserCustomizations provider. We do not trust third party
     * provider by default.
     */
    private static final int PARTNER_BROWSER_CUSTOMIZATIONS_TIMEOUT_MS = 10000;

    /**
     * Maximum delay for initial document activity launch.
     */
    private static final int INITIAL_DOCUMENT_ACTIVITY_LAUNCH_TIMEOUT_MS = 500;

    private static final LaunchHistogram sMoveToFrontExceptionHistogram =
            new LaunchHistogram("DocumentActivity.MoveToFrontFailed");

    private IntentHandler mIntentHandler;
    private boolean mIsInMultiInstanceMode;
    private boolean mIsFinishNeeded;

    /** When started with an intent, maybe pre-resolve the domain. */
    private void maybePrefetchDnsInBackground() {
        if (getIntent() != null && Intent.ACTION_VIEW.equals(getIntent().getAction())) {
            String maybeUrl = IntentHandler.getUrlFromIntent(getIntent());
            if (maybeUrl != null) {
                WarmupManager.getInstance().maybePrefetchDnsForUrlInBackground(this, maybeUrl);
            }
        }
    }

    /**
     * Figure out how to route the Intent.  Because this is on the critical path to startup, please
     * avoid making the pathway any more complicated than it already is.  Make sure that anything
     * you add _absolutely has_ to be here.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize the command line in case we've disabled document mode from there.
        ((ChromeMobileApplication) getApplication()).initCommandLine();

        // Read partner browser customizations information asynchronously.
        // We want to initialize early because when there is no tabs to restore, we should possibly
        // show homepage, which might require reading PartnerBrowserCustomizations provider.
        PartnerBrowserCustomizations.initializeAsync(getApplicationContext(),
                PARTNER_BROWSER_CUSTOMIZATIONS_TIMEOUT_MS);

        mIsInMultiInstanceMode = MultiWindowUtils.getInstance().shouldRunInMultiInstanceMode(this);
        mIntentHandler = new IntentHandler(this, getPackageName());
        maybePerformMigrationTasks();

        if (handleHostedActivityIntent()) {
            finish();
            return;
        }

        // Check if we should launch a WebappActivity.
        if (IntentUtils.safeGetBooleanExtra(getIntent(), EXTRA_BRING_WEBAPP_TO_FRONT, false)
                || TextUtils.equals(getIntent().getAction(), ACTION_START_WEBAPP)) {
            Intent fallbackIntent = launchWebapp(getIntent());
            if (fallbackIntent == null) {
                ApiCompatibilityUtils.finishAndRemoveTask(this);
                return;
            } else {
                // Try to launch the URL as a regular VIEW intent.
                setIntent(fallbackIntent);
            }
        }

        // Check if we should launch the ChromeTabbedActivity.
        if (!FeatureUtilities.isDocumentMode(this)) {
            launchTabbedMode();
            finish();
            return;
        }

        // Check if we're just closing all of the Incognito tabs.
        if (TextUtils.equals(getIntent().getAction(), ACTION_CLOSE_ALL_INCOGNITO)) {
            ChromeMobileApplication.getDocumentTabModelSelector().getModel(true).closeAllTabs();
            ApiCompatibilityUtils.finishAndRemoveTask(this);
            return;
        }

        // The notification settings cog on the flipped side of Notifications and in the Android
        // Settings "App Notifications" view will open us with a specific category.
        if (getIntent().hasCategory(Notification.INTENT_CATEGORY_NOTIFICATION_PREFERENCES)) {
            NotificationUIManager.launchNotificationPreferences(this, getIntent());
            return;
        }

        // Check if we should launch the FirstRunActivity.  This occurs after the check to launch
        // ChromeTabbedActivity because ChromeTabbedActivity handles FRE in its own way.
        if (launchFirstRunExperience()) return;

        // Launch a DocumentActivity to handle the Intent.
        handleDocumentActivityIntent();
        if (!mIsFinishNeeded) ApiCompatibilityUtils.finishAndRemoveTask(this);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FIRST_RUN_EXPERIENCE_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                // User might have opted out during FRE, so check again.
                if (FeatureUtilities.isDocumentMode(this)) {
                    handleDocumentActivityIntent();
                    if (!mIsFinishNeeded) ApiCompatibilityUtils.finishAndRemoveTask(this);
                } else {
                    launchTabbedMode();
                    finish();
                }
                return;
            }

            // TODO(aruslan): FAIL.
            ApiCompatibilityUtils.finishAndRemoveTask(this);
        }
    }

    /**
     * If we have just opted in or opted out of document mode, perform pending migration tasks
     * such as cleaning up the recents.
     */
    private void maybePerformMigrationTasks() {
        if (DocumentModeManager.getInstance(this).isOptOutCleanUpPending()) {
            cleanUpChromeRecents(
                    DocumentModeManager.getInstance(this).isOptedOutOfDocumentMode());
            DocumentModeManager.getInstance(this).setOptOutCleanUpPending(false);
        }
    }

    @Override
    public void processWebSearchIntent(String query) {
        assert false;
    }

    @Override
    public void processUrlViewIntent(String url, String headers,
            IntentHandler.TabOpenType tabOpenType, String externalAppId,
            int tabIdToBringToFront, Intent intent) {
        assert false;
    }

    /**
     * Handles launching an hosted activity, which will sit on top of a client's activity in the
     * same task.
     * @return True if the intent is handled here.
     */
    private boolean handleHostedActivityIntent() {
        if (getIntent() == null) return false;

        boolean enabled = CommandLine.getInstance().hasSwitch(ChromeSwitches.ENABLE_EMBEDDED_MODE);
        boolean append = IntentUtils.safeGetBooleanExtra(
                getIntent(), IntentHandler.EXTRA_APPEND_TASK, false);
        if (!append || !enabled) return false;

        String url = IntentHandler.getUrlFromIntent(getIntent());
        if (url == null) return false;

        // Create and fire a launch intent. Use the copy constructor to carry over the myriad of
        // extras.
        Intent newIntent = new Intent(getIntent());
        newIntent.setAction(Intent.ACTION_VIEW);
        newIntent.setClassName(this, HostedActivity.class.getName());
        newIntent.setData(Uri.parse(url));
        startActivity(newIntent);
        return true;
    }

    /**
     * Handles the launching of a DocumentActivity from the current Intent.  Routing Intents to
     * other types of Activities must be handled from onCreate() instead.
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void handleDocumentActivityIntent() {
        if (getIntent() == null || mIntentHandler.shouldIgnoreIntent(this, getIntent())) {
            Log.e(TAG, "Ignoring intent: " + getIntent());
            mIsFinishNeeded = true;
            return;
        }

        maybePrefetchDnsInBackground();

        // Increment the Tab ID counter at this point since this Activity may not appear in
        // getAppTasks() when DocumentTabModelSelector is initialized.  This can potentially happen
        // when Chrome is launched via the GSA/e200 search box and they relinquish their task.
        Tab.incrementIdCounterTo(getTaskId() + 1);

        // Handle MAIN Intent actions, usually fired when the user starts Chrome via the launcher.
        // Some launchers start Chrome by firing a VIEW Intent with an empty URL (crbug.com/459349);
        // treat it as a MAIN Intent.
        String url = IntentHandler.getUrlFromIntent(getIntent());
        if ((url == null && TextUtils.equals(getIntent().getAction(), Intent.ACTION_VIEW))
                || TextUtils.equals(getIntent().getAction(), Intent.ACTION_MAIN)) {
            handleMainDocumentIntent();
            return;
        }

        // Sometimes an Intent requests that the current Document get clobbered.
        if (clobberCurrentDocument(url)) return;

        // Try to retarget existing Documents before creating a new one.
        boolean incognito = IntentUtils.safeGetBooleanExtra(getIntent(),
                IntentHandler.EXTRA_OPEN_NEW_INCOGNITO_TAB, false);
        boolean append = IntentUtils.safeGetBooleanExtra(
                getIntent(), IntentHandler.EXTRA_APPEND_TASK, false);
        boolean reuse = IntentUtils.safeGetBooleanExtra(
                getIntent(), BookmarkUtils.REUSE_URL_MATCHING_TAB_ELSE_NEW_TAB, false);
        boolean affiliated = IntentUtils.safeGetBooleanExtra(
                getIntent(), IntentHandler.EXTRA_OPEN_IN_BG, false);

        // Try to relaunch an existing task.
        if (reuse && !append) {
            LaunchMetrics.recordHomeScreenLaunchIntoTab(url);
            if (relaunchTask(incognito, url)) return;
        }

        // Create and fire a launch Intent to start a new Task.  The old Intent is copied using
        // the constructor so that we pass through the myriad extras that were set on it.
        Intent newIntent = createLaunchIntent(
                getApplicationContext(), getIntent(), url, incognito, Tab.INVALID_TAB_ID);
        setRecentsFlagsOnIntent(
                newIntent, append ? 0 : Intent.FLAG_ACTIVITY_NEW_DOCUMENT, incognito);
        fireDocumentIntent(this, newIntent, incognito, url, affiliated, null);
    }

    /**
     * Handles actions pertaining to Chrome being started with a MAIN Intent.  Typically, receiving
     * this Intent means that a user has selected the Chrome icon from their launcher, but it is
     * also used internally (e.g. when firing Intents back at Chrome via notifications).
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void handleMainDocumentIntent() {
        // Bring a specific tab back to the foreground.
        int tabId = IntentUtils.safeGetIntExtra(getIntent(),
                TabOpenType.BRING_TAB_TO_FRONT.name(), Tab.INVALID_TAB_ID);
        if (tabId != Tab.INVALID_TAB_ID && relaunchTask(tabId)) return;

        // Bring the last viewed tab to the foreground, unless we're in Samsung's multi-instance
        // mode -- a MAIN Intent in that case results in the creation of a second default page.
        if (!mIsInMultiInstanceMode && launchLastViewedActivity()) return;

        // Launch the default page asynchronously because the homepage URL needs to be queried.
        // This is obviously not ideal, but we don't have a choice.
        mIsFinishNeeded = mIsInMultiInstanceMode;
        PartnerBrowserCustomizations.setOnInitializeAsyncFinished(new Runnable() {
            @Override
            public void run() {
                String url = HomepageManager.getHomepageUri(ChromeLauncherActivity.this);
                if (TextUtils.isEmpty(url)) url = UrlConstants.NTP_URL;

                int mode = mIsInMultiInstanceMode ? LAUNCH_MODE_FOREGROUND : LAUNCH_MODE_RETARGET;
                launchDocumentInstance(ChromeLauncherActivity.this, false, mode, url,
                        DocumentMetricIds.STARTED_BY_LAUNCHER, PageTransition.AUTO_TOPLEVEL, false,
                        null);

                if (mIsFinishNeeded) finish();
            }
        }, INITIAL_DOCUMENT_ACTIVITY_LAUNCH_TIMEOUT_MS);
    }

    /**
     * If necessary, attempts to clobber the current DocumentActivity's tab with the given URL.
     * @param url URL to display.
     * @return Whether or not the clobber was successful.
     */
    private boolean clobberCurrentDocument(String url) {
        boolean shouldOpenNewTab = IntentUtils.safeGetBooleanExtra(
                getIntent(), Browser.EXTRA_CREATE_NEW_TAB, false);
        String applicationId =
                IntentUtils.safeGetStringExtra(getIntent(), Browser.EXTRA_APPLICATION_ID);
        if (shouldOpenNewTab || !getPackageName().equals(applicationId)) return false;

        // Check if there's a Tab that can be clobbered.
        int tabId = ChromeMobileApplication.getDocumentTabModelSelector().getCurrentTabId();
        if (tabId == Tab.INVALID_TAB_ID) return false;

        // Try to clobber the page.
        PendingDocumentData data = new PendingDocumentData();
        data.url = url;
        data.originalIntent = new Intent(getIntent());
        ChromeMobileApplication.getDocumentTabModelSelector().addPendingDocumentData(tabId, data);
        if (!relaunchTask(tabId)) {
            // Were not able to clobber, will fall through to handle in a new document.
            ChromeMobileApplication.getDocumentTabModelSelector().removePendingDocumentData(tabId);
            return false;
        }

        return true;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private boolean launchLastViewedActivity() {
        int tabId = ChromeMobileApplication.getDocumentTabModelSelector().getCurrentTabId();
        DocumentTabModel model =
                ChromeMobileApplication.getDocumentTabModelSelector().getModelForTabId(tabId);
        if (tabId != Tab.INVALID_TAB_ID && model != null && !model.isCoveredByChildActivity(tabId)
                && relaunchTask(tabId)) {
            return true;
        }

        // Everything above failed, try to launch the last viewed activity based on app tasks list.
        ActivityManager am = (ActivityManager) getSystemService(Activity.ACTIVITY_SERVICE);
        PackageManager pm = getPackageManager();
        for (AppTask task : am.getAppTasks()) {
            String className = DocumentUtils.getTaskClassName(task, pm);
            if (className == null || !DocumentActivity.isDocumentActivity(className)) continue;

            int id = ActivityDelegate.getTabIdFromIntent(task.getTaskInfo().baseIntent);
            model = ChromeMobileApplication.getDocumentTabModelSelector().getModelForTabId(id);
            if (model != null && model.isCoveredByChildActivity(id)) continue;

            if (!moveToFront(task)) continue;
            return true;
        }
        return false;
    }

    /**
     * Starts a Document for the given URL.
     *
     * NOTE: this method adds trusted intent extra to authenticate that Chrome set the
     * EXTRA_PAGE_TRANSITION_TYPE extra which we only want Chrome to do.
     * This should never be exposed to non-Chrome callers.
     * @param activity Activity launching the new instance. May be null.
     * @param incognito Whether the created document should be incognito.
     * @param launchMode See LAUNCH_MODE_* above.
     * @param url URL to load.
     * @param intentSource What is causing the Intent to be fired.
     *         See DocumentUma.DOCUMENT_ACTIVITY_STARTED_BY_
     * @param pageTransitionType The page transition we will do on loading the given URL.
     * @param useDesktopUserAgent Whether to use a desktop user agent.
     * @param pendingUrlParams PendingUrlParams to store internally and use later once an intent is
     *                         received to launch the URL. May be null.
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static void launchDocumentInstance(Activity activity, boolean incognito, int launchMode,
            String url, int intentSource, int pageTransitionType,
            boolean useDesktopUserAgent, PendingDocumentData pendingUrlParams) {
        // If we weren't given an initial URL, check the pending parameters.
        if (url == null && pendingUrlParams != null) {
            if (pendingUrlParams.url != null) {
                url = pendingUrlParams.url;
            } else if (pendingUrlParams.webContents != null) {
                url = pendingUrlParams.webContents.getUrl();
            }
        }

        // Try to retarget an existing task.  Make sure there is no pending data to go with the load
        // because relaunching an Activity won't send the parameters over.
        if (launchMode == LAUNCH_MODE_RETARGET) {
            assert pendingUrlParams == null;
            if (relaunchTask(incognito, url)) return;
        }

        // If the new tab is spawned by another tab, record the parent.
        int parentId = activity != null && (launchMode == LAUNCH_MODE_AFFILIATED
                || intentSource == DocumentMetricIds.STARTED_BY_WINDOW_OPEN
                || intentSource == DocumentMetricIds.STARTED_BY_CONTEXTUAL_SEARCH)
                ? ActivityDelegate.getTabIdFromIntent(activity.getIntent())
                : Tab.INVALID_TAB_ID;

        // Fire an Intent to start a DocumentActivity instance.
        Context context = ApplicationStatus.getApplicationContext();
        Intent intent = createLaunchIntent(context, null, url, incognito, parentId);
        setRecentsFlagsOnIntent(intent, Intent.FLAG_ACTIVITY_NEW_DOCUMENT, incognito);
        intent.putExtra(IntentHandler.EXTRA_OPEN_NEW_INCOGNITO_TAB, incognito);
        intent.putExtra(IntentHandler.EXTRA_PAGE_TRANSITION_TYPE, pageTransitionType);
        intent.putExtra(IntentHandler.EXTRA_STARTED_BY, intentSource);
        intent.putExtra(IntentHandler.EXTRA_USE_DESKTOP_USER_AGENT, useDesktopUserAgent);
        intent.putExtra(EXTRA_LAUNCH_MODE, launchMode);
        IntentHandler.addTrustedIntentExtras(intent, context);

        boolean affiliated = launchMode == LAUNCH_MODE_AFFILIATED;
        if (activity == null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            fireDocumentIntent(context, intent, incognito, url, affiliated, pendingUrlParams);
        } else {
            fireDocumentIntent(activity, intent, incognito, url, affiliated, pendingUrlParams);
        }
    }

    /**
     * Starts the document activity specified by the intent and options. Potentially first runs
     * {@link CipherKeyActivity} in order to restore cipher keys.
     *
     * Note that Android has a mechanism for retargeting existing tasks via Intents, which involves
     * firing an Intent to the same class with the same URI data.  Firing an Intent via this method
     * may therefore _not_ create a new DocumentActivity instance.
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static void fireDocumentIntent(Context context, Intent intent, boolean incognito,
            String url, boolean affiliated, PendingDocumentData pendingUrlParams) {
        assert url != null;
        assert incognito || TextUtils.equals(IntentHandler.getUrlFromIntent(intent), url);
        assert !affiliated || !incognito;

        // Remove any flags from the Intent that would prevent a second instance of Chrome from
        // appearing.
        if (context instanceof ChromeLauncherActivity
                && ((ChromeLauncherActivity) context).mIsInMultiInstanceMode) {
            MultiWindowUtils.getInstance().makeMultiInstanceIntent((ChromeLauncherActivity) context,
                    intent);
        }

        // Incognito URLs are not passed through the Intent for privacy reasons.  Instead, store it
        // as a parameter that gets retrieved when the IncognitoDocumentActivity starts.
        if (incognito) {
            if (pendingUrlParams == null) pendingUrlParams = new PendingDocumentData();
            assert pendingUrlParams.url == null;
            pendingUrlParams.url = url;
        }

        // Store parameters for the new DocumentActivity, which are retrieved immediately after the
        // new Activity starts.  This structure is used to avoid passing things like pointers to
        // native WebContents in the Intent, which are strictly under Android's control and is
        // re-delivered when a Chrome Activity is restarted.
        boolean isWebContentsPending = false;
        if (pendingUrlParams != null) {
            int tabId = ActivityDelegate.getTabIdFromIntent(intent);
            ChromeMobileApplication.getDocumentTabModelSelector().addPendingDocumentData(
                    tabId, pendingUrlParams);
            isWebContentsPending = pendingUrlParams.webContents != null;
        }

        Bundle options = affiliated && !isWebContentsPending
                ? ActivityOptions.makeTaskLaunchBehind().toBundle() : null;
        if (incognito && !CipherFactory.getInstance().hasCipher()
                && ChromeMobileApplication.getDocumentTabModelSelector().getModel(true)
                        .getCount() > 0) {
            // The CipherKeyActivity needs to be run to restore the Incognito decryption key.
            Intent cipherIntent = CipherKeyActivity.createIntent(context, intent, options);
            context.startActivity(cipherIntent);
        } else {
            context.startActivity(intent, options);
        }
    }

    /**
     * Get an intent that will close all incognito tabs through {@link ChromeLauncherActivity}.
     * @param context The context to use for creating the {@link PendingIntent}.
     * @return {@link PendingIntent} to use for closing all incognito tabs.
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static PendingIntent getRemoveAllIncognitoTabsIntent(Context context) {
        Intent intent = new Intent(
                ACTION_CLOSE_ALL_INCOGNITO, null, context, ChromeLauncherActivity.class);
        return PendingIntent.getActivity(context, 0, intent, 0);
    }

    static String getDocumentClassName(boolean isIncognito) {
        return isIncognito ? IncognitoDocumentActivity.class.getName() :
                DocumentActivity.class.getName();
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static Intent createLaunchIntent(
            Context context, Intent oldIntent, String url, boolean incognito, int parentId) {
        int newTabId = ChromeMobileApplication.getDocumentTabModelSelector().generateValidTabId();

        // Copy the old Intent so that the extras carry over.
        Intent intent = oldIntent == null ? new Intent() : new Intent(oldIntent);
        intent.setAction(Intent.ACTION_VIEW);
        intent.setClassName(context, getDocumentClassName(incognito));

        if (incognito) {
            // Incognito Intents don't pass URLs in their data.
            intent.setData(DocumentTabModelSelector.createDocumentDataString(newTabId, ""));
        } else {
            intent.setData(DocumentTabModelSelector.createDocumentDataString(newTabId, url));
        }

        // For content URIs, because intent.getData().getScheme() begins with "document://,
        // we need to pass a ClipData so DocumentActivity can access the content.
        if (url != null && url.startsWith("content://")) {
            intent.setClipData(ClipData.newUri(
                    context.getContentResolver(), "content", Uri.parse(url)));
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        intent.putExtra(IntentHandler.EXTRA_PARENT_TAB_ID, parentId);
        if (oldIntent != null && Intent.ACTION_VIEW.equals(oldIntent.getAction())) {
            intent.putExtra(IntentHandler.EXTRA_ORIGINAL_INTENT, oldIntent);
        }

        return intent;
    }

    @SuppressLint("InlinedApi")
    private void launchTabbedMode() {
        maybePrefetchDnsInBackground();

        Intent newIntent = new Intent(getIntent());
        newIntent.setClassName(getApplicationContext().getPackageName(),
                ChromeTabbedActivity.class.getName());
        newIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            newIntent.addFlags(Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS);
        }
        Uri uri = newIntent.getData();
        if (uri != null && "content".equals(uri.getScheme())) {
            newIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        if (mIsInMultiInstanceMode) {
            MultiWindowUtils.getInstance().makeMultiInstanceIntent(this, newIntent);
        }
        startActivity(newIntent);
    }

    /**
     * Bring the task matching the given tab ID to the front.
     * @param tabId tab ID to search for.
     * @return Whether the task was successfully brought back.
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static boolean relaunchTask(int tabId) {
        if (tabId == Tab.INVALID_TAB_ID) return false;

        Context context = ApplicationStatus.getApplicationContext();
        ActivityManager manager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (AppTask task : manager.getAppTasks()) {
            RecentTaskInfo info = DocumentUtils.getTaskInfoFromTask(task);
            if (info == null) continue;

            int id = ActivityDelegate.getTabIdFromIntent(info.baseIntent);
            if (id != tabId) continue;

            DocumentTabModelSelector.setPrioritizedTabId(id);
            if (!moveToFront(task)) continue;

            return true;
        }

        return false;
    }

    /**
     * Bring the task matching the given URL to the front if the task is retargetable.
     * @param incognito Whether or not the tab is incognito.
     * @param url URL that the tab would have been created for. If null, this param is ignored.
     * @return Whether the task was successfully brought back.
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static boolean relaunchTask(boolean incognito, String url) {
        if (TextUtils.isEmpty(url)) return false;

        Context context = ApplicationStatus.getApplicationContext();
        ActivityManager manager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (AppTask task : manager.getAppTasks()) {
            RecentTaskInfo info = DocumentUtils.getTaskInfoFromTask(task);
            if (info == null) continue;

            String initialUrl = ActivityDelegate.getInitialUrlForDocument(info.baseIntent);
            if (TextUtils.isEmpty(initialUrl) || !TextUtils.equals(initialUrl, url)) continue;

            int id = ActivityDelegate.getTabIdFromIntent(info.baseIntent);
            DocumentTabModelSelector.setPrioritizedTabId(id);
            if (!ChromeMobileApplication.getDocumentTabModelSelector().getModel(incognito)
                    .isRetargetable(id)) {
                continue;
            }

            if (!moveToFront(task)) continue;
            return true;
        }

        return false;
    }

    /**
     * On opting out, remove all the old tasks from the recents.
     * @param fromDocument Whether any possible migration was from document mode to classic.
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void cleanUpChromeRecents(boolean fromDocument) {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.AppTask> taskList = am.getAppTasks();
        PackageManager pm = getPackageManager();
        for (int i = 0; i < taskList.size(); i++) {
            AppTask task = taskList.get(i);
            String className = DocumentUtils.getTaskClassName(task, pm);
            if (className == null) continue;

            RecentTaskInfo taskInfo = DocumentUtils.getTaskInfoFromTask(task);
            if (taskInfo == null) continue;

            // Skip the document activities if we are migrating from classic to document.
            boolean skip = !fromDocument && DocumentActivity.isDocumentActivity(className);
            if (!skip && (taskInfo.id != getTaskId())) {
                taskList.get(i).finishAndRemoveTask();
            }
        }
    }

    /**
     * Set flags that ensure that we control when our Activities disappear from Recents.
     * @param intent Intent to set the flags on.
     * @param extraFlags Other flags to add to the Intent, 0 if there's nothing to add.
     * @param incognito Whether we are launching an incognito document.
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static void setRecentsFlagsOnIntent(Intent intent, int extraFlags, boolean incognito) {
        intent.setFlags(intent.getFlags() & ~Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        if (!incognito) intent.addFlags(Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS);
        if (extraFlags != 0) intent.addFlags(extraFlags);
    }

    /**
     * @return Whether there is already an browser instance of Chrome already running.
     */
    public boolean isChromeBrowserActivityRunning() {
        for (WeakReference<Activity> reference : ApplicationStatus.getRunningActivities()) {
            Activity activity = reference.get();
            if (activity == null) continue;

            String className = activity.getClass().getName();
            if (DocumentActivity.isDocumentActivity(className)
                    || TextUtils.equals(className, ChromeTabbedActivity.class.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Attempt to move a task back to the front.  This can FAIL for some reason because the UID
     * of the DocumentActivity we try to bring back to the front doesn't match the
     * ChromeLauncherActivities.
     * @param task Task to attempt to bring back to the foreground.
     * @return Whether or not this succeeded.
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static boolean moveToFront(AppTask task) {
        try {
            task.moveToFront();
            return true;
        } catch (SecurityException e) {
            sMoveToFrontExceptionHistogram.recordHit();
        }
        return false;
    }

    /**
     * Tries to launch a WebappActivity for the given Intent.
     * @return Intent to fire if the webapp Intent failed to launch because of security checks,
     *         null otherwise.
     */
    private Intent launchWebapp(Intent intent) {
        String webappId = IntentUtils.safeGetStringExtra(intent, ShortcutHelper.EXTRA_ID);
        String webappUrl = IntentUtils.safeGetStringExtra(intent, ShortcutHelper.EXTRA_URL);
        String webappTitle = IntentUtils.safeGetStringExtra(intent, ShortcutHelper.EXTRA_TITLE);
        String webappIcon = IntentUtils.safeGetStringExtra(intent, ShortcutHelper.EXTRA_ICON);
        int webappOrientation = IntentUtils.safeGetIntExtra(intent,
                ShortcutHelper.EXTRA_ORIENTATION, ScreenOrientationValues.DEFAULT);

        if (webappId != null && webappUrl != null) {
            String webappMacString = IntentUtils.safeGetStringExtra(
                    intent, ShortcutHelper.EXTRA_MAC);
            byte[] webappMac =
                    webappMacString == null ? null : Base64.decode(webappMacString, Base64.DEFAULT);

            if (webappMac != null && WebappAuthenticator.isUrlValid(this, webappUrl, webappMac)) {
                if (TextUtils.equals(ACTION_START_WEBAPP, intent.getAction())) {
                    LaunchMetrics.recordHomeScreenLaunchIntoStandaloneActivity(webappUrl);
                }

                WebappActivity.launchInstance(
                        this, webappId, webappUrl, webappIcon, webappTitle, webappOrientation);
            } else {
                Log.e(TAG, "Shortcut (" + webappUrl + ") opened in Chrome.");

                // Tried and failed.  Change the intent action and try the URL with a VIEW Intent.
                Intent fallbackIntent = new Intent(intent);
                fallbackIntent.setAction(Intent.ACTION_VIEW);
                fallbackIntent.setData(Uri.parse(webappUrl));
                fallbackIntent.putExtra(BookmarkUtils.REUSE_URL_MATCHING_TAB_ELSE_NEW_TAB, true);
                return fallbackIntent;
            }
        }
        return null;
    }

    /**
     * Tries to launch the First Run Experience.  If ChromeLauncherActivity is running with the
     * wrong Intent flags, we instead relaunch ChromeLauncherActivity to make sure it runs in its
     * own task, which then triggers First Run.
     * @return Whether or not the First Run Experience needed to be shown.
     */
    private boolean launchFirstRunExperience() {
        final boolean isIntentActionMain = getIntent() != null
                && TextUtils.equals(getIntent().getAction(), Intent.ACTION_MAIN);
        final Intent freIntent = FirstRunFlowSequencer.checkIfFirstRunIsNecessary(
                this, getIntent(), isIntentActionMain);
        if (freIntent == null) return false;

        if ((getIntent().getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0) {
            startActivityForResult(freIntent, FIRST_RUN_EXPERIENCE_REQUEST_CODE);
        } else {
            Intent newIntent = new Intent(getIntent());
            newIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(newIntent);
            finish();
        }
        return true;
    }

    /**
     * Send the number of times an exception was caught when trying to move a task back to front.
     */
    public static void sendExceptionCount() {
        sMoveToFrontExceptionHistogram.commitHistogram();
    }
}