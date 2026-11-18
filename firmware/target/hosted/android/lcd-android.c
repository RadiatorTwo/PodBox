/***************************************************************************
 *             __________               __   ___.
 *   Open      \______   \ ____   ____ |  | _\_ |__   _______  ___
 *   Source     |       _//  _ \_/ ___\|  |/ /| __ \ /  _ \  \/  /
 *   Jukebox    |    |   (  <_> )  \___|    < | \_\ (  <_> > <  <
 *   Firmware   |____|_  /\____/ \___  >__|_ \|___  /\____/__/\_ \
 *                     \/            \/     \/    \/            \/
 *
 * Copyright (c) 2010 Thomas Martitz
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


/*
 * PodBox: Rockbox draws straight into the Android surface with
 * ANativeWindow instead of handing the whole framebuffer to Java on every
 * update (JNI call, 600 KB copy into a Bitmap, software Canvas draw), and
 * only the rows of the updated rectangle are copied.
 */

#include <jni.h>
#include <string.h>
#include <pthread.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include "config.h"
#include "system.h"
#include "kernel.h"
#include "lcd.h"
#include "button.h"

extern JNIEnv *env_ptr;
extern jobject RockboxService_instance;

static int dpi;
static int scroll_threshold;
static bool display_on;
static bool connected;

/* the surface, protected by window_mutex (Rockbox thread draws, the UI
 * thread creates and destroys it) */
static pthread_mutex_t window_mutex = PTHREAD_MUTEX_INITIALIZER;
static ANativeWindow *window;

/* this might actually be called before lcd_init_device() or even main(), so
 * be sure to only access static storage initalized at library loading,
 * and not more */
static void connect_with_java(JNIEnv* env, jobject fb_instance)
{
    JNIEnv e = *env;

    jclass fb_class = e->GetObjectClass(env, fb_instance);
    jmethodID get_dpi    = e->GetMethodID(env, fb_class,
                                         "getDpi", "()I");
    jmethodID thresh     = e->GetMethodID(env, fb_class,
                                         "getScrollThreshold", "()I");
    /* these don't change with new instances so call them now */
    dpi                  = e->CallIntMethod(env, fb_instance, get_dpi);
    scroll_threshold     = e->CallIntMethod(env, fb_instance, thresh);
}

/*
 * Do nothing here and connect with the java object later (if it isn't already)
 */
void lcd_init_device(void)
{
}

/* copies the rectangle (or more, if Android asks for it) to the surface */
static void copy_to_window(int x, int y, int width, int height)
{
    pthread_mutex_lock(&window_mutex);

    if (window && display_on)
    {
        ARect dirty = { .left = x, .top = y,
                        .right = x + width, .bottom = y + height };
        ANativeWindow_Buffer buf;

        if (ANativeWindow_lock(window, &buf, &dirty) == 0)
        {
            /* Android may widen the dirty area (e.g. when it can't copy
             * the previous frame back) - fill whatever it asks for */
            int l = MAX(dirty.left, 0), t = MAX(dirty.top, 0);
            int r = MIN(dirty.right, MIN(buf.width, LCD_WIDTH));
            int b = MIN(dirty.bottom, MIN(buf.height, LCD_HEIGHT));
            const fb_data *src = FBADDR(0, 0);
            uint16_t *dst = buf.bits;

            if (l == 0 && r == LCD_WIDTH && buf.stride == LCD_FBWIDTH)
            {
                memcpy(dst + t * LCD_FBWIDTH, src + t * LCD_FBWIDTH,
                       (size_t)(b - t) * LCD_WIDTH * sizeof (fb_data));
            }
            else
            {
                for (int row = t; row < b; row++)
                    memcpy(dst + row * buf.stride + l,
                           src + row * LCD_FBWIDTH + l,
                           (size_t)(r - l) * sizeof (fb_data));
            }

            ANativeWindow_unlockAndPost(window);
        }
    }

    pthread_mutex_unlock(&window_mutex);
}

void lcd_update(void)
{
    copy_to_window(0, 0, LCD_WIDTH, LCD_HEIGHT);
}

void lcd_update_rect(int x, int y, int width, int height)
{
    /* clip to the screen */
    if (x < 0) { width += x; x = 0; }
    if (y < 0) { height += y; y = 0; }
    width = MIN(width, LCD_WIDTH - x);
    height = MIN(height, LCD_HEIGHT - y);

    if (width > 0 && height > 0)
        copy_to_window(x, y, width, height);
}

/*
 * this is called when the surface is created, which called is everytime
 * the activity is brought in front and the RockboxFramebuffer gains focus
 *
 * Note this is considered interrupt context
 */
JNIEXPORT void JNICALL
Java_org_rockbox_RockboxFramebuffer_surfaceCreated(JNIEnv *env, jobject this,
                                                     jobject surfaceholder)
{
    /* possibly a new instance - reconnect */
    if (!connected)
    {
        connect_with_java(env, this);
        connected = true;
    }

    jclass holder_class = (*env)->GetObjectClass(env, surfaceholder);
    jmethodID get_surface = (*env)->GetMethodID(env, holder_class,
                                "getSurface", "()Landroid/view/Surface;");
    jobject surface = (*env)->CallObjectMethod(env, surfaceholder, get_surface);

    pthread_mutex_lock(&window_mutex);
    if (window)
        ANativeWindow_release(window);
    window = ANativeWindow_fromSurface(env, surface);
    if (window)
        ANativeWindow_setBuffersGeometry(window, LCD_WIDTH, LCD_HEIGHT,
                                         WINDOW_FORMAT_RGB_565);
    display_on = window != NULL;
    pthread_mutex_unlock(&window_mutex);

    (*env)->DeleteLocalRef(env, surface);
    (*env)->DeleteLocalRef(env, holder_class);

    /* need to wait for button_queue to be valid to post to */
    wait_rockbox_ready();

    send_event(LCD_EVENT_ACTIVATION, NULL);
    /* Force an update, since the newly created surface is initially black
     * waiting for the next normal update results in a longish black screen */
    button_queue_post(BUTTON_REDRAW, 0);
}

/*
 * the surface is destroyed everytime the RockboxFramebuffer loses focus and
 * goes invisible
 */
JNIEXPORT void JNICALL
Java_org_rockbox_RockboxFramebuffer_surfaceDestroyed(JNIEnv *e, jobject this,
                                                    jobject surfaceholder)
{
    (void)e; (void)this; (void)surfaceholder;

    pthread_mutex_lock(&window_mutex);
    display_on = false;
    if (window)
        ANativeWindow_release(window);
    window = NULL;
    pthread_mutex_unlock(&window_mutex);
}

bool lcd_active(void)
{
    return display_on;
}

int lcd_get_dpi(void)
{
    return dpi;
}

int touchscreen_get_scroll_threshold(void)
{
    return scroll_threshold;
}
