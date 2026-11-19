/***************************************************************************
 *             __________               __   ___.
 *   Open      \______   \ ____   ____ |  | _\_ |__   _______  ___
 *   Source     |       _//  _ \_/ ___\|  |/ /| __ \ /  _ \  \/  /
 *   Jukebox    |    |   (  <_> )  \___|    < | \_\ (  <_> > <  <
 *   Firmware   |____|_  /\____/ \___  >__|_ \|___  /\____/__/\_ \
 *                     \/            \/     \/    \/            \/
 *
 * Copyright © 2010 Thomas Martitz
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


#include "config.h"
#include "audiohw.h"
#include "pcm-android.h"
#include "sound.h"

void audiohw_set_volume(int volume)
{
    /* PodBox: at startup Android keeps its own volume and returns it
     * (RockboxPCM.set_volume), Rockbox takes it over. Through
     * sound_set_volume() so global_status.volume and the prescaler both
     * follow; Java is synced by then, so this recurses only once. */
    int applied = pcm_set_mixer_volume(volume);
    if (applied != volume)
        sound_set_volume(applied / 10);
}

void audiohw_close(void)
{
    pcm_shutdown();
}
