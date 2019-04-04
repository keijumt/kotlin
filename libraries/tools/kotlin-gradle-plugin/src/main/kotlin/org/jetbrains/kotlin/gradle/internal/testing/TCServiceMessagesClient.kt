/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.internal.testing

import jetbrains.buildServer.messages.serviceMessages.*
import org.gradle.api.internal.tasks.testing.*
import org.gradle.api.tasks.testing.TestOutputEvent
import org.gradle.api.tasks.testing.TestOutputEvent.Destination.StdErr
import org.gradle.api.tasks.testing.TestOutputEvent.Destination.StdOut
import org.gradle.api.tasks.testing.TestResult
import org.gradle.api.tasks.testing.TestResult.ResultType.*
import org.gradle.internal.operations.OperationIdentifier
import org.jetbrains.kotlin.gradle.logging.kotlinDebug
import org.jetbrains.kotlin.gradle.testing.KotlinTestFailure
import org.slf4j.Logger
import java.text.ParseException
import java.lang.System.currentTimeMillis as currentTimeMillis1

data class TCServiceMessagesClientSettings(
    val rootNodeName: String,
    val testNameSuffix: String? = null,
    val prepandSuiteName: Boolean = false,
    val treatFailedTestOutputAsStacktrace: Boolean = false
)

internal class TCServiceMessagesClient(
    private val results: TestResultProcessor,
    val settings: TCServiceMessagesClientSettings,
    val log: Logger
) : ServiceMessageParserCallback {
    inline fun root(operation: OperationIdentifier, actions: () -> Unit) {
        RootNode(operation.id).open {
            actions()
        }
    }

    override fun parseException(e: ParseException, text: String) {
        log.error("Failed to parse test process messages: \"$text\"", e)
    }

    override fun serviceMessage(message: ServiceMessage) {
        log.kotlinDebug { "TCSM: $message" }

        when (message) {
            is TestSuiteStarted -> open(message.ts, SuiteNode(requireLeafGroup(), message.suiteName))
            is TestStarted -> beginTest(message.ts, message.testName)
            is TestStdOut -> requireLeafTest().output(StdOut, message.stdOut)
            is TestStdErr -> requireLeafTest().output(StdErr, message.stdErr)
            is TestFailed -> requireLeafTest().failure(message)
            is TestFinished -> endTest(message.ts, message.testName)
            is TestIgnored -> {
                if (message.attributes["suite"] == "true") {
                    // non standard property for dealing with ignored test suites without visiting all inner tests
                    SuiteNode(requireLeafGroup(), message.testName).open(message.ts) { message.ts }
                } else {
                    beginTest(message.ts, message.testName, isIgnored = true)
                    endTest(message.ts, message.testName)
                }
            }
            is TestSuiteFinished -> close(message.ts, message.suiteName)
            else -> Unit
        }
    }

    override fun regularText(text: String) {
        log.kotlinDebug { "TCSM stdout captured: $text" }
        val actualText = text + "\n"

        val test = leaf as? TestNode
        if (test != null) {
            test.output(StdOut, actualText)
        } else {
            print(actualText)
        }
    }

    private fun beginTest(ts: Long, testName: String, isIgnored: Boolean = false) {
        val parent = requireLeafGroup()
        parent.requireReportingNode()

        val finalTestName = testName.let {
            if (settings.prepandSuiteName) "${parent.fullNameWithoutRoot}.$it"
            else it
        }

        val parsedName = ParsedTestName(finalTestName, parent.localId)
        val methodName = if (settings.testNameSuffix == null) parsedName.methodName
        else "${parsedName.methodName}[${settings.testNameSuffix}]"

        open(
            ts, TestNode(
                parent, parsedName.className, parsedName.classDisplayName, methodName,
                displayName = methodName,
                localId = testName,
                ignored = isIgnored
            )
        )
    }

    private fun endTest(ts: Long, testName: String) {
        close(ts, testName)
    }

    private fun TestNode.failure(
        message: TestFailed
    ) {
        hasFailures = true

        val stacktrace = buildString {
            append(message.stacktrace)

            if (settings.treatFailedTestOutputAsStacktrace) {
                append(output)
                output.setLength(0)
            }
        }

        results.failure(descriptor.id, KotlinTestFailure(message.failureMessage, stacktrace))
    }

    private fun TestNode.output(
        destination: TestOutputEvent.Destination,
        text: String
    ) {
        if (settings.treatFailedTestOutputAsStacktrace) {
            output.append(text)
        }

        results.output(descriptor.id, DefaultTestOutputEvent(destination, text))
    }

    private inline fun <NodeType : Node> NodeType.open(contents: (NodeType) -> Unit) = open(System.currentTimeMillis()) {
        contents(it)
        System.currentTimeMillis()
    }

    private inline fun <NodeType : Node> NodeType.open(tsStart: Long, contents: (NodeType) -> Long) {
        val child = open(tsStart, this@open)
        val tsEnd = contents(child)
        assert(close(tsEnd, child.localId) === child)
    }

    private fun <NodeType : Node> open(ts: Long, new: NodeType): NodeType = new.also {
        log.kotlinDebug { "Test node opened: $it" }

        it.markStarted(ts)
        push(it)
    }

    private fun close(ts: Long, assertLocalId: String?) = pop().also {
        if (assertLocalId != null) {
            check(it.localId == assertLocalId) {
                "Bad TCSM: unexpected node to close: ${it.localId}, stack: ${
                leaf.collectParents().joinToString("") { item -> "\n - ${item.localId}" }
                }\n"
            }
        }

        log.kotlinDebug { "Test node closed: $it" }
        it.markCompleted(ts)
    }

    private fun Node?.collectParents(): MutableList<Node> {
        var i = this
        val items = mutableListOf<Node>()
        while (i != null) {
            items.add(i)
            i = i.parent
        }
        return items
    }


    class ParsedTestName(testName: String, parentName: String) {
        val hasClassName: Boolean
        val className: String
        val classDisplayName: String
        val methodName: String

        init {
            val methodNameCut = testName.lastIndexOf('.')
            hasClassName = methodNameCut != -1

            if (hasClassName) {
                className = testName.substring(0, methodNameCut)
                classDisplayName = className.substringAfterLast('.')
                methodName = testName.substring(methodNameCut + 1)
            } else {
                className = parentName
                classDisplayName = parentName
                methodName = testName
            }
        }
    }

    enum class NodeState {
        created, started, completed
    }

    /**
     * Node of tests tree.
     *
     */
    abstract inner class Node(
        var parent: Node? = null,
        val localId: String
    ) {
        val id: String = if (parent != null) "${parent!!.id}/$localId" else localId

        open val cleanName: String
            get() = localId

        abstract val descriptor: TestDescriptorInternal?

        var state: NodeState = NodeState.created

        var reportingParent: GroupNode? = null
            get() {
                checkReportingNodeCreated()
                return field
            }

        private fun checkReportingNodeCreated() {
            check(descriptor != null)
        }

        var hasFailures: Boolean = false
            set(value) {
                // traverse parents only on first failure
                if (!field) {
                    field = value
                    parent?.hasFailures = true
                }
            }

        /**
         * If all tests in group are ignored, then group marked as skipped.
         * This is workaround for absence of ignored test suite flag in TC service messages protocol.
         */
        var containsNotIgnored: Boolean = false
            set(value) {
                // traverse parents only on first test
                if (!field) {
                    field = value
                    parent?.containsNotIgnored = true
                }
            }

        val resultType: TestResult.ResultType
            get() = when {
                containsNotIgnored -> when {
                    hasFailures -> FAILURE
                    else -> SUCCESS
                }
                else -> SKIPPED
            }

        override fun toString(): String = id

        abstract fun markStarted(ts: Long)
        abstract fun markCompleted(ts: Long)

        fun checkState(state: NodeState) {
            check(this.state == state) {
                "$this should be in state $state"
            }
        }

        protected fun reportStarted(ts: Long) {
            checkState(NodeState.created)
            reportingParent?.checkState(NodeState.started)

            results.started(descriptor!!, TestStartEvent(ts, reportingParent?.descriptor?.id))

            state = NodeState.started
        }

        protected fun reportCompleted(ts: Long) {
            checkState(NodeState.started)
            reportingParent?.checkState(NodeState.started)

            results.completed(descriptor!!.id, TestCompleteEvent(ts, resultType))

            state = NodeState.completed
        }
    }

    abstract inner class GroupNode(parent: Node?, localId: String) : Node(parent, localId) {
        val fullNameWithoutRoot: String
            get() = collectParents().dropLast(1)
                .reversed()
                .map { it.localId }
                .filter { it.isNotBlank() }
                .joinToString(".") { it }

        abstract fun requireReportingNode(): TestDescriptorInternal
    }

    inner class RootNode(val ownerBuildOperationId: Any) : GroupNode(null, settings.rootNodeName) {
        override val descriptor: TestDescriptorInternal = object : DefaultTestSuiteDescriptor(settings.rootNodeName, localId) {
            override fun getOwnerBuildOperationId(): Any? = this@RootNode.ownerBuildOperationId
        }

        override fun requireReportingNode(): TestDescriptorInternal = descriptor

        override fun markStarted(ts: Long) {
            reportStarted(ts)
        }

        override fun markCompleted(ts: Long) {
            reportCompleted(ts)
        }
    }

    fun cleanName(parent: GroupNode, name: String): String {
        // Some test reporters may report test suite in name (Kotlin/Native)
        val parentName = parent.fullNameWithoutRoot
        return name.removePrefix("$parentName.")
    }

    inner class SuiteNode(parent: GroupNode, name: String) : GroupNode(parent, name) {
        override val cleanName = cleanName(parent, name)

        private var shouldReportComplete = false

        override var descriptor: TestDescriptorInternal? = null
            private set

        override fun requireReportingNode(): TestDescriptorInternal = descriptor ?: createReportingNode()

        /**
         * Called when first test in suite started
         */
        private fun createReportingNode(): TestDescriptorInternal {
            val parents = collectParents()
            val fullName = parents.reversed()
                .map { it.cleanName }
                .filter { it.isNotBlank() }
                .joinToString(".")

            val reportingParent = parents.last() as RootNode
            this.reportingParent = reportingParent

            descriptor = object : DefaultTestSuiteDescriptor(id, fullName) {
                override fun getParent(): TestDescriptorInternal? = reportingParent.descriptor
            }

            shouldReportComplete = true

            check(startedTs != 0L)
            reportStarted(startedTs)

            return descriptor!!
        }

        private var startedTs: Long = 0

        override fun markStarted(ts: Long) {
            check(descriptor == null)
            startedTs = ts
        }

        override fun markCompleted(ts: Long) {
            if (shouldReportComplete) {
                check(descriptor != null)
                reportCompleted(ts)
            }
        }
    }

    inner class TestNode(
        parent: GroupNode,
        className: String,
        classDisplayName: String,
        methodName: String,
        displayName: String,
        localId: String,
        ignored: Boolean = false
    ) : Node(parent, localId) {
        val output by lazy { StringBuilder() }

        override val descriptor: TestDescriptorInternal =
            object : DefaultTestDescriptor(id, className, methodName, classDisplayName, displayName) {
                override fun getParent(): TestDescriptorInternal? = (this@TestNode.parent as GroupNode).requireReportingNode()
            }

        override fun markStarted(ts: Long) {
            reportStarted(ts)
        }

        override fun markCompleted(ts: Long) {
            reportCompleted(ts)
        }

        init {
            if (!ignored) containsNotIgnored = true
        }
    }

    private var leaf: Node? = null

    private val ServiceMessage.ts: Long
        get() = creationTimestamp?.timestamp?.time ?: System.currentTimeMillis()

    private fun push(node: Node) = node.also { leaf = node }
    private fun pop() = leaf!!.also { leaf = it.parent }

    fun closeAll() {
        val ts = System.currentTimeMillis()

        while (leaf != null) {
            close(ts, leaf!!.localId)
        }
    }

    private fun requireLeaf() = leaf ?: error("test out of group")
    private fun requireLeafGroup(): GroupNode = requireLeaf().let {
        it as? GroupNode ?: error("previous test `$it` not finished")
    }

    private fun requireLeafTest() = leaf as? TestNode
        ?: error("no running test")
}