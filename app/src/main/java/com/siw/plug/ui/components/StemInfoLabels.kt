/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.siw.plug.ui.components

import com.siw.plug.R
import com.siw.plug.logic.utils.automix.stems.StemAvailability

internal fun stemAvailabilityLabelRes(availability: StemAvailability): Int = when (availability) {
    StemAvailability.DISABLED -> R.string.stem_status_disabled
    StemAvailability.READY -> R.string.stem_status_ready
    StemAvailability.PROCESSING -> R.string.stem_status_processing
    StemAvailability.NOT_CACHED -> R.string.stem_status_not_cached
    StemAvailability.FAILED -> R.string.stem_status_failed
}
