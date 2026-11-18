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
    /* PodBox: Android's volume wins at startup. Until Rockbox has taken over
     * the volume we posted, its set_volume() calls come from its own stored
     * (or default 0 dB!) value and must not touch the Android volume. */
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

    /* PodBox: Rockbox and Android share one volume. Rockbox shows real dB,
     * so its value has to follow Android's volume curve (on the F20 Pro+:
     * 100 steps, 1 = -58 dB, 20 = -40 dB, 60 = -17 dB, 100 = 0 dB). The old
     * code spread -99..0 dB evenly over the steps, so "-79 dB" was really
     * about -39 dB and the lowest Rockbox steps were still clearly audible.
     * Below Android's lowest step the native output attenuates the rest. */
    private float[] streamDb;
    private boolean streamDbHeadset;

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
            streamDb = db;
            streamDbHeadset = headset;
        }
        return streamDb;
    }

    /* Android step -> Rockbox volume in dB (-99..0) */
    private int streamToRockbox(int index)
    {
        float[] db = getStreamDb();
        if (db == null)
            return ((maxstreamvolume - index) * -99) / maxstreamvolume;
        if (index <= 0)
            return -99;
        return Math.max(-99, Math.min(0, Math.round(db[Math.min(index, maxstreamvolume)])));
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

    private void set_volume(int volume)
    {
        Logger.d("java:set_volume("+volume+")");
        if (!volumeSynced)
        {
            if (Math.round(volume / 10.0f) != postedVolume)
            {
                Logger.i("PodBox: ignoring startup volume " + volume / 10.0f
                         + " dB, Android has " + postedVolume + " dB");
                return;
            }
            volumeSynced = true;
        }
        /* Rockbox 'volume' is 0..-990 deci-dB attenuation */
        float target = volume / 10.0f;
        float[] db = getStreamDb();
        if (db == null)
        {
            set_volume_linear(volume);
            return;
        }

        int oldstreamvolume = audiomanager.getStreamVolume(streamtype);
        int streamvolume;
        if (target <= -99.0f)
            streamvolume = 0; /* lowest Rockbox step = muted */
        else if (oldstreamvolume > 0 && oldstreamvolume <= maxstreamvolume
                 && Math.abs(db[oldstreamvolume] - target) <= 0.5f)
            /* Android already is at this volume (e.g. the change came from
             * Android itself), keep its step to avoid ping-pong */
            streamvolume = oldstreamvolume;
        else
        {   /* lowest step that is not quieter than the target */
            streamvolume = maxstreamvolume;
            for (int i = 1; i <= maxstreamvolume; i++)
            {
                if (db[i] >= target)
                {
                    streamvolume = i;
                    break;
                }
            }
        }

        /* the rest (only below the lowest Android step, or less than one
         * step) is done by the native output, it can only attenuate */
        float pcmvolume = 1.0f;
        if (streamvolume > 0)
        {
            float rest = target - db[streamvolume];
            if (rest < 0)
                pcmvolume = (float)Math.pow(10.0, rest / 20.0);
        }
        nativeSetGain(pcmvolume);

        if (streamvolume != oldstreamvolume) {
            Logger.d("java:setStreamVolume("+streamvolume+")");
            setstreamvolume = streamvolume;
            audiomanager.setStreamVolume(streamtype, streamvolume, 0);
        }
    }

    /* old mapping, for Android < 9 (no getStreamVolumeDb()) */
    private void set_volume_linear(int volume)
    {
        /* Android streams have rather low resolution volume control,
           typically 8 or 15 steps.
           Therefore we use the pcm volume to add finer steps between
           every android stream volume step.
           It's not "real" dB, but it gives us 100 volume steps.
        */

        float fraction = 1 - (volume / -990.0f);
        int streamvolume = (int)Math.ceil(maxstreamvolume * fraction);
        if (streamvolume > 0) {
            float streamfraction = (float)streamvolume / maxstreamvolume;
            nativeSetGain(fraction / streamfraction);
        }

        int oldstreamvolume = audiomanager.getStreamVolume(streamtype);
        if (streamvolume != oldstreamvolume) {
            Logger.d("java:setStreamVolume("+streamvolume+")");
            setstreamvolume = streamvolume;
            audiomanager.setStreamVolume(streamtype, streamvolume, 0);
        }
    }

}
