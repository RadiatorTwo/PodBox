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
 *****************************************************************************/


#include "button.h"
#include "android_keyevents.h"

static bool ignore_back_button = false;
void android_ignore_back_button(bool yes)
{
    ignore_back_button = yes;
}

int key_to_button(int keyboard_key)
{
    switch (keyboard_key)
    {
        case KEYCODE_BACK:
            return ignore_back_button ? BUTTON_NONE : BUTTON_BACK;
        case KEYCODE_MENU:
            return BUTTON_MENU;
        case KEYCODE_DPAD_CENTER:
            return BUTTON_DPAD_CENTER;
        /* PodBox / Surfans F20 Pro+ hardware (vendor Generic.kl):
         *   wheel button       -> KEYCODE_CAMERA         = select
         *   right side, top    -> KEYCODE_MEDIA_PREVIOUS = back
         *   right side, bottom -> KEYCODE_MEDIA_NEXT     = menu/context
         * (middle key PLAY_PAUSE is handled as multimedia key, the
         *  short/long press distinction is done in RockboxFramebuffer.java) */
        case KEYCODE_CAMERA:
            return BUTTON_DPAD_CENTER;
        case KEYCODE_MEDIA_PREVIOUS:
            return BUTTON_BACK;
        case KEYCODE_MEDIA_NEXT:
            return BUTTON_MENU;
        default:
            return BUTTON_NONE;
    }
}

/* PodBox: the scroll wheel reports one VOLUME_UP (clockwise) or
 * VOLUME_DOWN (counter-clockwise) key press per detent. Each detent is
 * posted as a single event, like the dpad keys below. */
unsigned wheel_to_button(int keyboard_key)
{
    switch (keyboard_key)
    {
        case KEYCODE_VOLUME_UP:
            return BUTTON_DPAD_DOWN|BUTTON_REL;
        case KEYCODE_VOLUME_DOWN:
            return BUTTON_DPAD_UP|BUTTON_REL;
        default:
            return BUTTON_NONE;
    }
}

unsigned multimedia_to_button(int keyboard_key)
{
    switch (keyboard_key)
    {
        case KEYCODE_MEDIA_PLAY_PAUSE:
            return BUTTON_MULTIMEDIA_PLAYPAUSE;
        case KEYCODE_MEDIA_STOP:
            return BUTTON_MULTIMEDIA_STOP;
        /* MEDIA_NEXT / MEDIA_PREVIOUS are hardware keys on PodBox,
         * see key_to_button() */
        case KEYCODE_MEDIA_REWIND:
            return BUTTON_MULTIMEDIA_REW;
        case KEYCODE_MEDIA_FAST_FORWARD:
            return BUTTON_MULTIMEDIA_FFWD;
        default:
            return 0;
    }
}

unsigned dpad_to_button(int keyboard_key)
{
    switch (keyboard_key)
    {
        /* These buttons only post a single release event.
         * doing otherwise will cause action.c to lock up waiting for
         * a release (because android sends press/unpress to us too quickly
         */
        case KEYCODE_DPAD_UP:
            return BUTTON_DPAD_UP|BUTTON_REL;
        case KEYCODE_DPAD_DOWN:
            return BUTTON_DPAD_DOWN|BUTTON_REL;
        case KEYCODE_DPAD_LEFT:
            return BUTTON_DPAD_LEFT|BUTTON_REL;
        case KEYCODE_DPAD_RIGHT:
            return BUTTON_DPAD_RIGHT|BUTTON_REL;
        default:
            return BUTTON_NONE;
    }
}
    
