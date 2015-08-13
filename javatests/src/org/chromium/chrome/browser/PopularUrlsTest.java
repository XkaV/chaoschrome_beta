// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser;

import static org.chromium.base.test.util.ScalableTimeout.scaleTimeout;

import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;

import org.chromium.base.annotations.SuppressFBWarnings;
import org.chromium.base.test.util.Manual;
import org.chromium.chrome.browser.tab.EmptyTabObserver;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.test.ChromeActivityTestCaseBase;
import org.chromium.content.browser.test.util.CallbackHelper;
import org.chromium.content_public.browser.LoadUrlParams;
import org.chromium.ui.base.PageTransition;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Popular URL tests (ported from {@link com.android.browser.PopularUrlsTest}).
 * <p>
 * These tests read popular URLs from /sdcard/popular_urls.txt, open them one by one and verify
 * page load. When aborted, they save the last opened URL in /sdcard/test_status.txt, so that they
 * can continue opening the next URL when they are restarted.
 */
public class PopularUrlsTest extends ChromeActivityTestCaseBase<ChromeActivity> {

    private static final String TAG = "PopularUrlsTest";
    private static final String NEW_LINE = System.getProperty("line.separator");
    private static final File INPUT_FILE =
            new File(Environment.getExternalStorageDirectory(), "popular_urls.txt");
    private static final File OUTPUT_FILE =
            new File(Environment.getExternalStorageDirectory(), "test_output.txt");
    private static final File STATUS_FILE =
            new File(Environment.getExternalStorageDirectory(), "test_status.txt");
    private static final File FAILURE_FILE =
            new File(Environment.getExternalStorageDirectory(), "failures.txt");
    private static final File WAIT_FLAG_FILE =
            new File(Environment.getExternalStorageDirectory(), "url-test-short-wait");
    private static final int PERF_LOOPCOUNT = 10;
    private static final int STABILITY_LOOPCOUNT = 1;
    private static final int SHORT_WAIT_TIMEOUT = 1000;

    private RunStatus mStatus;
    private boolean mFailed;
    private boolean mDoShortWait;

    public PopularUrlsTest() {
        super(ChromeActivity.class);
    }

    @Override
    protected void setUp() throws Exception {
        mSkipCheckHttpServer = true;
        mStatus = new RunStatus(STATUS_FILE);
        mFailed = false;
        mDoShortWait = checkDoShortWait();
        super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        if (mStatus != null) {
            mStatus.cleanUp();
        }
        super.tearDown();
    }

    @Override
    public void startMainActivity() throws InterruptedException {
        startMainActivityFromLauncher();
    }

    private BufferedReader getInputStream(File inputFile) throws FileNotFoundException {
        FileReader fileReader = new FileReader(inputFile);
        BufferedReader bufferedReader = new BufferedReader(fileReader);

        return bufferedReader;
    }

    private OutputStreamWriter getOutputStream(File outputFile) throws IOException {
        return new FileWriter(outputFile, mStatus.getIsRecovery());
    }

    private void logToStream(String str, OutputStreamWriter writer) throws IOException {
        if (writer != null) {
            writer.write(str);
            writer.flush();
        }
    }

    private boolean checkDoShortWait() {
        return WAIT_FLAG_FILE.isFile() && WAIT_FLAG_FILE.exists();
    }

    private static class RunStatus {
        private File mFile;
        private int mIteration;
        private int mPage;
        private String mUrl;
        private boolean mIsRecovery;
        private boolean mAllClear;

        public RunStatus(File file) throws IOException {
            mFile = file;
            FileReader input = null;
            BufferedReader reader = null;
            mIsRecovery = false;
            mAllClear = false;
            mIteration = 0;
            mPage = 0;
            try {
                input = new FileReader(mFile);
                mIsRecovery = true;
                reader = new BufferedReader(input);
                String line = reader.readLine();
                if (line == null) {
                    return;
                }
                mIteration = Integer.parseInt(line);
                line = reader.readLine();
                if (line == null) {
                    return;
                }
                mPage = Integer.parseInt(line);
            } catch (FileNotFoundException ex) {
                return;
            } catch (NumberFormatException nfe) {
                Log.wtf(TAG, "Unexpected data in status file. Will run for all URLs.");
                return;
            } finally {
                try {
                    if (reader != null) {
                        reader.close();
                    }
                } finally {
                    if (input != null) {
                        input.close();
                    }
                }
            }
        }

        @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_BAD_PRACTICE")
        public void write() throws IOException {
            FileWriter output = null;
            if (mFile.exists()) {
                mFile.delete();
            }
            try {
                output = new FileWriter(mFile);
                output.write(mIteration + NEW_LINE);
                output.write(mPage + NEW_LINE);
                output.write(mUrl + NEW_LINE);
            } finally {
                if (output != null) {
                    output.close();
                }
            }
        }

        @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_BAD_PRACTICE")
        public void cleanUp() {
            // Only perform cleanup when mAllClear flag is set, i.e.
            // when the test was not interrupted by a Java crash.
            if (mFile.exists() && mAllClear) {
                mFile.delete();
            }
        }

        public void resetPage() {
            mPage = 0;
        }

        public void incrementPage() {
            ++mPage;
            mAllClear = true;
        }

        public void incrementIteration() {
            ++mIteration;
        }

        public int getPage() {
            return mPage;
        }

        public int getIteration() {
            return mIteration;
        }

        public boolean getIsRecovery() {
            return mIsRecovery;
        }

        public void setUrl(String url) {
            mUrl = url;
            mAllClear = false;
        }
    }

    /**
     * Navigates to a URL directly without going through the UrlBar. This bypasses the page
     * preloading mechanism of the UrlBar.
     * @param url the page URL
     * @param failureWriter the writer where failures/crashes/timeouts are logged.
     * @throws IOException unable to read from input or write to writer.
     * @throws InterruptedException the thread was interrupted waiting for the page to load.
     */
    public void loadUrl(final String url, OutputStreamWriter failureWriter)
            throws InterruptedException, IOException {
        Tab tab = getActivity().getActivityTab();
        final CallbackHelper loadedCallback = new CallbackHelper();
        final CallbackHelper failedCallback = new CallbackHelper();
        final CallbackHelper crashedCallback = new CallbackHelper();

        tab.addObserver(new EmptyTabObserver() {
            @Override
            public void onPageLoadFinished(Tab tab) {
                loadedCallback.notifyCalled();
            }

            @Override
            public void onPageLoadFailed(Tab tab, int errorCode) {
                failedCallback.notifyCalled();
            }

            @Override
            public void onCrash(Tab tab, boolean sadTabShown) {
                crashedCallback.notifyCalled();
            }
        });

        getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                Tab tab = getActivity().getActivityTab();
                int pageTransition = PageTransition.TYPED | PageTransition.FROM_ADDRESS_BAR;
                tab.loadUrl(new LoadUrlParams(url, pageTransition));
            }
        });
        // There are a combination of events ordering in a failure case.
        // There might be TAB_CRASHED with or without PAGE_LOAD_FINISHED preceding it.
        // It is possible to get PAGE_LOAD_FINISHED followed by PAGE_LOAD_FAILED due to redirects.
        boolean timedout = false;
        try {
            loadedCallback.waitForCallback(0, 1, 2, TimeUnit.MINUTES);
        } catch (TimeoutException ex) {
            timedout = true;
        }

        boolean failed = true;
        boolean crashed = true;
        if (mDoShortWait) {
            try {
                failedCallback.waitForCallback(0, 1, SHORT_WAIT_TIMEOUT, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ex) {
                failed = false;
            }
            try {
                crashedCallback.waitForCallback(0, 1, SHORT_WAIT_TIMEOUT, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ex) {
                crashed = false;
            }
        } else {
            try {
                failedCallback.waitForCallback(
                        0, 1, scaleTimeout(100 * 1000), TimeUnit.MILLISECONDS);
            } catch (TimeoutException ex) {
                failed = false;
            }
            try {
                crashedCallback.waitForCallback(
                        0, 1, scaleTimeout(100 * 1000), TimeUnit.MILLISECONDS);
            } catch (TimeoutException ex) {
                crashed = false;
            }
        }
        if (crashed) {
            logToStream(url + " crashed!" + NEW_LINE, failureWriter);
            mFailed = true;
        }
        if (failed) {
            logToStream(url + " failed to load!" + NEW_LINE, failureWriter);
            mFailed = true;
        }
        if (timedout) {
            logToStream(url + " timed out!" + NEW_LINE, failureWriter);
            mFailed = true;
        }
        // Try to stop page load.
        getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                getActivity().getActivityTab().stopLoading();
            }
        });
        getInstrumentation().waitForIdleSync();
    }

    /**
     * Loops over a list of URLs, points the browser to each one, and records the time elapsed.
     *
     * @param input the reader from which to get the URLs.
     * @param outputWriter the writer to which to output the results.
     * @param failureWriter the writer where failures/crashes/timeouts are logged.
     * @param clearCache determines whether the cache is cleared before loading each page.
     * @param loopCount the number of times to loop through the list of pages.
     * @throws IOException unable to read from input or write to writer.
     * @throws InterruptedException the thread was interrupted waiting for the page to load.
     */
    private void loopUrls(BufferedReader input, OutputStreamWriter outputWriter,
            OutputStreamWriter failureWriter, boolean clearCache, int loopCount)
            throws IOException, InterruptedException {
        List<String> pages = new LinkedList<String>();

        String page;
        while (null != (page = input.readLine())) {
            if (!TextUtils.isEmpty(page)) {
                pages.add(page);
            }
        }

        Iterator<String> iterator = pages.iterator();
        for (int i = 0; i < mStatus.getPage(); ++i) {
            iterator.next();
        }

        if (mStatus.getIsRecovery()) {
            Log.e(TAG, "Recovering after crash: " + iterator.next());
            mStatus.incrementPage();
        }

        while (mStatus.getIteration() < loopCount) {
            if (clearCache) {
                // TODO(jingzhao): Clear cache before loading the URL.
            }
            while (iterator.hasNext()) {
                page = iterator.next();
                mStatus.setUrl(page);
                mStatus.write();
                Log.i(TAG, "Start: " + page);

                long startTime = System.currentTimeMillis();
                loadUrl(page, failureWriter);
                long stopTime = System.currentTimeMillis();

                String currentUrl = getActivity().getActivityTab().getUrl();
                Log.i(TAG, "Finish: " + currentUrl);
                logToStream(page + "|" + (stopTime - startTime) + NEW_LINE, outputWriter);
                mStatus.incrementPage();
            }
            mStatus.incrementIteration();
            mStatus.resetPage();
            iterator = pages.iterator();
        }
    }

    /**
     * Navigate to all the pages listed in the input.
     * @param perf Whether this is a performance test or stability test.
     * @throws IOException
     * @throws InterruptedException
     */
    public void loadPages(boolean perf) throws IOException, InterruptedException {
        OutputStreamWriter outputWriter = null;
        if (perf) {
            outputWriter = getOutputStream(OUTPUT_FILE);
        }
        OutputStreamWriter failureWriter = getOutputStream(FAILURE_FILE);
        try {
            BufferedReader bufferedReader = getInputStream(INPUT_FILE);
            int loopCount = perf ? PERF_LOOPCOUNT : STABILITY_LOOPCOUNT;
            try {
                loopUrls(bufferedReader, outputWriter, failureWriter, true, loopCount);
                assertFalse(
                        String.format("Failed to load all pages. Take a look at %s", FAILURE_FILE),
                        mFailed);
            } finally {
                if (bufferedReader != null) {
                    bufferedReader.close();
                }
            }
        } catch (FileNotFoundException fnfe) {
            Log.e(TAG, fnfe.getMessage(), fnfe);
            fail(String.format("URL file %s is not found.", INPUT_FILE));
        } finally {
            if (outputWriter != null) {
                outputWriter.close();
            }
            if (failureWriter != null) {
                failureWriter.close();
            }
        }
    }

    /**
     * Repeats loading all URLs by PERF_LOOPCOUNT times, and records the time each load takes.
     */
    @Manual
    public void testLoadPerformance() throws IOException, InterruptedException {
        loadPages(true);
    }

    /**
     * Loads all URLs.
     */
    @Manual
    public void testStability() throws IOException, InterruptedException {
        loadPages(false);
    }
}
