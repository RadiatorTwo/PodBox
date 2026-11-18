/***************************************************************************
 *             __________               __   ___.
 *   Open      \______   \ ____   ____ |  | _\_ |__   _______  ___
 *   Source     |       _//  _ \_/ ___\|  |/ /| __ \ /  _ \  \/  /
 *   Jukebox    |    |   (  <_> )  \___|    < | \_\ (  <_> > <  <
 *   Firmware   |____|_  /\____/ \___  >__|_ \|___  /\____/__/\_ \
 *                     \/            \/     \/    \/            \/
 *
 * PodBox: "Other apps" entry in the main menu. PodBox can be the Android
 * home screen; this opens the other home screen (the manufacturer's
 * launcher) so other apps stay reachable.
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

#include <jni.h>
#include <stdbool.h>
#include "kernel.h"
#include "splash.h"
#include "lang.h"
#include "language.h"
#include "launcher.h"

extern JNIEnv *env_ptr;
extern jclass RockboxService_class;
extern jobject RockboxService_instance;

int android_open_other_launcher(void)
{
    static jmethodID open_launcher;
    JNIEnv e = *env_ptr;

    if (!open_launcher)
        open_launcher = e->GetMethodID(env_ptr, RockboxService_class,
                                       "openOtherLauncher", "()Z");

    if (!open_launcher || !e->CallBooleanMethod(env_ptr, RockboxService_instance,
                                                open_launcher))
        splash(HZ*2, str(LANG_PODBOX_NO_OTHER_LAUNCHER));

    return 0; /* stay in the main menu */
}
