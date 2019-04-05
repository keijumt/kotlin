/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package kotlin.time

import java.util.*

internal actual fun formatToDecimals(value: Double, decimals: Int, unitName: String): String =
    String.format(Locale.ROOT, "%.${decimals}f%s", value, unitName)