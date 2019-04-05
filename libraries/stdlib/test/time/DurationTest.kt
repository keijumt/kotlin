/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package test.time

import test.numbers.assertAlmostEquals
import kotlin.test.*
import kotlin.time.*
import kotlin.random.*

private val expectStorageUnit = DurationUnit.MICROSECONDS
private val units = DurationUnit.values()

class DurationTest {

    // construction white-box testing
    @Test
    fun construction() {

        repeat(100) {
            val value = Random.nextInt(1_000_000)
            val unit = units.random()
            val expected = convertDurationUnit(value.toDouble(), unit, expectStorageUnit)
            assertEquals(expected, Duration(value, unit)._value)
            assertEquals(expected, Duration(value.toLong(), unit)._value)
            assertEquals(expected, Duration(value.toDouble(), unit)._value)
        }

        todo {
            assertFails { Duration(Double.NaN, DurationUnit.SECONDS) }
        }
    }

    // equality white-box testing
    @Test
    fun equality() {
        val data = listOf<Pair<Double, DurationUnit>>(
            Pair(2.0, DurationUnit.DAYS),
            Pair(2.0, DurationUnit.HOURS),
            Pair(0.25, DurationUnit.MINUTES),
            Pair(1.0, DurationUnit.SECONDS),
            Pair(50.0, DurationUnit.MILLISECONDS),
            Pair(0.3, DurationUnit.MICROSECONDS),
            Pair(20_000_000_000.0, DurationUnit.NANOSECONDS),
            Pair(1.0, DurationUnit.NANOSECONDS)
        )

        for ((value, unit) in data) {
            repeat(10) {
                val d1 = Duration(value, unit)
                val unit2 = units.random()
                val value2 = convertDurationUnit(value, unit, unit2)
                val d2 = Duration(value2, unit2)
                assertEquals(d1, d2, "$value $unit in $unit2")

                val d3 = Duration(value2 * 2, unit2)
                assertNotEquals(d1, d3, "$value $unit in $unit2")
            }
        }
    }


    @Test
    fun conversionFromNumber() {
        val n1 = Random.nextInt(Int.MAX_VALUE)
        val n2 = Random.nextLong(Long.MAX_VALUE)
        val n3 = Random.nextDouble()

        assertEquals(Duration(n1, DurationUnit.DAYS), n1.days)
        assertEquals(Duration(n2, DurationUnit.DAYS), n2.days)
        assertEquals(Duration(n3, DurationUnit.DAYS), n3.days)

        assertEquals(Duration(n1, DurationUnit.HOURS), n1.hours)
        assertEquals(Duration(n2, DurationUnit.HOURS), n2.hours)
        assertEquals(Duration(n3, DurationUnit.HOURS), n3.hours)

        assertEquals(Duration(n1, DurationUnit.MINUTES), n1.minutes)
        assertEquals(Duration(n2, DurationUnit.MINUTES), n2.minutes)
        assertEquals(Duration(n3, DurationUnit.MINUTES), n3.minutes)

        assertEquals(Duration(n1, DurationUnit.SECONDS), n1.seconds)
        assertEquals(Duration(n2, DurationUnit.SECONDS), n2.seconds)
        assertEquals(Duration(n3, DurationUnit.SECONDS), n3.seconds)

        assertEquals(Duration(n1, DurationUnit.MILLISECONDS), n1.milliseconds)
        assertEquals(Duration(n2, DurationUnit.MILLISECONDS), n2.milliseconds)
        assertEquals(Duration(n3, DurationUnit.MILLISECONDS), n3.milliseconds)

        assertEquals(Duration(n1, DurationUnit.MICROSECONDS), n1.microseconds)
        assertEquals(Duration(n2, DurationUnit.MICROSECONDS), n2.microseconds)
        assertEquals(Duration(n3, DurationUnit.MICROSECONDS), n3.microseconds)

        assertEquals(Duration(n1, DurationUnit.NANOSECONDS), n1.nanoseconds)
        assertEquals(Duration(n2, DurationUnit.NANOSECONDS), n2.nanoseconds)
        assertEquals(Duration(n3, DurationUnit.NANOSECONDS), n3.nanoseconds)
    }

    @Test
    fun conversionToNumber() {
        assertEquals(24.0, 1.days.inHours)
        assertEquals(0.5, 12.hours.inDays)
        assertEquals(15.0, 0.25.hours.inMinutes)
        assertEquals(600.0, 10.minutes.inSeconds)
        assertEquals(500.0, 0.5.seconds.inMilliseconds)
        assertEquals(50_000.0, 0.05.seconds.inMicroseconds)
        assertEquals(50_000.0, 0.05.milliseconds.inNanoseconds)

        assertEquals(365 * 86400e9, 365.days.inNanoseconds)

        assertEquals(0.0, Duration.ZERO.inNanoseconds)

        repeat(100) {
            val value = Random.nextLong(1000)
            val unit = units.random()
            val unit2 = units.random()

            assertAlmostEquals(convertDurationUnit(value.toDouble(), unit, unit2), Duration(value, unit).inUnits(unit2))
        }
    }

    @Test
    fun infinite() {
        assertTrue(Duration.INFINITE.isInfinite())
        assertTrue((-Duration.INFINITE).isInfinite())
        assertTrue(Double.POSITIVE_INFINITY.nanoseconds.isInfinite())

        assertTrue(Duration.MAX_VALUE.isFinite())
        assertTrue(Duration.MIN_VALUE.isFinite())

        assertFalse((Duration.MAX_VALUE * 2).isFinite())
        assertFalse((Duration.MIN_VALUE * 2).isFinite())

        assertTrue(Double.MAX_VALUE.seconds.isInfinite()) // days overflow to infinite
        assertTrue((-Double.MAX_VALUE).seconds.isInfinite()) // days overflow to infinite
    }


    @Test
    fun negation() {
        repeat(100) {
            val value = Random.nextLong()
            val unit = units.random()

            assertEquals(Duration(-value, unit), -Duration(value, unit))
        }
    }

    @Test
    fun isNegativeAndAbsoluteValue() {
        val negative = -1.seconds
        val positive = 1.seconds
        val zero = Duration.ZERO

        assertTrue(negative.isNegative())
        assertFalse(zero.isNegative())
        assertFalse(positive.isNegative())

        assertEquals(positive, negative.absoluteValue())
        assertEquals(positive, positive.absoluteValue())
        assertEquals(zero, zero.absoluteValue())
    }


    @Test
    fun addition() {
        assertEquals(1.5.hours, 1.hours + 30.minutes)
        assertEquals(0.5.days, 6.hours + 360.minutes)
        assertEquals(0.5.seconds, 200.milliseconds + 300_000.microseconds)
    }

    @Test
    fun subtraction() {
        assertEquals(10.hours, 0.5.days - 120.minutes)
        assertEquals(850.milliseconds, 1.seconds - 150.milliseconds)
    }

    @Test
    fun multiplication() {
        assertEquals(1.days, 12.hours * 2)
        assertEquals(1.days, 60.minutes * 24.0)
        assertEquals(1.microseconds, 20.nanoseconds * 50)
    }

    @Test
    fun divisionByNumber() {
        assertEquals(12.hours, 1.days / 2)
        assertEquals(60.minutes, 1.days / 24.0)
        assertEquals(20.seconds, 2.minutes / 6)
    }

    @Test
    fun divisionByDuration() {
        assertEquals(24.0, 1.days / 1.hours)
        assertEquals(0.1, 9.minutes / 1.5.hours)
        assertEquals(50.0, 1.microseconds / 20.nanoseconds)
    }

}