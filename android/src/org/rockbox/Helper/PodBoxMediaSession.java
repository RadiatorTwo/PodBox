/***************************************************************************
 *             __________               __   ___.
 *   Open      \______   \ ____   ____ |  | _\_ |__   _______  ___
 *   Source     |       _//  _ \_/ ___\|  |/ /| __ \ /  _ \  \/  /
 *   Jukebox    |    |   (  <_> )  \___|    < | \_\ (  <_> > <  <
 *   Firmware   |____|_  /\____/ \___  >__|_ \|___  /\____/__/\_ \
 *                     \/            \/     \/    \/            \/
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

package org.rockbox.Helper;

import org.rockbox.RockboxFramebuffer;
import org.rockbox.RockboxService;

import android.content.Context;
import android.content.Intent;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.view.KeyEvent;

/* PodBox: receives the hardware media keys while RockboxActivity is not in
 * front (screen off). Android 12 delivers MEDIA_BUTTON broadcasts to the
 * legacy receiver without the KeyEvent, so a real MediaSession is needed. */
public class PodBoxMediaSession
{
    private final MediaSession session;

    public PodBoxMediaSession(Context c)
    {
        session = new MediaSession(c, "PodBox");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                       | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback()
        {
            @Override
            public boolean onMediaButtonEvent(Intent intent)
            {
                KeyEvent key = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                RockboxService s = RockboxService.getInstance();
                if (key != null && s != null && s.isRockboxRunning())
                    RockboxFramebuffer.backgroundKey(key);
                return true;
            }
        });
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE
                          | PlaybackState.ACTION_PLAY
                          | PlaybackState.ACTION_PAUSE
                          | PlaybackState.ACTION_STOP
                          | PlaybackState.ACTION_SKIP_TO_NEXT
                          | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(PlaybackState.STATE_PLAYING, 0, 1.0f)
                .build());
        session.setActive(true);
    }

    public void release()
    {
        session.release();
    }
}
