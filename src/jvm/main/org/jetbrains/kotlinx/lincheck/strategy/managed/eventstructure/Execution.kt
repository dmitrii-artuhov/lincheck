/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.utils.*


/**
 * Execution represents a set of events belonging to a single program's execution.
 *
 * The set of events in the execution is causally closed, meaning that if
 * an event belongs to the execution, all its causal predecessors must also belong to it.
 *
 * We assume that all the events within the same thread are totally ordered by the program order.
 * Thus, the execution is represented as a list of threads,
 * with each thread being a list of thread's events given in program order.
 *
 * Since we also assume that the program order is consistent the enumeration order of events,
 * (that is, `(a, b) \in po` implies `a.id < b.id`),
 * the threads' events lists are sorted with respect to the enumeration order.
 *
 * @param E the type of events stored in the execution, must extend `ThreadEvent`.
 */
interface Execution<out E : ThreadEvent> : Collection<E> {

    /**
     * A map from thread IDs to the list of thread events.
     */
    val threadMap: ThreadMap<SortedList<E>>

    /**
     * Retrieves the list of events for the specified thread ID.
     *
     * @param tid The thread ID for which to retrieve the events.
     * @return The list of events for the specified thread ID,
     *  or null if the requested thread does not belong to the execution.
     */
    operator fun get(tid: ThreadID): SortedList<E>? =
        threadMap[tid]

    /**
     * Checks if the given event is present at the execution.
     *
     * @param element The event to check for presence in the execution.
     * @return true if the execution contains the event, false otherwise.
     */
    override fun contains(element: @UnsafeVariance E): Boolean =
        get(element.threadId, element.threadPosition) == element

    /**
     * Checks if all events in the given collection are present at the execution.
     *
     * @param elements The collection of events to check for presence in the execution.
     * @return true if all events in the collection are present in the execution, false otherwise.
     */
    override fun containsAll(elements: Collection<@UnsafeVariance E>): Boolean =
        elements.all { contains(it) }


    /**
     * Returns an iterator over the events in the execution.
     *
     * @return an iterator that iterates over the events of the execution.
     */
    override fun iterator(): Iterator<E> =
        threadIDs.map { get(it)!! }.asSequence().flatten().iterator()

}

/**
 * Mutable execution represents a modifiable set of events belonging to a single program's execution.
 *
 * Mutable execution supports events' addition.
 * Events can only be added according to the causal order.
 * That is, whenever a new event is added to the execution,
 * all the events on which it depends, including its program order predecessors,
 * should already be added into the execution.
 *
 * @param E the type of events stored in the execution, must extend `ThreadEvent`.
 *
 * @see Execution
 * @see ExecutionFrontier
 */
interface MutableExecution<E: ThreadEvent> : Execution<E> {

    /**
     * Adds the specified event to the mutable execution.
     * All the causal predecessors of the event must already be added to the execution.
     *
     * @param event the event to be added to the execution.
     */
    fun add(event: E)

    // TODO: support single (causally-last) event removal (?)
}

val Execution<*>.threadIDs: Set<ThreadID>
    get() = threadMap.keys

val Execution<*>.maxThreadID: ThreadID
    get() = threadIDs.maxOrNull() ?: -1

fun Execution<*>.getThreadSize(tid: ThreadID): Int =
    get(tid)?.size ?: 0

fun Execution<*>.lastPosition(tid: ThreadID): Int =
    getThreadSize(tid) - 1

fun<E : ThreadEvent> Execution<E>.firstEvent(tid: ThreadID): E? =
    get(tid)?.firstOrNull()

fun<E : ThreadEvent> Execution<E>.lastEvent(tid: ThreadID): E? =
    get(tid)?.lastOrNull()

operator fun<E : ThreadEvent> Execution<E>.get(tid: ThreadID, pos: Int): E? =
    get(tid)?.getOrNull(pos)

fun<E : ThreadEvent> Execution<E>.nextEvent(event: E): E? =
    get(event.threadId)?.let { events ->
        require(events[event.threadPosition] == event)
        events.getOrNull(event.threadPosition + 1)
    }

// TODO: make default constructor
fun<E : ThreadEvent> Execution(nThreads: Int): Execution<E> =
    MutableExecution(nThreads)

fun<E : ThreadEvent> MutableExecution(nThreads: Int): MutableExecution<E> =
    ExecutionImpl(ArrayIntMap(*(0 until nThreads)
        .map { (it to sortedArrayListOf<E>()) }
        .toTypedArray()
    ))

fun<E : ThreadEvent> executionOf(vararg pairs: Pair<ThreadID, List<E>>): Execution<E> =
    mutableExecutionOf(*pairs)

fun<E : ThreadEvent> mutableExecutionOf(vararg pairs: Pair<ThreadID, List<E>>): MutableExecution<E> =
    ExecutionImpl(ArrayIntMap(*pairs
        .map { (tid, events) -> (tid to SortedArrayList(events)) }
        .toTypedArray()
    ))

private class ExecutionImpl<E : ThreadEvent>(
    override val threadMap: ArrayIntMap<SortedMutableList<E>>
) : MutableExecution<E> {

    override var size: Int = threadMap.values.sumOf { it.size }
        private set

    override fun isEmpty(): Boolean =
        (size > 0)

    override fun get(tid: ThreadID): SortedMutableList<E>? =
        threadMap[tid]

    override fun add(event: E) {
        ++size
        threadMap[event.threadId]!!
            .ensure { event.parent == it.lastOrNull() }
            .also { it.add(event) }
    }

    override fun equals(other: Any?): Boolean =
        (other is ExecutionImpl<*>) && (size == other.size) && (threadMap == other.threadMap)

    override fun hashCode(): Int =
       threadMap.hashCode()

    override fun toString(): String = buildString {
        appendLine("<======== Execution Graph @${hashCode()} ========>")
        threadIDs.toList().sorted().forEach { tid ->
            val events = threadMap[tid] ?: return@forEach
            appendLine("[-------- Thread #${tid} --------]")
            for (event in events) {
                appendLine("$event")
                if (event.dependencies.isNotEmpty()) {
                    appendLine("    dependencies: ${event.dependencies.joinToString()}}")
                }
            }
        }
    }

}

fun<E : ThreadEvent> Execution<E>.toFrontier(): ExecutionFrontier<E> =
    toMutableFrontier()

fun<E : ThreadEvent> Execution<E>.toMutableFrontier(): MutableExecutionFrontier<E> =
    threadIDs.map { tid ->
        tid to get(tid)?.lastOrNull()
    }.let {
        mutableExecutionFrontierOf(*it.toTypedArray())
    }

fun<E : ThreadEvent> Execution<E>.calculateFrontier(clock: VectorClock): MutableExecutionFrontier<E> =
    (0 until maxThreadID).mapNotNull { tid ->
        val timestamp = clock[tid]
        if (timestamp >= 0)
            tid to this[tid, timestamp]
        else null
    }.let {
        mutableExecutionFrontierOf(*it.toTypedArray())
    }

fun<E : ThreadEvent> Execution<E>.buildIndexer() = object : Indexer<E> {

    private val events = enumerationOrderSorted()

    private val eventIndices = threadMap.mapValues { (_, threadEvents) ->
        List(threadEvents.size) { pos ->
            events.indexOf(threadEvents[pos]).ensure { it >= 0 }
        }
    }

    override fun get(i: Int): E {
        return events[i]
    }

    override fun index(x: E): Int {
        return eventIndices[x.threadId]!![x.threadPosition]
    }

}

fun Execution<*>.locations(): Set<MemoryLocation> {
    val locations = mutableSetOf<MemoryLocation>()
    for (event in this) {
        val location = (event.label as? MemoryAccessLabel)?.location
            ?: continue
        locations.add(location)
    }
    return locations
}

fun<E : ThreadEvent> Execution<E>.isBlockedDanglingRequest(event: E): Boolean {
    return event.label.isRequest && event.label.isBlocking &&
            (event == this[event.threadId]?.last())
}

fun<E : ThreadEvent> Execution<E>.computeVectorClock(event: E, relation: Relation<E>): VectorClock {
    check(this is ExecutionImpl<E>)
    val clock = MutableVectorClock(threadMap.capacity)
    for (i in 0 until threadMap.capacity) {
        val threadEvents = get(i) ?: continue
        clock[i] = (threadEvents.binarySearch { !relation(it, event) } - 1)
            .coerceAtLeast(-1)
    }
    return clock
}

fun VectorClock.observes(event: ThreadEvent): Boolean =
    observes(event.threadId, event.threadPosition)

fun VectorClock.observes(execution: Execution<*>): Boolean =
    execution.threadMap.values.all { events ->
        events.lastOrNull()?.let { observes(it) } ?: true
    }

fun<E : ThreadEvent> Covering<E>.coverable(event: E, clock: VectorClock): Boolean =
    this(event).all { clock.observes(it) }

fun<E : ThreadEvent> Covering<E>.allCoverable(events: List<E>, clock: VectorClock): Boolean =
    events.all { event -> this(event).all { clock.observes(it) || it in events } }

fun<E : ThreadEvent> Covering<E>.firstCoverable(events: List<E>, clock: VectorClock): Boolean =
    coverable(events.first(), clock)

fun <E : Event> Collection<E>.enumerationOrderSorted(): List<E> =
    this.sorted()

fun<E : ThreadEvent> Execution<E>.enumerationOrderSorted(): List<E> =
    this.sorted()

// TODO: make an interface instead of type-alias?
interface EventAggregator {
    fun aggregate(events: List<AtomicThreadEvent>): List<List<AtomicThreadEvent>>

    fun label(events: List<AtomicThreadEvent>): EventLabel?
    fun dependencies(events: List<AtomicThreadEvent>, remapping: EventRemapping): List<HyperThreadEvent>

    fun isCoverable(
        events: List<AtomicThreadEvent>,
        covering: Covering<ThreadEvent>,
        clock: VectorClock
    ): Boolean
}

fun SynchronizationAlgebra.aggregator() = object : EventAggregator {

    override fun aggregate(events: List<AtomicThreadEvent>): List<List<AtomicThreadEvent>> =
        events.squash { x, y -> synchronizable(x.label, y.label) }

    override fun label(events: List<AtomicThreadEvent>): EventLabel =
        synchronize(events).ensureNotNull()

    override fun dependencies(events: List<AtomicThreadEvent>, remapping: EventRemapping): List<HyperThreadEvent> {
        return events
            // TODO: should use covering here instead of dependencies?
            .flatMap { event -> event.dependencies.mapNotNull { remapping[it] } }
            .distinct()
    }

    override fun isCoverable(
        events: List<AtomicThreadEvent>,
        covering: Covering<ThreadEvent>,
        clock: VectorClock
    ): Boolean {
        return covering.allCoverable(events, clock)
    }

}

fun ActorAggregator(execution: Execution<AtomicThreadEvent>) = object : EventAggregator {

    override fun aggregate(events: List<AtomicThreadEvent>): List<List<AtomicThreadEvent>> {
        var pos = 0
        val result = mutableListOf<List<AtomicThreadEvent>>()
        while (pos < events.size) {
            if ((events[pos].label as? ActorLabel)?.actorKind != ActorLabelKind.Start) {
                result.add(listOf(events[pos++]))
                continue
            }
            val start = events[pos]
            val end = events.subList(fromIndex = start.threadPosition, toIndex = events.size).find {
                (it.label as? ActorLabel)?.actorKind == ActorLabelKind.End
            } ?: break
            check((start.label as ActorLabel).actor == (end.label as ActorLabel).actor)
            result.add(events.subList(fromIndex = start.threadPosition, toIndex = end.threadPosition + 1))
            pos = end.threadPosition + 1
        }
        return result
    }

    override fun label(events: List<AtomicThreadEvent>): EventLabel? {
        val start = events.first().takeIf {
            (it.label as? ActorLabel)?.actorKind == ActorLabelKind.Start
        } ?: return null
        val end = events.last().ensure {
            (it.label as? ActorLabel)?.actorKind == ActorLabelKind.End
        }
        check((start.label as ActorLabel).actor == (end.label as ActorLabel).actor)
        return ActorLabel((start.label as ActorLabel).threadId, ActorLabelKind.Span, (start.label as ActorLabel).actor)
    }

    override fun dependencies(events: List<AtomicThreadEvent>, remapping: EventRemapping): List<HyperThreadEvent> {
        return events
            .flatMap { event ->
                val causalEvents = execution.threadMap.entries.mapNotNull { (tid, thread) ->
                    if (tid != event.threadId)
                        thread.getOrNull(event.causalityClock[tid])
                    else null
                }
                causalEvents.mapNotNull { remapping[it] }
            }
            // TODO: should use covering here instead of dependencies?
            .filter {
                // take the last event before ActorEnd event
                val last = it.events[it.events.size - 2]
                events.first().causalityClock.observes(last)
            }
            .distinct()
    }

    override fun isCoverable(
        events: List<AtomicThreadEvent>,
        covering: Covering<ThreadEvent>,
        clock: VectorClock
    ): Boolean {
        return covering.firstCoverable(events, clock)
    }

}

// TODO: unify with Remapping class
typealias EventRemapping = Map<AtomicThreadEvent, HyperThreadEvent>

fun Execution<AtomicThreadEvent>.aggregate(
    aggregator: EventAggregator
): Pair<Execution<HyperThreadEvent>, EventRemapping> {
    val clock = MutableVectorClock(1 + maxThreadID)
    val result = MutableExecution<HyperThreadEvent>(1 + maxThreadID)
    val remapping = mutableMapOf<AtomicThreadEvent, HyperThreadEvent>()
    val aggregated = threadMap.mapValues { (_, events) -> aggregator.aggregate(events) }
    val aggregatedClock = MutableVectorClock(1 + maxThreadID).apply {
        for (i in 0 .. maxThreadID)
            this[i] = 0
    }
    while (!clock.observes(this)) {
        var position = -1
        var found = false
        var events: List<AtomicThreadEvent>? = null
        for ((tid, list) in aggregated.entries) {
            position = aggregatedClock[tid]
            events = list.getOrNull(position) ?: continue
            if (aggregator.isCoverable(events, causalityCovering, clock)) {
                found = true
                break
            }
        }
        if (!found) {
            // error("Cannot aggregate events due to cyclic dependencies")
            break
        }
        check(position >= 0)
        check(events != null)
        check(events.isNotEmpty())
        val tid = events.first().threadId
        val label = aggregator.label(events)
        val parent = result[tid]?.lastOrNull()
        if (label != null) {
            val dependencies = aggregator.dependencies(events, remapping)
            val event = HyperThreadEvent(
                label = label,
                parent = parent,
                dependencies = dependencies,
                events = events,
            )
            result.add(event)
            events.forEach {
                remapping.put(it, event).ensureNull()
            }
        } else if (parent != null) {
            // effectively squash skipped events into previous hyper event,
            // such representation is convenient for causality clock maintenance
            // TODO: make sure dependencies of skipped events are propagated correctly
            events.forEach {
                remapping.put(it, parent).ensureNull()
            }
        }
        clock.increment(tid, events.size)
        aggregatedClock.increment(tid)
    }
    return result to remapping
}

fun Covering<AtomicThreadEvent>.aggregate(remapping: EventRemapping) =
    Covering<HyperThreadEvent> { event ->
        event.events
            .flatMap { atomicEvent ->
                this(atomicEvent).mapNotNull { remapping[it] }
            }
            .distinct()
    }

// TODO: include parent event in covering (?) and remove `External`
fun<E : ThreadEvent> Execution<E>.buildExternalCovering(relation: Relation<E>) = object : Covering<E> {

    // TODO: document this precondition!
    init {
        // require(respectsProgramOrder)
    }

    private val execution = this@buildExternalCovering
    private val indexer = execution.buildIndexer()

    private val nThreads = 1 + maxThreadID

    val covering: List<List<E>> = execution.indices.map { index ->
        val event = indexer[index]
        val clock = execution.computeVectorClock(event, relation)
        (0 until nThreads).mapNotNull { tid ->
            if (tid != event.threadId && clock[tid] != -1)
                execution[tid, clock[tid]]
            else null
        }
    }

    override fun invoke(x: E): List<E> =
        covering[indexer.index(x)]

}