/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.siw.plug.logic.utils.automix

internal object PcmTimestampContinuity {
    const val TOLERANCE_US = 20_000L

    fun canDiscardInitialBuffer(
        expectedUs: Long,
        actualUs: Long,
        rate: Int,
        encoderDelay: Int,
        sampleCount: Int,
        seekUs: Long,
        firstBufferOnly: Boolean,
    ): Boolean {
        if (rate <= 0 || seekUs <= 0 || encoderDelay <= 0 || sampleCount <= 0 ||
            sampleCount > encoderDelay || !firstBufferOnly
        ) return false
        val gapUs = actualUs - expectedUs
        val maxDelayUs = encoderDelay.toLong() * 1_000_000L / rate + 2_000L
        return gapUs > TOLERANCE_US && gapUs <= maxDelayUs
    }
}
