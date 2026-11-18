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

import java.util.Arrays;
import org.rockbox.Helper.Logger;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Build;
import android.os.Process;

public class RockboxPCM 
{
    private static final int streamtype = AudioManager.STREAM_MUSIC;
    private static final int samplerate = 44100;
    /* should be CHANNEL_OUT_STEREO in 2.0 and above */
    private static final int channels = 
            AudioFormat.CHANNEL_OUT_STEREO;
    private static final int encoding = 
            AudioFormat.ENCODING_PCM_16BIT;
    private AudioManager audiomanager;
    private RockboxService rbservice;
    private byte[] raw_data;

    private int refillmark;
    private int maxstreamvolume;
    private int setstreamvolume = -1;
    private float minpcmvolume;
    private float curpcmvolume = 0;
    private float pcmrange;

    /* 8k is plenty, but some devices may have a higher minimum.
     * 8k represents 125ms of audio */
    private static final int chunkSize =
        Math.max(8<<10, AudioTrack.getMinBufferSize(samplerate, channels, encoding));
    Streamer streamer;

    /* PodBox: RockboxPCM used to extend AudioTrack. It now wraps one, so the
     * track can be built with PERFORMANCE_MODE_POWER_SAVING (API 26+). That
     * puts it on the deep-buffer output; otherwise Android 12 uses the
     * low-latency primary output (256-frame HAL periods, FastMixer), which
     * costs about half a CPU core and heats the device. */
    private final AudioTrack track;

    private static AudioTrack createTrack()
    {
        AudioAttributes attr = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();
        AudioFormat fmt = new AudioFormat.Builder()
            .setSampleRate(samplerate)
            .setChannelMask(channels)
            .setEncoding(encoding)
            .build();
        if (Build.VERSION.SDK_INT >= 26)
        {
            return new AudioTrack.Builder()
                .setAudioAttributes(attr)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(chunkSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
                .build();
        }
        return new AudioTrack(attr, fmt, chunkSize, AudioTrack.MODE_STREAM,
                              AudioManager.AUDIO_SESSION_ID_GENERATE);
    }

    public RockboxPCM()
    {
        track = createTrack();

        streamer = new Streamer(chunkSize);
        streamer.start();
        raw_data = new byte[chunkSize]; /* in shorts */
        Arrays.fill(raw_data, (byte) 0);

        /* find cleaner way to get context? */
        rbservice = RockboxService.getInstance();
        audiomanager =
            (AudioManager) rbservice.getSystemService(Context.AUDIO_SERVICE);
        maxstreamvolume = audiomanager.getStreamMaxVolume(streamtype);

        minpcmvolume = AudioTrack.getMinVolume();
        pcmrange = AudioTrack.getMaxVolume() - minpcmvolume;

        setupVolumeHandler();
        postVolume(audiomanager.getStreamVolume(streamtype));
    }

    /**
     * This class does the actual playback work. Its run() method
     * continuously writes data to the AudioTrack. This operation blocks
     * and should therefore be run on its own thread.
     */
    private class Streamer extends Thread
    {
        byte[] buffer;
        private boolean quit = false;

        Streamer(int bufsize)
        {
            super("audio thread");
            buffer = new byte[bufsize];
        }

        @Override
        public void run()
        {
            /* THREAD_PRIORITY_URGENT_AUDIO can only be specified via
             * setThreadPriority(), and not via thread.setPriority(). This is
             * also how the android's HandlerThread class implements it */
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            while (!quit)
            {
                switch(track.getPlayState())
                {
                    case AudioTrack.PLAYSTATE_PLAYING:
                        nativeWrite(buffer, buffer.length);
                        break;
                    case AudioTrack.PLAYSTATE_PAUSED:
                    case AudioTrack.PLAYSTATE_STOPPED:
                    {
                        synchronized (this)
                        {
                            try
                            {
                                wait();
                            }
                            catch (InterruptedException e) { e.printStackTrace(); }
                            break;
                        }
                    }
                }
            }
        }

        synchronized void quit()
        {
            quit = true;
            notify();
        }

        synchronized void kick()
        {
            notify();
        }

        void quitAndJoin()
        {
            while(true)
            {
                try
                {
                    quit();
                    join();
                    return;
                }
                catch (InterruptedException e) { }
            }
        }
    }

    private native void postVolumeChangedEvent(int volume);

    private void postVolume(int volume)
    {
        int rbvolume = ((maxstreamvolume - volume) * -99) /
            maxstreamvolume;
        Logger.d("java:postVolumeChangedEvent, avol "+volume+" rbvol "+rbvolume);
        postVolumeChangedEvent(rbvolume);
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
        int volume = audiomanager.getStreamVolume(streamtype);
        int rbvolume = ((maxstreamvolume - volume) * -99) / maxstreamvolume;
        postVolumeChangedEvent(rbvolume);

        /* We're relying on internal API's here,
           this can break in the future! */
        rbservice.registerReceiver(
            broadcastReceiver,
            new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
    }

    private int bytes2frames(int bytes) 
    {
        /* 1 sample is 2 bytes, 2 samples are 1 frame */
        return (bytes/4);
    }

    private int frames2bytes(int frames) 
    {
        /* 1 frame is 2 samples, 1 sample is 2 bytes */
        return (frames*4);
    }

    private void play_pause(boolean pause) 
    {
        RockboxService service = RockboxService.getInstance();
        if (pause)
        {
            Intent widgetUpdate = new Intent("org.rockbox.UpdateState");
            widgetUpdate.setPackage(service.getPackageName());
            widgetUpdate.putExtra("state", "pause");
            service.sendBroadcast(widgetUpdate);
            service.stopForeground();
            track.pause();
        }
        else
        {
            Intent widgetUpdate = new Intent("org.rockbox.UpdateState");
            widgetUpdate.setPackage(service.getPackageName());
            widgetUpdate.putExtra("state", "play");
            service.sendBroadcast(widgetUpdate);
            service.startForeground();
            if (track.getPlayState() == AudioTrack.PLAYSTATE_STOPPED)
            {
                /* need to fill with silence before starting playback */ 
                track.write(raw_data, 0, raw_data.length);
            }
            play();
        }
    }

    /* called from native code (pcm-android.c) */
    public int write(byte[] audioData, int offsetInBytes, int sizeInBytes)
    {
        return track.write(audioData, offsetInBytes, sizeInBytes);
    }

    public void play() throws IllegalStateException
    {
        track.play();
        /* when stopped or paused the streamer is in a wait() state. need
         * it to wake it up */
        streamer.kick();
    }

    public synchronized void stop() throws IllegalStateException 
    {
        /* flush pending data, but turn the volume off so it cannot be heard.
         * This is so that we don't hear old data if music is resumed very
         * quickly after (e.g. when seeking).
         */
        float old_vol = curpcmvolume; 
        try {
            setStereoVolume(0, 0);
            track.flush();
            track.stop();
        } catch (IllegalStateException e) {
            throw new IllegalStateException(e);
        } finally {
            setStereoVolume(old_vol, old_vol);
        }

        Intent widgetUpdate = new Intent("org.rockbox.UpdateState");
        widgetUpdate.setPackage(RockboxService.getInstance().getPackageName());
        widgetUpdate.putExtra("state", "stop");
        RockboxService.getInstance().sendBroadcast(widgetUpdate);
        RockboxService.getInstance().stopForeground();
    }

    public void release()
    {
        track.release();
        /* stop streamer if this AudioTrack is destroyed by whomever */
        streamer.quitAndJoin();
    }

    public int setStereoVolume(float leftVolume, float rightVolume)
    {
        curpcmvolume = leftVolume;
        return track.setStereoVolume(leftVolume, rightVolume);
    }

    private void set_volume(int volume)
    {
        Logger.d("java:set_volume("+volume+")");
        /* Rockbox 'volume' is 0..-990 deci-dB attenuation.
           Android streams have rather low resolution volume control,
           typically 8 or 15 steps.
           Therefore we use the pcm volume to add finer steps between
           every android stream volume step.
           It's not "real" dB, but it gives us 100 volume steps.
        */

        float fraction = 1 - (volume / -990.0f);
        int streamvolume = (int)Math.ceil(maxstreamvolume * fraction);
        if (streamvolume > 0) {
            float streamfraction = (float)streamvolume / maxstreamvolume;
            float pcmvolume =
                (fraction / streamfraction) * pcmrange + minpcmvolume;
            setStereoVolume(pcmvolume, pcmvolume);
        }

        int oldstreamvolume = audiomanager.getStreamVolume(streamtype);
        if (streamvolume != oldstreamvolume) {
            Logger.d("java:setStreamVolume("+streamvolume+")");
            setstreamvolume = streamvolume;
            audiomanager.setStreamVolume(streamtype, streamvolume, 0);
        }
    }

    public native int nativeWrite(byte[] temp, int len);
}
