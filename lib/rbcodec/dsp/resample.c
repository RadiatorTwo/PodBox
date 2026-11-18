/***************************************************************************
 *             __________               __   ___.
 *   Open      \______   \ ____   ____ |  | _\_ |__   _______  ___
 *   Source     |       _//  _ \_/ ___\|  |/ /| __ \ /  _ \  \/  /
 *   Jukebox    |    |   (  <_> )  \___|    < | \_\ (  <_> > <  <
 *   Firmware   |____|_  /\____/ \___  >__|_ \|___  /\____/__/\_ \
 *                     \/            \/     \/    \/            \/
 *
 * Copyright (C) 2005 Miika Pekkarinen
 * Copyright (C) 2012 Michael Sevakis
 * Copyright (C) 2013 Michael Giacomelli
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
#include "rbcodecconfig.h"
#include "platform.h"
#include "fracmul.h"
#include "fixedpoint.h"
#include "dsp_proc_entry.h"
#include "dsp_misc.h"
#include "resample.h"
#include <string.h>

/**
 * Linear interpolation resampling that introduces a one sample delay because
 * of our inability to look into the future at the end of a frame.
 */

#if 1 /* Set to '0' to enable debug messages */
#undef DEBUGF
#define DEBUGF(...)
#endif

#define RESAMPLE_BUF_COUNT 192 /* Per channel, per DSP */

/* CODEC_IDX_AUDIO = left and right, CODEC_IDX_VOICE = mono */
static int32_t resample_out_bufs[3][RESAMPLE_BUF_COUNT] IBSS_ATTR;

/* Data for each resampler on each DSP */
static struct resample_data
{
    uint32_t delta;         /* 00h: Phase delta for each step in s15.16*/
    uint32_t phase;         /* 04h: Current phase [pos16|frac16] */
    int32_t  history[2][3]; /* 08h: Last samples for interpolation (L+R)
                                    0 = oldest, 2 = newest */
                            /* 20h */
    unsigned int frequency;         /* Virtual input samplerate */
    unsigned int frequency_out;     /* Resampler output samplerate */
    struct dsp_buffer resample_buf; /* Buffer descriptor for resampled data */
    int32_t *resample_out_p[2];     /* Actual output buffer pointers */
    bool halfband;                  /* PodBox: exact 2:1, see below */
} resample_data[DSP_COUNT] IBSS_ATTR;

/**
 * PodBox: exact 2:1 downsampling (352.8 -> 176.4 kHz, 384 -> 192 kHz, or
 * 88.2/96 -> 44.1/48 kHz with a fixed output rate) with a half-band FIR
 * instead of the Hermite interpolation, which does not filter and lets
 * everything above the new Nyquist frequency fold back.
 *
 * 95 taps, Kaiser window (beta 10.06): flat (< 0.01 dB) up to 0.216 of the
 * input rate, -6 dB at 0.25, below -97 dB from 0.284 on. Only every other
 * tap is non-zero; the centre tap is 0.5, the others are listed here
 * (offsets 1, 3, 5, ... 47 from the centre, Q31, sum = 0.5 for unity gain).
 * Only for the audio DSP (stereo).
 */
#define HB_TAPS 95
#define HB_HIST (HB_TAPS - 1)
static const int32_t hb_coefs[(HB_TAPS + 1) / 4] =
{
    682089429, -223462110, 129504240, -87791231, 63657206, -47678004,
    36245306, -27680834, 21093533, -15959124, 11940836, -8804798,
    6377219, -4521716, 3126990, -2100050, 1362431, -848021,
    501689, -278257, 141564, -63503, 22990, -4874,
};

/* input history + new input of one call, per channel */
static int32_t hb_buf[2][HB_HIST + 2 * RESAMPLE_BUF_COUNT];
static int hb_fill; /* samples in hb_buf[ch] (same for both channels) */

static void halfband_flush(void)
{
    memset(hb_buf, 0, sizeof (hb_buf));
    hb_fill = HB_HIST; /* start with silence as history */
}

/* Consumes up to 2 * dst->bufcount input samples and writes one output
   sample for every two; returns the number of input samples consumed */
static int resample_halfband(struct dsp_buffer *src, struct dsp_buffer *dst)
{
    int ch = src->format.num_channels - 1;
    int count = MIN(src->remcount, 2 * dst->bufcount);
    int fill = hb_fill;
    int outcount = 0;

    do
    {
        int32_t *x = hb_buf[ch];
        int32_t *d = dst->p32[ch];
        int n = fill;

        memcpy(x + n, src->p32[ch], count * sizeof (int32_t));
        n += count;

        /* window x[j .. j + HB_TAPS - 1], centre x[j + HB_HIST/2] */
        int j;
        for (j = 0; j + HB_TAPS <= n; j += 2)
        {
            const int32_t *c = x + j + HB_HIST / 2;
            int64_t acc = (int64_t)c[0] << 30; /* centre tap 0.5 */

            for (int k = 0; k < (HB_TAPS + 1) / 4; k++)
            {
                int o = 2 * k + 1;
                acc += (int64_t)hb_coefs[k] * ((int64_t)c[-o] + c[o]);
            }

            *d++ = (int32_t)((acc + (1LL << 30)) >> 31);
        }

        outcount = d - dst->p32[ch];

        /* keep what the next windows need */
        memmove(x, x + j, (n - j) * sizeof (int32_t));
        if (ch == 0)
            hb_fill = n - j;
    }
    while (--ch >= 0);

    dst->remcount = outcount;
    return count;
}

/* Actual worker function. Implemented here or in target assembly code. */
int resample_hermite(struct resample_data *data, struct dsp_buffer *src,
                     struct dsp_buffer *dst);

static void resample_flush_data(struct resample_data *data)
{
    data->phase = 0;
    memset(&data->history, 0, sizeof (data->history));
}

static void resample_flush(struct dsp_proc_entry *this)
{
    struct resample_data *data = (void *)this->data;
    data->resample_buf.remcount = 0;
    resample_flush_data(data);
    if (data->halfband)
        halfband_flush();
}

static bool resample_new_delta(struct resample_data *data,
                               struct sample_format *format,
                               unsigned int fout)
{
    unsigned int frequency = format->frequency; /* virtual samplerate */

    data->frequency = frequency;
    data->frequency_out = fout;
    data->delta = fp_div(frequency, fout, 16);

    /* PodBox: exact 2:1 on the audio DSP -> half-band decimator */
    bool hb = data == &resample_data[CODEC_IDX_AUDIO] && frequency == 2 * fout;
    if (hb && !data->halfband)
        halfband_flush();
    data->halfband = hb;

    if (frequency == data->frequency_out)
    {
        /* NOTE: If fully glitch-free transistions from no resampling to
           resampling are desired, history should be maintained even when
           not resampling. */
        resample_flush_data(data);
        return false;
    }

    return true;
}

#if (!defined(CPU_COLDFIRE) && !defined(CPU_ARM)) || defined(CPU_ARM_MICRO)
int resample_hermite(struct resample_data *data, struct dsp_buffer *src,
                     struct dsp_buffer *dst)
{
    int ch = src->format.num_channels - 1;
    uint32_t count = MIN(src->remcount, 0x8000);
    uint32_t delta = data->delta;
    uint32_t phase, pos;
    int32_t *d;

    do
    {
        const int32_t *s = src->p32[ch];

        d = dst->p32[ch];
        int32_t *dmax = d + dst->bufcount;

        /* Restore state */
        phase = data->phase;
        pos = phase >> 16;
        pos = MIN(pos, count);

        while (pos < count && d < dmax)
        {
            int x0, x1, x2, x3;

            if (pos < 3)
            {
                x3 = data->history[ch][pos+0];
                x2 = pos < 2 ? data->history[ch][pos+1] : s[pos-2];
                x1 = pos < 1 ? data->history[ch][pos+2] : s[pos-1];
            }
            else
            {
                x3 = s[pos-3];
                x2 = s[pos-2];
                x1 = s[pos-1];
            }

            x0 = s[pos];

            int32_t frac = (phase & 0xffff) << 15;

            /* 4-point, 3rd-order Hermite/Catmull-Rom spline (x-form):
             * c1 = -0.5*x3 + 0.5*x1
             *    = 0.5*(x1 - x3)                <--
             *
             * v = x1 - x2, -v = x2 - x1
             * c2 = x3 - 2.5*x2 + 2*x1 - 0.5*x0
             *    = x3 + 2*(x1 - x2) - 0.5*(x0 + x2)
             *    = x3 + 2*v - 0.5*(x0 + x2)     <--
             *
             * c3 = -0.5*x3 + 1.5*x2 - 1.5*x1 + 0.5*x0
             *    = 0.5*x0 - 0.5*x3 + 0.5*(x2 - x1) + (x2 - x1)
             *    = 0.5*(x0 - x3 - v) - v        <--
             *
             * polynomial coefficients */
            int32_t c1 = (x1 - x3) >> 1;
            int32_t v = x1 - x2;
            int32_t c2 = x3 + 2*v - ((x0 + x2) >> 1);
            int32_t c3 = ((x0 - x3 - v) >> 1) - v;

            /* Evaluate polynomial at time 'frac'; Horner's rule. */
            int32_t acc;
            acc = FRACMUL(c3, frac) + c2;
            acc = FRACMUL(acc, frac) + c1;
            acc = FRACMUL(acc, frac) + x2;

            *d++ = acc;

            phase += delta;
            pos = phase >> 16;
        }

        pos = MIN(pos, count);

        /* Save delay samples for next time. Must do this even if pos was
         * clamped before loop in order to keep record up to date. */
        data->history[ch][0] = pos < 3 ? data->history[ch][pos+0] : s[pos-3];
        data->history[ch][1] = pos < 2 ? data->history[ch][pos+1] : s[pos-2];
        data->history[ch][2] = pos < 1 ? data->history[ch][pos+2] : s[pos-1];
    }
    while (--ch >= 0);

    /* Wrap phase accumulator back to start of next frame. */
    data->phase = phase - (pos << 16);

    dst->remcount = d - dst->p32[0];
    return pos;
}
#endif /* CPU */

/* Resample count stereo samples or stop when the destination is full.
 * Updates the src buffer and changes to its own output buffer to refer to
 * the resampled data. */
static void resample_process(struct dsp_proc_entry *this,
                             struct dsp_buffer **buf_p)
{
    struct resample_data *data = (void *)this->data;
    struct dsp_buffer *src = *buf_p;
    struct dsp_buffer *dst = &data->resample_buf;

    *buf_p = dst;

    if (dst->remcount > 0)
        return; /* data still remains */

    dst->remcount = 0;
    dst->p32[0] = data->resample_out_p[0];
    dst->p32[1] = data->resample_out_p[1];

    if (src->remcount > 0)
    {
        dst->bufcount = RESAMPLE_BUF_COUNT;

        int consumed = data->halfband ?
            resample_halfband(src, dst) : resample_hermite(data, src, dst);

        /* Advance src by consumed amount */
        if (consumed > 0)
            dsp_advance_buffer32(src, consumed);
    }
    /* else purged resample_buf */

    /* Inherit in-place processed mask from source buffer */
    dst->proc_mask = src->proc_mask;
}

/* Finish draining old samples then switch format or shut off */
static intptr_t resample_new_format(struct dsp_proc_entry *this,
                                    struct dsp_config *dsp,
                                    struct sample_format *format)
{
    struct resample_data *data = (void *)this->data;
    struct dsp_buffer *dst = &data->resample_buf;

    if (dst->remcount > 0)
        return PROC_NEW_FORMAT_TRANSITION;

    DSP_PRINT_FORMAT(DSP_PROC_RESAMPLE, *format);

    unsigned int frequency = data->frequency;
    unsigned int fout = dsp_get_output_frequency(dsp);
    bool active = dsp_proc_active(dsp, DSP_PROC_RESAMPLE);

    if ((unsigned int)format->frequency != frequency ||
        data->frequency_out != fout)
    {
        DEBUGF("  DSP_PROC_RESAMPLE- new settings: %u %u\n",
               format->frequency, fout);
        active = resample_new_delta(data, format, fout);
        dsp_proc_activate(dsp, DSP_PROC_RESAMPLE, active);
    }

    /* Everything after us is fout */
    dst->format = *format;
    dst->format.frequency = fout;
    dst->format.codec_frequency = fout;

    if (active)
        return PROC_NEW_FORMAT_OK;

    /* No longer needed */
    DEBUGF("  DSP_PROC_RESAMPLE- deactivated\n");
    return PROC_NEW_FORMAT_DEACTIVATED;
}

void dsp_resample_init(struct dsp_config *dsp, unsigned int dsp_id)
{
    int32_t *lbuf, *rbuf;

    switch (dsp_id)
    {
    case CODEC_IDX_AUDIO:
        lbuf = resample_out_bufs[0];
        rbuf = resample_out_bufs[1];
        break;

    case CODEC_IDX_VOICE:
        lbuf = rbuf = resample_out_bufs[2]; /* Always mono */
        break;

    default:
        /* huh? */
        DEBUGF("DSP_PROC_RESAMPLE- unknown DSP %u\n", dsp_id);
        return;
    }

    /* Always enable resampler so that format changes may be monitored and
     * it self-activated when required */
    dsp_proc_enable(dsp, DSP_PROC_RESAMPLE, true);
    resample_data[dsp_id].resample_out_p[0] = lbuf;
    resample_data[dsp_id].resample_out_p[1] = rbuf;
}

static void resample_proc_init(struct dsp_proc_entry *this,
                               struct dsp_config *dsp)
{
    struct resample_data *data = &resample_data[dsp_get_id(dsp)];
    this->data = (intptr_t)data;
    dsp_proc_set_in_place(dsp, DSP_PROC_RESAMPLE, false);
    data->frequency_out = DSP_OUT_DEFAULT_HZ;
    this->process = resample_process;
}

/* DSP message hook */
static intptr_t resample_configure(struct dsp_proc_entry *this,
                                   struct dsp_config *dsp,
                                   unsigned int setting,
                                   intptr_t value)
{
    intptr_t retval = 0;

    switch (setting)
    {
    case DSP_FLUSH:
        resample_flush(this);
        break;

    case DSP_PROC_INIT:
        resample_proc_init(this, dsp);
        break;

    case DSP_PROC_CLOSE:
        /* This stage should be enabled at all times */
        DEBUGF("DSP_PROC_RESAMPLE- Error: Closing!\n");
        break;

    case DSP_PROC_NEW_FORMAT:
        retval = resample_new_format(this, dsp, (struct sample_format *)value);
        break;

    case DSP_SET_OUT_FREQUENCY:
        dsp_proc_want_format_update(dsp, DSP_PROC_RESAMPLE);
        break;
    }

    return retval;
}

/* Database entry */
DSP_PROC_DB_ENTRY(RESAMPLE,
                  resample_configure);
