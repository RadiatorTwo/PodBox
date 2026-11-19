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

import org.rockbox.Helper.Logger;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Build;

/* PodBox: the audio itself is played by native code (pcm-android.c, AAudio,
 * 32 bit, any rate). This class is left with what needs Java: the Android
 * volume (shared with Rockbox) and the foreground service / widgets. */
public class RockboxPCM
{
    private static final int streamtype = AudioManager.STREAM_MUSIC;
    private AudioManager audiomanager;
    private RockboxService rbservice;

    private int maxstreamvolume;
    private int setstreamvolume = -1;
    /* PodBox: Android's volume wins at startup, see set_volume() */
    private int postedVolume = Integer.MIN_VALUE;
    private boolean volumeSynced = false;

    public RockboxPCM()
    {
        rbservice = RockboxService.getInstance();
        audiomanager =
            (AudioManager) rbservice.getSystemService(Context.AUDIO_SERVICE);
        maxstreamvolume = audiomanager.getStreamMaxVolume(streamtype);

        setupVolumeHandler();
    }

    private native void postVolumeChangedEvent(int volume);

    private void postVolume(int volume)
    {
        int rbvolume = streamToRockbox(volume);
        Logger.d("java:postVolumeChangedEvent, avol "+volume+" rbvol "+rbvolume);
        postedVolume = rbvolume;
        /* the Android step now is the whole volume */
        nativeSetGain(1.0f);
        postVolumeChangedEvent(rbvolume);
    }

    /* PodBox: Rockbox and Android share one volume. Rockbox has STEPS
     * levels, each maps to the Android step closest to it in dB using
     * Android's volume curve (on the F20 Pro+: 100 steps, 1 = -58 dB,
     * 20 = -40 dB, 60 = -17 dB, 100 = 0 dB). */
    private float[] streamDb;
    private boolean streamDbHeadset;
    private int streamDbLowest;     /* quietest audible Android step */

    /* attenuation in dB of every Android step, index 0 = muted */
    private float[] getStreamDb()
    {
        if (Build.VERSION.SDK_INT < 28)
            return null;
        boolean headset = audiomanager.isWiredHeadsetOn();
        if (streamDb == null || headset != streamDbHeadset)
        {
            int device = headset ? android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                                 : android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
            float[] db = new float[maxstreamvolume + 1];
            try {
                for (int i = 0; i <= maxstreamvolume; i++)
                    db[i] = audiomanager.getStreamVolumeDb(streamtype, i, device);
            } catch (Exception e) {
                Logger.e("getStreamVolumeDb failed: " + e);
                return null;
            }
            /* the quietest audible step (Android may report -infinity
             * for the lowest steps, that broke the level math once and
             * jumped to full volume) */
            int lowest = 0;
            for (int i = 1; i <= maxstreamvolume; i++)
            {
                if (!Float.isInfinite(db[i]) && !Float.isNaN(db[i])
                    && db[i] > -120.0f)
                {
                    lowest = i;
                    break;
                }
            }
            Logger.i("PodBox: volume curve " + (headset ? "headset" : "speaker")
                     + ": step 1 = " + db[1] + " dB, quietest audible = step "
                     + lowest + " (" + (lowest > 0 ? db[lowest] : 0) + " dB), "
                     + maxstreamvolume + " = " + db[maxstreamvolume] + " dB");
            if (lowest == 0 || db[lowest] >= -1.0f)
                return null;
            streamDb = db;
            streamDbLowest = lowest;
            streamDbHeadset = headset;
        }
        return streamDb;
    }

    /* PodBox: Rockbox volume is 0..STEPS levels (0 = muted), must match
     * AUDIOHW_SETTING(VOLUME) in firmware/export/android_codec.h. Level 1 is
     * Android's quietest step, STEPS is full volume, the levels in between
     * are spaced evenly in dB (about 2 dB on the F20 Pro+). */
    private static final int STEPS = 30;

    /* attenuation in dB of a Rockbox level >= 1 */
    private float levelDb(float[] db, int level)
    {
        float lowest = db[streamDbLowest];
        return lowest - lowest * (level - 1) / (STEPS - 1);
    }

    /* Android step -> Rockbox level (0..STEPS) */
    private int streamToRockbox(int index)
    {
        float[] db = getStreamDb();
        if (index <= 0)
            return 0;
        if (db == null)
            return Math.max(1, (index * STEPS + maxstreamvolume / 2) / maxstreamvolume);
        if (index <= streamDbLowest)
            return 1;
        float lowest = db[streamDbLowest];
        float d = db[Math.min(index, maxstreamvolume)];
        int level = Math.round((d - lowest) / -lowest * (STEPS - 1)) + 1;
        return Math.max(1, Math.min(STEPS, level));
    }

    private void setupVolumeHandler()
    {
        BroadcastReceiver broadcastReceiver = new BroadcastReceiver()
        {
            @Override
            public void onReceive(Context context, Intent intent)
            {
                int streamType = intent.getIntExtra(
                    "android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
                int volume = intent.getIntExtra(
                    "android.media.EXTRA_VOLUME_STREAM_VALUE", -1);

                if (streamType == RockboxPCM.streamtype &&
                    volume != -1 &&
                    volume != setstreamvolume &&
                    rbservice.isRockboxRunning())
                {
                    postVolume(volume);
                }
            }
        };

        /* at startup, change the internal rockbox volume to what the global
           android music stream volume is */
        postVolume(audiomanager.getStreamVolume(streamtype));

        /* We're relying on internal API's here,
           this can break in the future! */
        rbservice.registerReceiver(
            broadcastReceiver,
            new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
    }

    /* called from native code: playback started (true) or stopped */
    private void play_state(boolean playing)
    {
        RockboxService service = RockboxService.getInstance();
        Intent widgetUpdate = new Intent("org.rockbox.UpdateState");
        widgetUpdate.setPackage(service.getPackageName());
        widgetUpdate.putExtra("state", playing ? "play" : "stop");
        service.sendBroadcast(widgetUpdate);
        /* PodBox: stay in the foreground while paused too (see
         * RockboxService.doStart), otherwise Android stops the service about
         * a minute after the screen goes off and PodBox exits */
        if (playing)
            service.startForeground();
    }

    /* fine attenuation applied by the native output, 1.0 = untouched */
    private native void nativeSetGain(float gain);

    /* called from audiohw_set_volume(), returns the volume now in effect.
     * Rockbox 'volume' is the level (0..STEPS) times 10. */
    private int set_volume(int volume)
    {
        Logger.d("java:set_volume("+volume+")");
        if (!volumeSynced)
        {
            /* The first call comes from Rockbox's stored (or default) value.
             * Android's volume wins: hand it back, Rockbox takes it over.
             * Waiting for SYS_VOLUME_CHANGED instead was not reliable, that
             * event can get lost during boot and then every later volume
             * change was ignored. */
            volumeSynced = true;
            if (Math.round(volume / 10.0f) != postedVolume)
            {
                Logger.i("PodBox: startup volume " + volume / 10.0f
                         + " replaced by Android's " + postedVolume);
                return postedVolume * 10;
            }
        }
        apply_volume(Math.round(volume / 10.0f));
        return volume;
    }

    private void apply_volume(int level)
    {
        float[] db = getStreamDb();
        if (db == null)
        {
            set_volume_linear(level);
            return;
        }

        int oldstreamvolume = audiomanager.getStreamVolume(streamtype);
        int streamvolume;
        float target = level > 0 ? levelDb(db, level) : 0;
        /* half a level: Android steps this close count as this level */
        float tolerance = -db[streamDbLowest] / (STEPS - 1) / 2;
        if (level <= 0)
            streamvolume = 0; /* level 0 = muted */
        else if (oldstreamvolume > 0 && oldstreamvolume <= maxstreamvolume
                 && Math.abs(db[oldstreamvolume] - target) <= tolerance)
            /* Android already is at this volume (e.g. the change came from
             * Android itself), keep its step to avoid ping-pong */
            streamvolume = oldstreamvolume;
        else
        {   /* lowest step that is not quieter than the target */
            streamvolume = maxstreamvolume;
            for (int i = streamDbLowest; i <= maxstreamvolume; i++)
            {
                if (db[i] >= target)
                {
                    streamvolume = i;
                    break;
                }
            }
        }

        /* Android's step is the whole volume (no extra attenuation) */
        nativeSetGain(1.0f);

        if (streamvolume != oldstreamvolume) {
            Logger.d("java:setStreamVolume("+streamvolume+")");
            setstreamvolume = streamvolume;
            audiomanager.setStreamVolume(streamtype, streamvolume, 0);
        }
    }

    /* old mapping, for Android < 9 (no getStreamVolumeDb()) */
    private void set_volume_linear(int level)
    {
        int streamvolume = (level * maxstreamvolume + STEPS / 2) / STEPS;
        if (level > 0 && streamvolume < 1)
            streamvolume = 1;
        nativeSetGain(1.0f);

        int oldstreamvolume = audiomanager.getStreamVolume(streamtype);
        if (streamvolume != oldstreamvolume) {
            Logger.d("java:setStreamVolume("+streamvolume+")");
            setstreamvolume = streamvolume;
            audiomanager.setStreamVolume(streamtype, streamvolume, 0);
        }
    }

}
