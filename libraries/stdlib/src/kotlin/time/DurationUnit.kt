/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

@file:kotlin.jvm.JvmMultifileClass()
@file:kotlin.jvm.JvmName("DurationUnitKt")

package kotlin.time


public enum class DurationUnit(internal val scale: Double) {
    /**
     * Time unit representing one nanosecond, which is 1/1000 of a microsecond.
     */
    NANOSECONDS(1.0),
    /**
     * Time unit representing one microsecond, which is 1/1000 of a millisecond.
     */
    MICROSECONDS(1000.0),
    /**
     * Time unit representing one millisecond, which is 1/1000 of a second.
     */
    MILLISECONDS(1e6),
    /**
     * Time unit representing one second.
     */
    SECONDS(1e9),
    /**
     * Time unit representing one minute.
     */
    MINUTES(60e9),
    /**
     * Time unit representing one hour.
     */
    HOURS(3600e9),
    /**
     * Time unit representing one day, which always equals 24 hours.
     */
    DAYS(86400e9);
}


public fun convertDurationUnit(value: Double, sourceUnit: DurationUnit, targetUnit: DurationUnit): Double {
    val sourceCompareTarget = sourceUnit.scale.compareTo(targetUnit.scale)
    return when {
        sourceCompareTarget > 0 -> value * (sourceUnit.scale / targetUnit.scale)
        sourceCompareTarget < 0 -> value / (targetUnit.scale / sourceUnit.scale)
        else -> value
    }
}



internal fun DurationUnit.shortName(): String = when (this) {
    DurationUnit.NANOSECONDS -> "ns"
    DurationUnit.MICROSECONDS -> "us"
    DurationUnit.MILLISECONDS -> "ms"
    DurationUnit.SECONDS -> "s"
    DurationUnit.MINUTES -> "m"
    DurationUnit.HOURS -> "h"
    DurationUnit.DAYS -> "d"
}