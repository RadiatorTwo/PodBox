/***************************************************************************
 *             __________               __   ___.
 *   Open      \______   \ ____   ____ |  | _\_ |__   _______  ___
 *   Source     |       _//  _ \_/ ___\|  |/ /| __ \ /  _ \  \/  /
 *   Jukebox    |    |   (  <_> )  \___|    < | \_\ (  <_> > <  <
 *   Firmware   |____|_  /\____/ \___  >__|_ \|___  /\____/__/\_ \
 *                     \/            \/     \/    \/            \/
 *
 * Copyright (C) 2010 Thomas Martitz
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTY OF ANY
 * KIND, either express or implied.
 *
 ****************************************************************************/

package org.rockbox;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.rockbox.Helper.Logger;
import org.rockbox.Helper.MediaButtonReceiver;
import org.rockbox.Helper.PodBoxMediaSession;
import org.rockbox.Helper.RunForegroundManager;
import android.app.Activity;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.view.KeyEvent;

/* This class is used as the main glue between java and c.
 * All access should be done through RockboxService.get_instance() for safety.
 */

public class RockboxService extends Service
{
    /* this Service is really a singleton class - well almost. */
    private static RockboxService instance = null;

    /* locals needed for the c code and Rockbox state */
    private static volatile boolean rockbox_running;
    private Activity mCurrentActivity = null;
    private RunForegroundManager mFgRunner;
    private MediaButtonReceiver mMediaButtonReceiver;
    private PodBoxMediaSession mMediaSession;
    private ResultReceiver mResultReceiver;

    /* possible result values for intent handling */ 
    public static final int RESULT_INVOKING_MAIN = 0;
    public static final int RESULT_LIB_LOAD_PROGRESS = 1;
    public static final int RESULT_SERVICE_RUNNING = 3;
    public static final int RESULT_ERROR_OCCURED = 4;
    public static final int RESULT_LIB_LOADED = 5;
    public static final int RESULT_ROCKBOX_EXIT = 6;

    @Override
    public void onCreate()
    {
        instance = this;
        mMediaButtonReceiver = new MediaButtonReceiver(this);
        mMediaSession = new PodBoxMediaSession(this);
        mFgRunner = new RunForegroundManager(this);
        rememberOtherLauncher();
    }

    public static RockboxService getInstance()
    {
        /* don't call the constructor here, the instances are managed by
         * android, so we can't just create a new one */
        return instance;
    }

    public boolean isRockboxRunning()
    {
        return rockbox_running;
    }
    public Activity getActivity()
    {
        return mCurrentActivity;
    }

    public void setActivity(Activity a)
    {
        mCurrentActivity = a;
    }
    
    private void putResult(int resultCode)
    {
        putResult(resultCode, null);
    }

    private void putResult(int resultCode, Bundle resultData)
    {
        if (mResultReceiver != null)
            mResultReceiver.send(resultCode, resultData);
    }

    private void doStart(Intent intent)
    {
        Logger.d("Start RockboxService (Intent: " + intent.getAction() + ")");

        if (intent.getAction().equals("org.rockbox.ResendTrackUpdateInfo"))
        {
            if (rockbox_running)
                mFgRunner.resendUpdateNotification();
            return;
        }

        if (intent.hasExtra("callback"))
            mResultReceiver = (ResultReceiver) intent.getParcelableExtra("callback");

        if (!rockbox_running)
            startService();

        if (intent.getAction().equals(Intent.ACTION_MEDIA_BUTTON))
        {
            KeyEvent kev = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            if (kev != null)
                RockboxFramebuffer.backgroundKey(kev);
        }

        /* (Re-)attach the media button receiver, in case it has been lost */
        mMediaButtonReceiver.register();
        putResult(RESULT_SERVICE_RUNNING);

        /* PodBox: run as foreground service for as long as Rockbox runs, not
         * only while playing. A paused PodBox with the screen off otherwise
         * counts as idle background app, Android stops the service after
         * about a minute and onDestroy() ends the process. Fails (caught)
         * when started from the background; the activity retries on resume. */
        mFgRunner.startForeground();

        rockbox_running = true;
    }

    public void onStart(Intent intent, int startId) {
        doStart(intent);
    }

    public int onStartCommand(Intent intent, int flags, int startId)
    {
        /* if null, then the service was most likely restarted by android
         * after getting killed for memory pressure earlier */
        if (intent == null)
            intent = new Intent("org.rockbox.ServiceRestarted");
        doStart(intent);
        /* PodBox: no sticky restart. A restarted service without UI is
         * stopped again right away for being idle in the background, and
         * its System.exit() then also kills the activity Android restarts
         * for the home screen. The activity starts Rockbox again instead. */
        return START_NOT_STICKY;
    }

    private void startService()
    {
        final Object lock = new Object();
        Thread rb = new Thread(null, new Runnable()
        {
            public void run()
            {
                final int BUFFER = 8*1024;
                /* PodBox: no hard-coded package, the id is configurable */
                String rockboxDirPath = getDir("rockbox", MODE_PRIVATE).getAbsolutePath() + "/rockbox";
                String rockboxCreditsPath = rockboxDirPath + "/rocks/viewers";
                String rockboxSdDirPath = "/sdcard/rockbox";

                /* the following block unzips libmisc.so, which contains the files 
                 * we ship, such as themes. It's needed to put it into a .so file
                 * because there's no other way to ship files and have access
                 * to them from native code
                 */
                File libMisc = new File(getApplicationInfo().nativeLibraryDir, "libmisc.so");
                /* use arbitrary file to determine whether extracting is needed */
                File arbitraryFile = new File(rockboxCreditsPath, "credits.rock");
                File rockboxInfoFile = new File(rockboxSdDirPath, "rockbox-info.txt");
                /* PodBox: libs extracted from the APK carry a fixed 1980
                 * timestamp, so compare against the package update time */
                long updateTime = 0;
                try {
                    updateTime = getPackageManager()
                        .getPackageInfo(getPackageName(), 0).lastUpdateTime;
                } catch (Exception ex) { }
                final SharedPreferences prefs =
                    getSharedPreferences("podbox", MODE_PRIVATE);
                final long extractedTime = prefs.getLong("extracted", -1);
                /* unzip newer or doesnt exist */
                boolean doExtract = !arbitraryFile.exists()
                        || extractedTime != updateTime;

                /* load library before unzipping which may take a while
                 * but at least tell if unzipping is going to be done before*/
                synchronized (lock) {
                    Bundle bdata = new Bundle();
                    bdata.putBoolean("unzip", doExtract);
                    /* PodBox: tell the native code where the codecs really
                     * are. The legacy /data/data/org.rockbox/lib link it was
                     * built with is not updated when the ABI changes (it
                     * still pointed to lib/arm after installing arm64). */
                    try {
                        android.system.Os.setenv("ROCKBOX_BINDIR",
                                getApplicationInfo().nativeLibraryDir, true);
                    } catch (android.system.ErrnoException e) {
                        Logger.e("setenv ROCKBOX_BINDIR failed: " + e);
                    }
                    System.loadLibrary("rockbox");
                    putResult(RESULT_LIB_LOADED, bdata);
                    lock.notify();
                }

                if (doExtract)
                {
                    boolean extractToSd = false;
                    if(rockboxInfoFile.exists()) {
                        extractToSd = true;
                        Logger.d("extracting resources to SD card");
                    }
                    else {
                        Logger.d("extracting resources to internal memory");
                    }
                    try
                    {
                        Bundle progressData = new Bundle();
                        byte data[] = new byte[BUFFER];
                        ZipFile zipfile = new ZipFile(libMisc);
                        Enumeration<? extends ZipEntry> e = zipfile.entries();
                        progressData.putInt("max", zipfile.size());

                        while(e.hasMoreElements())
                        {
                           ZipEntry entry = (ZipEntry) e.nextElement();
                           File file;
                           /* strip off /.rockbox when extracting */
                           String fileName = entry.getName();
                           int slashIndex = fileName.indexOf('/', 1);
                           /* codecs are now stored as libs, only keep rocks on internal */
                           if(extractToSd == false
                               || fileName.substring(slashIndex).startsWith("/rocks"))
                           {
                               file = new File(rockboxDirPath + fileName.substring(slashIndex));
                           }
                           else
                           {
                               file = new File(rockboxSdDirPath + fileName.substring(slashIndex));
                           }

                           if (!entry.isDirectory())
                           {
                               /* Create the parent folders if necessary */
                               File folder = new File(file.getParent());
                               if (!folder.exists())
                                   folder.mkdirs();

                               /* Extract file */
                               BufferedInputStream is = new BufferedInputStream(zipfile.getInputStream(entry), BUFFER);
                               FileOutputStream fos = new FileOutputStream(file);
                               BufferedOutputStream dest = new BufferedOutputStream(fos, BUFFER);

                               int count;
                               while ((count = is.read(data, 0, BUFFER)) != -1)
                                  dest.write(data, 0, count);

                               dest.flush();
                               dest.close();
                               is.close();
                           }

                           progressData.putInt("value", progressData.getInt("value", 0) + 1);
                           putResult(RESULT_LIB_LOAD_PROGRESS, progressData);
                        }
                        arbitraryFile.setLastModified(libMisc.lastModified());
                        prefs.edit().putLong("extracted", updateTime).commit();
                    } catch(Exception e) {
                        Logger.d("Exception when unzipping", e);
                        Bundle bundle = new Bundle();
                        e.printStackTrace();
                        bundle.putString("error", getString(R.string.error_extraction));
                        putResult(RESULT_ERROR_OCCURED, bundle);
                    }
                }

                /* Generate default config if none exists yet */
                File rockboxConfig = new File(Environment.getExternalStorageDirectory(), "rockbox/config.cfg");
                if (!rockboxConfig.exists()) {
                    File rbDir = new File(rockboxConfig.getParent());
                    if (!rbDir.exists())
                        rbDir.mkdirs();

                    OutputStreamWriter strm;
                    try {
                        strm = new OutputStreamWriter(new FileOutputStream(rockboxConfig));
                        strm.write("# config generated by RockboxService\n");
                        strm.write("start directory: " + Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + "\n");
                        strm.write("lang: /.rockbox/langs/" + getString(R.string.rockbox_language_file) + "\n");
                        strm.close();
                    } catch(Exception e) {
                        Logger.d("Exception when writing default config", e);
                    }
                }

                /* Start native code */
                putResult(RESULT_INVOKING_MAIN);

                main();

                putResult(RESULT_ROCKBOX_EXIT);

                Logger.d("Stop service: main() returned");
                stopSelf(); /* service is of no use anymore */
            }
        }, "Rockbox thread", 64L * 1024 * 1024); /* big stack: Rockbox thread stacks are carved from it (thread-unix.c) */
        rb.setDaemon(false);
        /* wait at least until the library is loaded */
        synchronized (lock)
        {
            rb.start();
            while(true)
            {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    continue;
                }
                break;
            }
        }
    }

    private native void main();

    @Override
    public IBinder onBind(Intent intent)
    {
        return null;
    }

    void startForeground()
    {
        mFgRunner.startForeground();
    }

    void stopForeground()
    {
        mFgRunner.stopForeground();
    }

    /* PodBox: PodBox can be the home screen. This opens another home screen
     * (normally the manufacturer's launcher) to reach other apps; the home
     * button leads back to PodBox. Called from the Rockbox thread (main menu).
     * Returns false if there is no other home screen. */
    private static final String PREF_OTHER_HOME = "podbox_other_home";
    /* used if the previous home screen is unknown (Surfans F20 Pro+ default) */
    private static final String PREFERRED_OTHER_HOME = "com.blueshark.sidebarlauncher";

    /* Remember the default home screen while it is not PodBox, so "Other apps"
     * opens the one the user had before choosing PodBox. */
    private void rememberOtherLauncher()
    {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo def = getPackageManager()
                .resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            if (def == null || def.activityInfo == null)
                return;
            String pkg = def.activityInfo.packageName;
            /* "android" = no default chosen yet (chooser), skip ourselves too */
            if ("android".equals(pkg) || getPackageName().equals(pkg)
                    || "com.android.settings".equals(pkg))
                return;
            getSharedPreferences("podbox", MODE_PRIVATE).edit()
                .putString(PREF_OTHER_HOME, pkg + "/" + def.activityInfo.name).apply();
        } catch (Exception e) {
            Logger.i("rememberOtherLauncher: " + e);
        }
    }

    public boolean openOtherLauncher()
    {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        String saved = getSharedPreferences("podbox", MODE_PRIVATE)
                           .getString(PREF_OTHER_HOME, null);
        android.content.pm.ActivityInfo other = null;
        int best = -1;
        for (android.content.pm.ResolveInfo ri :
                getPackageManager().queryIntentActivities(home, 0))
        {
            android.content.pm.ActivityInfo ai = ri.activityInfo;
            if (ai == null || getPackageName().equals(ai.packageName))
                continue;
            /* skip the "FallbackHome" of the Settings app */
            if ("com.android.settings".equals(ai.packageName))
                continue;
            int score = 0;
            if (saved != null && saved.equals(ai.packageName + "/" + ai.name))
                score = 2;
            else if (PREFERRED_OTHER_HOME.equals(ai.packageName))
                score = 1;
            if (score > best)
            {
                best = score;
                other = ai;
            }
        }
        if (other == null)
        {
            Logger.i("openOtherLauncher: no other home screen");
            return false;
        }
        home.setClassName(other.packageName, other.name);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(home);
        } catch (Exception e) {
            Logger.i("openOtherLauncher: " + e);
            return false;
        }
        return true;
    }

    @Override
    public void onDestroy()
    {
        super.onDestroy();
        /* Don't unregister so we can receive them (and startup the service)
         * after idle power-off. Hopefully it's OK if mMediaButtonReceiver is
         * garbage collected.
         *  mMediaButtonReceiver.unregister(); */
        mMediaButtonReceiver = null;
        if (mMediaSession != null)
            mMediaSession.release();
        mMediaSession = null;
        /* Make sure our notification is gone. */
        stopForeground();
        instance = null;
        rockbox_running = false;
        System.runFinalization();
        /* exit() seems unclean but is needed in order to get the .so file garbage 
         * collected, otherwise Android caches this Service and librockbox.so
         * The library must be reloaded to zero the bss and reset data
         * segment */
        System.exit(0);
    }
}
