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

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewConfiguration;

public class RockboxFramebuffer extends SurfaceView 
                                 implements SurfaceHolder.Callback
{
    private final DisplayMetrics metrics;
    private final ViewConfiguration view_config;

    /* first stage init; needs to run from a thread that has a Looper 
     * setup stuff that needs a Context */
    public RockboxFramebuffer(Context c)
    {
        super(c);
        metrics = c.getResources().getDisplayMetrics();
        view_config = ViewConfiguration.get(c);
        getHolder().addCallback(this);
        /* Needed so we can catch KeyEvents */
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        /* don't draw until native is ready (2nd stage) */
        setEnabled(false);
    }

    /* PodBox: drawing happens natively (lcd-android.c, ANativeWindow) */

    public boolean onTouchEvent(MotionEvent me)
    {
        int x = (int) me.getX();
        int y = (int) me.getY();

        switch (me.getAction())
        {
        case MotionEvent.ACTION_CANCEL:
        case MotionEvent.ACTION_UP:
            touchHandler(false, x, y);
            return true;
        case MotionEvent.ACTION_MOVE:
        case MotionEvent.ACTION_DOWN:
            touchHandler(true, x, y);
            return true;
        }

        return false;
    }

    /* PodBox: middle hardware key, short press = play/pause,
     * long press = stop (decided on release) */
    private static final long LONG_PRESS_MS = 600;
    /* KeyEvent.KEYCODE_MEDIA_SKIP_* (API 23) */
    private static final int KEYCODE_MEDIA_SKIP_FORWARD  = 272;
    private static final int KEYCODE_MEDIA_SKIP_BACKWARD = 273;

    /* PodBox: keys delivered via the media session while the activity is
     * not in front (screen off). The current Rockbox screen is invisible
     * then, so map the keys to global playback actions instead of
     * navigation: top = previous track, bottom = next track,
     * middle = play/pause (long: stop). The wheel (VOLUME_UP/DOWN) changes
     * the Android stream volume, which RockboxPCM syncs into Rockbox. */
    public static boolean backgroundKey(KeyEvent event)
    {
        boolean up = event.getAction() == KeyEvent.ACTION_UP;
        switch (event.getKeyCode())
        {
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (up)
                    buttonHandler(KEYCODE_MEDIA_SKIP_BACKWARD, false);
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (up)
                    buttonHandler(KEYCODE_MEDIA_SKIP_FORWARD, false);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (up)
                {
                    long held = event.getEventTime() - event.getDownTime();
                    buttonHandler(held >= LONG_PRESS_MS ? KeyEvent.KEYCODE_MEDIA_STOP
                                                        : KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                                  false);
                }
                return true;
            default:
                return buttonHandler(event.getKeyCode(), !up);
        }
    }

    public boolean onKeyDown(int keyCode, KeyEvent event)
    {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            return true; /* handled on release */
        return buttonHandler(keyCode, true);
    }

    public boolean onKeyUp(int keyCode, KeyEvent event)
    {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        {
            long held = event.getEventTime() - event.getDownTime();
            buttonHandler(held >= LONG_PRESS_MS ? KeyEvent.KEYCODE_MEDIA_STOP
                                                : keyCode, false);
            return true;
        }
        return buttonHandler(keyCode, false);
    }
 
    private int getDpi()
    {
        return metrics.densityDpi;
    }
    

    private int getScrollThreshold()
    {
        return view_config.getScaledTouchSlop();
    }

    private native void touchHandler(boolean down, int x, int y);
    public native static boolean buttonHandler(int keycode, boolean state);

    public native void surfaceCreated(SurfaceHolder holder);
    public native void surfaceDestroyed(SurfaceHolder holder);
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height)
    {
        setEnabled(true);
        /* PodBox: a disabled view can't take focus, and with targetSdk >= 28
         * Android no longer hands it out later on its own. Without focus the
         * hardware keys never reach onKeyDown(). */
        requestFocus();
    }
}
