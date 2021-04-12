/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.distributed.stress

import kotlinx.atomicfu.AtomicArray
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import org.jetbrains.kotlinx.lincheck.annotations.StateRepresentation
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.distributed.queue.FastQueue
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.getMethod
import org.jetbrains.kotlinx.lincheck.runner.TestNodeExecution
import java.lang.Integer.max
import java.lang.reflect.Method
import kotlin.random.Random


class DistributedRunnerContext<Message, Log>(
    val testCfg: DistributedCTestConfiguration<Message, Log>,
    val scenario: ExecutionScenario,
    val runnerHash: Int,
    val stateRepresentation: Method?
) {
    val addressResolver = NodeAddressResolver(
        testCfg.testClass as Class<out Node<Message>>,
        scenario.threads, testCfg.nodeTypes.mapValues { it.value.maxNumberOfInstances to it.value.canFail }
    )

    lateinit var messageHandler : ChannelHandler<MessageSentEvent<Message>>

    lateinit var failureNotifications : Array<Channel<Int>>

    lateinit var failureInfo : NodeFailureInfo

    lateinit var events: FastQueue<Pair<Int, Event>>

    val messageId = atomic(0)

    lateinit var testNodeExecutions: Array<TestNodeExecution>

    lateinit var testInstances: Array<Node<Message>>

    val vectorClock = Array(addressResolver.totalNumberOfNodes) {
        IntArray(addressResolver.totalNumberOfNodes)
    }

    fun incClock(i: Int) = vectorClock[i][i]++

    fun maxClock(iNode: Int, clock: IntArray): IntArray {
        for (i in vectorClock[iNode].indices) {
            vectorClock[iNode][i] = max(vectorClock[iNode][i], clock[i])
        }
        return vectorClock[iNode].copyOf()
    }

    @Volatile
    var invocation: Int = 0

    lateinit var taskCounter : DispatcherTaskCounter

    lateinit var dispatchers: Array<NodeDispatcher>

    lateinit var logs : Array<List<Log>>

    val probabilities = Array(addressResolver.totalNumberOfNodes) {
        Probability(testCfg, addressResolver.totalNumberOfNodes)
    }

    fun initialNumberOfTasks() = if (testCfg.messageOrder == MessageOrder.SYNCHRONOUS) {
        3 * addressResolver.totalNumberOfNodes
    } else {
        2 * addressResolver.totalNumberOfNodes + addressResolver.totalNumberOfNodes * addressResolver.totalNumberOfNodes
    }

    fun initTasksForNode() = if (testCfg.messageOrder == MessageOrder.SYNCHRONOUS) {
        3
    } else {
        addressResolver.totalNumberOfNodes + 2
    }

    fun getStateRepresentation(iNode: Int) = testInstances[iNode].stateRepresentation()

    fun reset() {
        taskCounter = DispatcherTaskCounter(initialNumberOfTasks(), addressResolver.totalNumberOfNodes)
        dispatchers = Array(addressResolver.totalNumberOfNodes) {
            NodeDispatcher(it, taskCounter, runnerHash)
        }
        logs = Array(addressResolver.totalNumberOfNodes) {
            emptyList()
        }
        failureInfo = NodeFailureInfo(
            addressResolver.totalNumberOfNodes,
            testCfg.maxNumberOfFailedNodes(addressResolver.totalNumberOfNodes)
        )
        failureNotifications = Array(addressResolver.totalNumberOfNodes) {
            Channel(UNLIMITED)
        }
        events = FastQueue()
        vectorClock.forEach { it.fill(0) }
        messageHandler = ChannelHandler(testCfg.messageOrder, addressResolver.totalNumberOfNodes)
        if (Probability.failedNodesExpectation == 0) {
            Probability.failedNodesExpectation = testCfg.maxNumberOfFailedNodes(addressResolver.totalNumberOfNodes)
        }
    }
}
