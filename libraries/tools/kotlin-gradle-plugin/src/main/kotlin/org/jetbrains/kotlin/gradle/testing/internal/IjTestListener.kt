/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.testing.internal

import org.gradle.api.internal.tasks.testing.TestDescriptorInternal
import org.gradle.api.tasks.testing.*
import org.gradle.api.tasks.testing.TestResult.ResultType.*
import java.util.*

class IjTestListener : TestListener, TestOutputListener {
    private val TestDescriptor.id
        get() = (this as TestDescriptorInternal).id.toString()

    private val TestDescriptor.parentId
        get() = (parent as TestDescriptorInternal?)?.id?.toString() ?: ""

    fun ijSend(message: String) {
        println(message)
    }

    fun shouldReport(suite: TestDescriptor) = true //suite.parent != null

    val parents = mutableListOf<String>("")

    override fun beforeSuite(suite: TestDescriptor) {
        if (shouldReport(suite)) {
            suite as TestDescriptorInternal
            val id = suite.displayName
            ijSend(ijSuiteStart(parents[parents.lastIndex], id, suite.displayName))
            parents.add(id)
        }
    }

    override fun afterSuite(suite: TestDescriptor, result: TestResult) {
        if (shouldReport(suite)) {
            ijSend(
                ijLogFinish(
                    parents[parents.lastIndex - 1],
                    parents[parents.lastIndex],
                    when (result.resultType) {
                        null, SUCCESS -> "SUCCESS"
                        FAILURE -> "FAILURE"
                        SKIPPED -> "SKIPPED"
                    },
                    result.startTime,
                    result.endTime
                )
            )
            parents.removeAt(parents.lastIndex)
        }
    }

    override fun beforeTest(testDescriptor: TestDescriptor) {
        testDescriptor as TestDescriptorInternal

        ijSend(
            ijTestStart(
                parents.last(),
                testDescriptor.id.toString(),
                testDescriptor.className ?: "",
                testDescriptor.name
            )
        )
    }

    override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
        ijSend(
            ijLogFinish(
                testDescriptor.parentId,
                testDescriptor.id,
                when (result.resultType) {
                    null, SUCCESS -> "SUCCESS"
                    FAILURE -> "FAILURE"
                    SKIPPED -> "SKIPPED"
                },
                result.startTime,
                result.endTime
            )
        )
    }

    override fun onOutput(testDescriptor: TestDescriptor, outputEvent: TestOutputEvent) {
//        ijSend(
//            ijLogOutput(
//                testDescriptor.parentId,
//                testDescriptor.id,
//                outputEvent.message,
//                when (outputEvent.destination) {
//                    null, StdOut -> "StdOut"
//                    StdErr -> "StdErr"
//                }
//            )
//        )
    }

    private fun ijTestStart(parent: String, id: String, className: String, methodName: String) = buildString {
        append("<ijLog>")
        append("<event type='beforeTest'>")
        append("<test id='$id' parentId='$parent'>")
        append("<descriptor name='$methodName' className='$className' />")
        append("</test>")
        append("</event>")
        append("</ijLog>")
    }

    private fun ijSuiteStart(parent: String, id: String, name: String) = buildString {
        append("<ijLog>")
        append("<event type='beforeTest'>")
        append("<test id='$id' parentId='$parent'>")
        append("<descriptor name='$name'/>")
        append("</test>")
        append("</event>")
        append("</ijLog>")
    }

    private fun ijLogFinish(
        parent: String,
        id: String,
        resultType: String,
        startTime: Long,
        endTime: Long
    ) = buildString {
        append("<ijLog>")
        append("<event type='afterTest'>")
        append("<test id='$id' parentId='$parent'>")
        append("<result resultType='$resultType' startTime='$startTime' endTime='$endTime'/>")
        append("</test>")
        append("</event>")
        append("</ijLog>")
    }

    private fun ijLogOutput(parent: String, id: String, info: String, dest: String) = buildString {
        append("<ijLog>")
        append("<event type='onOutput'>")
        append("<test id='$id' parentId='$parent'>")
        append("<event destination='$dest'>")
        append("<![CDATA[${info.toByteArray().base64()}]]>")
        append("</event>")
        append("</test>")
        append("</event>")
        append("</ijLog>")
    }

    private fun ByteArray.base64() = Base64.getEncoder().encode(this)
}