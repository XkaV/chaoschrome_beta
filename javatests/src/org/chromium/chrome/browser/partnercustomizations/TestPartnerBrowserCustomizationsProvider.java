// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.partnercustomizations;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import org.chromium.chrome.test.util.TestHttpServerClient;

/**
 * PartnerBrowserCustomizationsProvider example for testing.
 * Note: if you move or rename this class, make sure you have also updated AndroidManifest.xml.
 */
public class TestPartnerBrowserCustomizationsProvider extends ContentProvider {
    protected String mTag = TestPartnerBrowserCustomizationsProvider.class.getSimpleName();

    public static final String HOMEPAGE_URI =
            TestHttpServerClient.getUrl("chrome/test/data/simple.html");
    public static final String INCOGNITO_MODE_DISABLED_KEY = "disableincognitomode";
    public static final String BOOKMARKS_EDITING_DISABLED_KEY = "disablebookmarksediting";

    protected static final int URI_MATCH_HOMEPAGE = 1001;
    protected static final int URI_MATCH_DISABLE_INCOGNITO_MODE = 1002;
    protected static final int URI_MATCH_DISABLE_BOOKMARKS_EDITING = 1003;
    protected static final UriMatcher sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        sUriMatcher.addURI(TestPartnerBrowserCustomizationsProvider.class.getName(),
                "homepage", URI_MATCH_HOMEPAGE);
        sUriMatcher.addURI(TestPartnerBrowserCustomizationsProvider.class.getName(),
                "disableincognitomode", URI_MATCH_DISABLE_INCOGNITO_MODE);
        sUriMatcher.addURI(TestPartnerBrowserCustomizationsProvider.class.getName(),
                "disablebookmarksediting", URI_MATCH_DISABLE_BOOKMARKS_EDITING);
    }

    private int mDisableIncognitoModeFlag = 1;
    private int mDisableBookmarksEditingFlag = 1;

    @Override
    public boolean onCreate() {
        return true;
    }

    private void setIncognitoModeDisabled(Bundle bundle) {
        mDisableIncognitoModeFlag =
                bundle.getBoolean(INCOGNITO_MODE_DISABLED_KEY, false) ? 1 : 0;
    }

    private void setBookmarksEditingDisabled(Bundle bundle) {
        mDisableBookmarksEditingFlag =
                bundle.getBoolean(BOOKMARKS_EDITING_DISABLED_KEY, false) ? 1 : 0;
    }

    @Override
    public String getType(Uri uri) {
        Log.d(mTag, "getType called: " + uri);

        switch (sUriMatcher.match(uri)) {
            case URI_MATCH_HOMEPAGE:
                return "vnd.android.cursor.item/partnerhomepage";
            case URI_MATCH_DISABLE_INCOGNITO_MODE:
                return "vnd.android.cursor.item/partnerdisableincognitomode";
            case URI_MATCH_DISABLE_BOOKMARKS_EDITING:
                return "vnd.android.cursor.item/partnerdisablebookmarksediting";
            default:
                return null;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        Log.d(mTag, "query called: " + uri);

        switch (sUriMatcher.match(uri)) {
            case URI_MATCH_HOMEPAGE:
            {
                MatrixCursor cursor = new MatrixCursor(new String[] {"homepage"}, 1);
                cursor.addRow(new Object[] {HOMEPAGE_URI});
                return cursor;
            }
            case URI_MATCH_DISABLE_INCOGNITO_MODE:
            {
                MatrixCursor cursor = new MatrixCursor(new String[] {"disableincognitomode"}, 1);
                cursor.addRow(new Object[] {mDisableIncognitoModeFlag});
                return cursor;
            }
            case URI_MATCH_DISABLE_BOOKMARKS_EDITING:
            {
                MatrixCursor cursor = new MatrixCursor(new String[] {"disablebookmarksediting"}, 1);
                cursor.addRow(new Object[] {mDisableBookmarksEditingFlag});
                return cursor;
            }
            default:
                return null;
        }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (TextUtils.equals(method, "setIncognitoModeDisabled")) {
            setIncognitoModeDisabled(extras);
        } else if (TextUtils.equals(method, "setBookmarksEditingDisabled")) {
            setBookmarksEditingDisabled(extras);
        }
        return super.call(method, arg, extras);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

}