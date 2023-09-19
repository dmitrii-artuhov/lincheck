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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.utils.*
import java.util.*
import kotlin.reflect.KClass

class EventStructure(
    nParallelThreads: Int,
    val memoryInitializer: MemoryInitializer,
    val internalThreadSwitchCallback: InternalThreadSwitchCallback,
) {
    val mainThreadId = nParallelThreads
    val initThreadId = nParallelThreads + 1

    private val maxThreadId = initThreadId
    private val nThreads = maxThreadId + 1

    val syncAlgebra: SynchronizationAlgebra = AtomicSynchronizationAlgebra

    val root: ThreadEvent

    // TODO: this pattern is covered by explicit backing fields KEEP
    //   https://github.com/Kotlin/KEEP/issues/278
    private val _events = sortedMutableListOf<BacktrackableEvent>()

    /**
     * List of events of the event structure.
     */
    val events: SortedList<Event> = _events

    lateinit var currentExplorationRoot: Event
        private set

    // TODO: this pattern is covered by explicit backing fields KEEP
    //   https://github.com/Kotlin/KEEP/issues/278
    private var _currentExecution = MutableExecution(this.nThreads)

    val currentExecution: Execution
        get() = _currentExecution

    private var playedFrontier = MutableExecutionFrontier(this.nThreads)

    private var replayer = Replayer()

    private var pinnedEvents = ExecutionFrontier(this.nThreads)

    private var currentRemapping: Remapping = Remapping()

    private val delayedConsistencyCheckBuffer = mutableListOf<ThreadEvent>()

    var detectedInconsistency: Inconsistency? = null
        private set

    // TODO: move to EventIndexer once it will be implemented
    private val allocationEvents = IdentityHashMap<Any, Event>()

    val allocatedObjects: Set<Any>
        get() = allocationEvents.keys

    private val sequentialConsistencyChecker =
        IncrementalSequentialConsistencyChecker(
            checkReleaseAcquireConsistency = true,
            approximateSequentialConsistency = false
        )

    private val atomicityChecker = AtomicityChecker()

    private val consistencyChecker = aggregateConsistencyCheckers(
        listOf(
            atomicityChecker,
            sequentialConsistencyChecker
        ),
        listOf(),
    )
    
    /*
     * Map from blocked dangling events to their responses.
     * If event is blocked but the corresponding response has not yet arrived then it is mapped to null.
     */
    private val danglingEvents = mutableMapOf<ThreadEvent, ThreadEvent?>()

    init {
        root = addRootEvent()
    }

    fun getThreadRoot(iThread: Int): ThreadEvent? =
        currentExecution.firstEvent(iThread)?.also { event ->
            check(event.label is ThreadStartLabel && event.label.isRequest)
        }

    fun isStartedThread(iThread: Int): Boolean =
        getThreadRoot(iThread) != null

    fun isFinishedThread(iThread: Int): Boolean =
        currentExecution.lastEvent(iThread)?.let { event ->
            event.label is ThreadFinishLabel
        } ?: false

    fun startNextExploration(): Boolean {
        loop@while (true) {
            val event = rollbackToEvent { !it.visited }
                ?: return false
            event.visit()
            resetExploration(event)
            return true
        }
    }

    fun initializeExploration() {
        playedFrontier = MutableExecutionFrontier(nThreads)
        playedFrontier[initThreadId] = currentExecution[initThreadId]!!.last()
        replayer.currentEvent.ensure {
            it != null && it.label is InitializationLabel
        }
        replayer.setNextEvent()
    }

    fun abortExploration() {
        // _currentExecution = playedFrontier.toExecution()
        // println("played frontier: ${playedFrontier.mapping}")
        // TODO: bugfix --- cut threads absent in playedFrontier.mapping.values to 0 !!!
        for (threadId in currentExecution.threadIDs) {
            val lastEvent = playedFrontier[threadId]
            if (lastEvent == null) {
                _currentExecution.cut(threadId, 0)
                continue
            }
            when {
                // we handle blocking request in a special way --- we include their response part
                // in order to detect potential blocking response uniqueness violations
                // (e.g. two lock events unblocked by the same unlock event)
                // TODO: too complicated, try to simplify
                lastEvent.label.isRequest && lastEvent.label.isBlocking -> {
                    val responseEvent = _currentExecution[lastEvent.threadId, lastEvent.threadPosition + 1]
                        ?: continue
                    if (responseEvent.dependencies.any { (it as ThreadEvent) !in playedFrontier }) {
                        _currentExecution.cut(responseEvent)
                        continue
                    }
                    check(responseEvent.label.isResponse)
                    responseEvent.label.remap(currentRemapping)
                    _currentExecution.cutNext(responseEvent)
                }
                // otherwise just cut last replayed event
                else -> {
                    _currentExecution.cutNext(lastEvent)
                }
            }
        }
    }

    private fun rollbackToEvent(predicate: (BacktrackableEvent) -> Boolean): BacktrackableEvent? {
        val eventIdx = _events.indexOfLast(predicate)
        _events.subList(eventIdx + 1, _events.size).clear()
        return _events.lastOrNull()
    }

    private fun resetExploration(event: BacktrackableEvent) {
        check(event.label is InitializationLabel || event.label.isResponse)
        // reset consistency check state
        detectedInconsistency = null
        // set current exploration root
        currentExplorationRoot = event
        // reset current execution
        _currentExecution = event.frontier.toMutableExecution().apply {
            currentRemapping = resynchronize(syncAlgebra)
        }
        // reset the internal state of incremental checkers
        consistencyChecker.reset(currentExecution)
        // add new event to current execution
        _currentExecution.add(event)
        // remap new event's label
        currentRemapping.resynchronize(event, syncAlgebra)
        // set pinned events
        pinnedEvents = event.pinnedEvents.copy().ensure {
            currentExecution.containsAll(it.events)
        }
        // check new event with the incremental consistency checkers
        checkConsistencyIncrementally(event)
        // check the full consistency of the whole execution
        checkConsistency()
        // set the replayer state
        replayer = Replayer(sequentialConsistencyChecker.executionOrder)
        // reset state of other auxiliary structures
        currentRemapping.reset()
        danglingEvents.clear()
        allocationEvents.clear()
        delayedConsistencyCheckBuffer.clear()
    }

    fun checkConsistency(): Inconsistency? {
        // TODO: set suddenInvocationResult instead of `detectedInconsistency`
        if (detectedInconsistency == null) {
            detectedInconsistency = consistencyChecker.check()
        }
        return detectedInconsistency
    }

    private fun checkConsistencyIncrementally(event: Event): Inconsistency? {
        // TODO: set suddenInvocationResult instead of `detectedInconsistency`
        if (detectedInconsistency == null) {
            detectedInconsistency = consistencyChecker.check(event)
        }
        return detectedInconsistency
    }

    private class Replayer(private val executionOrder: List<ThreadEvent>) {
        private var index: Int = 0
        private val size: Int = executionOrder.size

        constructor(): this(listOf())

        fun inProgress(): Boolean =
            (index < size)

        val currentEvent: BacktrackableEvent?
            get() = if (inProgress()) (executionOrder[index] as? BacktrackableEvent) else null

        fun setNextEvent() {
            index++
        }
    }

    fun inReplayPhase(): Boolean =
        replayer.inProgress()

    fun inReplayPhase(iThread: Int): Boolean {
        val frontEvent = playedFrontier[iThread]
            ?.ensure { it in _currentExecution }
        return (frontEvent != currentExecution.lastEvent(iThread))
    }

    // should only be called in replay phase!
    fun canReplayNextEvent(iThread: Int): Boolean {
        return iThread == replayer.currentEvent?.threadId
    }

    private fun tryReplayEvent(iThread: Int): BacktrackableEvent? {
        if (inReplayPhase() && !canReplayNextEvent(iThread)) {
            // TODO: can we get rid of this?
            //   we can try to enforce more ordering invariants by grouping "atomic" events
            //   and also grouping events for which there is no reason to make switch in-between
            //   (e.g. `Alloc` followed by a `Write`).
            internalThreadSwitchCallback(iThread)
            check(!inReplayPhase() || canReplayNextEvent(iThread))
        }
        return replayer.currentEvent
            ?.also { replayer.setNextEvent() }
    }

    private fun emptyClock(): MutableVectorClock =
        MutableVectorClock(nThreads)

    private fun VectorClock.toMutableFrontier(): MutableExecutionFrontier =
        (0 until nThreads).map { tid ->
            tid to currentExecution[tid, this[tid]]
        }.let {
            mutableExecutionFrontierOf(*it.toTypedArray())
        }

    private fun createEvent(
        iThread: Int,
        label: EventLabel,
        parent: ThreadEvent?,
        dependencies: List<ThreadEvent>,
        conflicts: List<ThreadEvent>
    ): BacktrackableEvent? {
        var causalityViolation = false
        // Check that parent does not depend on conflicting events.
        if (parent != null) {
            causalityViolation = causalityViolation || conflicts.any { conflict ->
                causalityOrder.lessOrEqual(conflict, parent)
            }
        }
        // Also check that dependencies do not causally depend on conflicting events.
        causalityViolation = causalityViolation || conflicts.any { conflict -> dependencies.any { dependency ->
                causalityOrder.lessOrEqual(conflict, dependency)
        }}
        if (causalityViolation)
            return null
        val threadPosition = parent?.let { it.threadPosition + 1 } ?: 0
        val causalityClock = dependencies.fold(parent?.causalityClock?.copy() ?: emptyClock()) { clock, event ->
            clock + event.causalityClock
        }
        val allocation = label.obj?.let {
            allocationEvents[it.unwrap()]
        }
        val source = (label as? WriteAccessLabel)?.writeValue?.let {
            allocationEvents[it.unwrap()]
        }
        val frontier = currentExecution.toMutableFrontier().apply {
            cut(conflicts)
            cutDanglingRequestEvents()
            set(iThread, parent)
        }
        val pinnedEvents = pinnedEvents.copy().apply {
            cut(conflicts)
            merge(causalityClock.toMutableFrontier())
            cutDanglingRequestEvents()
        }
        return BacktrackableEvent(
            label = label,
            threadId = iThread,
            parent = parent,
            causalityClock = causalityClock.apply {
                set(iThread, threadPosition)
            },
            senders = dependencies,
            allocation = (allocation as? ThreadEvent),
            source = (source as? ThreadEvent),
            frontier = frontier,
            pinnedEvents = pinnedEvents,
        )
    }

    private fun addEvent(
        iThread: Int,
        label: EventLabel,
        parent: ThreadEvent?,
        dependencies: List<ThreadEvent>
    ): BacktrackableEvent? {
        // TODO: do we really need this invariant?
        // require(parent !in dependencies)
        val allDependencies = dependencies // + extraDependencies(iThread, label, parent, dependencies)
        val conflicts = conflictingEvents(iThread, parent, label, allDependencies)
        return createEvent(iThread, label, parent, allDependencies, conflicts)?.also { event ->
            _events.add(event)
        }
    }

    // private fun extraDependencies(
    //     iThread: Int,
    //     label: EventLabel,
    //     parent: ThreadEvent?,
    //     dependencies: List<ThreadEvent>
    // ): List<ThreadEvent> {
    //     return when (label) {
    //         is MemoryAccessLabel, is MutexLabel -> {
    //             // TODO: unify cases
    //             val obj = when (label) {
    //                 is MemoryAccessLabel -> label.location.obj
    //                 is MutexLabel -> label.mutex.unwrap()
    //                 else -> unreachable()
    //             }
    //             listOfNotNull(
    //                 allocationEvents[obj]
    //             )
    //         }
    //         else -> listOf()
    //     }
    // }

    private fun conflictingEvents(
        iThread: Int,
        parent: ThreadEvent?,
        label: EventLabel,
        dependencies: List<ThreadEvent>
    ): List<ThreadEvent> {
        val position = parent?.let { it.threadPosition + 1 } ?: 0
        val conflicts = mutableListOf<ThreadEvent>()
        // if the current execution already has an event in given position --- then it is conflict
        currentExecution[iThread, position]?.also { conflicts.add(it) }
        // handle label specific cases
        // TODO: unify this logic for various kinds of labels?
        when {
            // lock-response synchronizing with our unlock is conflict
            label is LockLabel && label.isResponse && !label.isReentry -> run {
                val unlock = dependencies.first { it.label.asUnlockLabel(label.mutex) != null }
                currentExecution.forEach { event ->
                    check(event is AbstractAtomicThreadEvent)
                    if (event.label.isResponse
                            && (event.label as? LockLabel)?.mutex == label.mutex
                            && event.locksFrom == unlock) {
                        conflicts.add(event)
                    }
                }
            }
            // wait-response synchronizing with our notify is conflict
            label is WaitLabel && label.isResponse -> run {
                val notify = dependencies.first { it.label is NotifyLabel }
                if ((notify.label as NotifyLabel).isBroadcast)
                    return@run
                currentExecution.forEach { event ->
                    check(event is AbstractAtomicThreadEvent)
                    if (event.label.isResponse
                            && (event.label as? WaitLabel)?.mutex == label.mutex
                            && event.notifiedBy == notify) {
                        conflicts.add(event)
                    }
                }
            }
            // TODO: add similar rule for read-exclusive-response?
        }
        return conflicts
    }

    private fun addEventToCurrentExecution(event: BacktrackableEvent, visit: Boolean = true) {
        // Mark event as visited if necessary.
        if (visit) {
            event.visit()
        }
        // Check if the added event is replayed event.
        val isReplayedEvent = inReplayPhase(event.threadId)
        // Update current execution and replayed frontier.
        if (!isReplayedEvent) {
            _currentExecution.add(event)
        }
        playedFrontier.update(event)
        // Check if we still in replay phase.
        val inReplayPhase = inReplayPhase()
        // Mark last replayed blocking event as dangling.
        if (event.label.isRequest && event.label.isBlocking && isReplayedEvent && !inReplayPhase(event.threadId)) {
            markBlockedDanglingRequest(event)
        }
        // Unmark dangling request if its response was added.
        if (event.label.isResponse && event.label.isBlocking && event.parent in danglingEvents) {
            unmarkBlockedDanglingRequest(event.parent!!)
        }
        // Update allocation events index.
        if (event.label is ObjectAllocationLabel) {
            allocationEvents.put(event.label.obj.unwrap(), event).ensureNull()
        }
        // If we are still in replay phase, but the added event is not a replayed event,
        // then save it to delayed events buffer to postpone its further processing.
        if (inReplayPhase) {
            if (!isReplayedEvent) {
                delayedConsistencyCheckBuffer.add(event)
            }
            return
        }
        // If we are not in replay phase anymore, but the current event is replayed event,
        // it means that we just finished replay phase (i.e. the given event is the last replayed event).
        // In this case, we need to proceed all postponed non-replayed events.
        if (isReplayedEvent) {
            for (delayedEvent in delayedConsistencyCheckBuffer) {
                if (delayedEvent.label.isSend) {
                    addSynchronizedEvents(delayedEvent)
                }
                checkConsistencyIncrementally(delayedEvent)
            }
            delayedConsistencyCheckBuffer.clear()
            return
        }
        // If we are not in the replay phase and the newly added event is not replayed, then we proceed it as usual.
        if (event.label.isSend) {
            addSynchronizedEvents(event)
        }
        checkConsistencyIncrementally(event)
    }

    private val EventLabel.syncType
        get() = syncAlgebra.syncType(this)

    private fun EventLabel.synchronizable(other: EventLabel) =
        syncAlgebra.synchronizable(this, other)

    private fun EventLabel.synchronize(other: EventLabel) =
        syncAlgebra.synchronize(this, other)

    private fun synchronizationCandidates(event: ThreadEvent): List<ThreadEvent> {
        // consider all candidates in current execution and apply some general filters
        val candidates = currentExecution.asSequence()
            // for send event we filter out ...
            .runIf(event.label.isSend) { filter {
                // (1) all of its causal predecessors, because an attempt to synchronize with
                //     these predecessors will result in causality cycle
                !causalityOrder.lessThan(it, event) &&
                // (2) pinned events, because their response part is pinned (i.e. fixed),
                //     unless pinned event is blocking dangling event
                (!pinnedEvents.contains(it) || danglingEvents.contains(it))
            }}
        val label = event.label
        return when {
            // for read-request events we search for the last write to the same memory location
            // in the same thread, and then filter out all causal predecessors of this last write,
            // because these events are "obsolete" --- reading from them will result in coherence cycle
            // and will violate consistency
            label is MemoryAccessLabel && label.isRequest -> {
                // val threadLastWrite = currentExecution[event.threadId]?.lastOrNull {
                //     it.label is WriteAccessLabel && it.label.location == event.label.location
                // } ?: root
                val threadReads = currentExecution[event.threadId]!!.filter {
                    it.label.isResponse && (it.label as? ReadAccessLabel)?.location == label.location
                }
                val lastSeenWrite = threadReads.lastOrNull()?.let { (it as AbstractAtomicThreadEvent).readsFrom }
                val staleWrites = threadReads
                    .map { (it as AbstractAtomicThreadEvent).readsFrom }
                    .filter { it != lastSeenWrite }
                    .distinct()
                val racyWrites = calculateRacyWrites(label.location, event.causalityClock.toMutableFrontier())
                candidates.filter {
                    // !causalityOrder.lessThan(it, threadLastWrite) &&
                    !racyWrites.any { write -> causalityOrder.lessThan(it, write) } &&
                    !staleWrites.any { write -> causalityOrder.lessOrEqual(it, write) }
                }
            }
            // re-entry lock-request synchronizes only with object allocation label
            label is LockLabel && event.label.isRequest && label.isReentry -> {
                candidates.filter { it.label.asObjectAllocationLabel(label.mutex) != null }
            }
            // re-entry unlock synchronizes with nothing
            label is UnlockLabel && label.isReentry -> {
                return listOf(root)
            }

            else -> candidates
        }.toList()
    }

    /**
     * Adds to the event structure a list of events obtained as a result of synchronizing given [event]
     * with the events contained in the current exploration. For example, if
     * `e1 @ A` is the given event labeled by `A` and `e2 @ B` is some event in the event structure labeled by `B`,
     * then the resulting list will contain event labeled by `C = A \+ B` if `C` is defined (i.e. not null),
     * and the list of dependencies of this new event will be equal to `listOf(e1, e2)`.
     *
     * @return list of added events
     */
    private fun addSynchronizedEvents(event: ThreadEvent): List<ThreadEvent> {
        // TODO: we should maintain an index of read/write accesses to specific memory location
        val syncEvents = when (event.label.syncType) {
            SynchronizationType.Binary -> addBinarySynchronizedEvents(event, synchronizationCandidates(event))
            SynchronizationType.Barrier -> addBarrierSynchronizedEvents(event, synchronizationCandidates(event))
            else -> return listOf()
        }
        // if there are responses to blocked dangling requests, then set the response of one of these requests
        for (syncEvent in syncEvents) {
            val requestEvent = syncEvent.parent
                ?.takeIf { it.label.isRequest && it.label.isBlocking }
                ?: continue
            if (requestEvent in danglingEvents && getUnblockingResponse(requestEvent) == null) {
                setUnblockingResponse(syncEvent)
                break
            }
        }
        return syncEvents
    }

    private fun addBinarySynchronizedEvents(event: ThreadEvent, candidateEvents: Collection<ThreadEvent>): List<ThreadEvent> {
        require(event.label.syncType == SynchronizationType.Binary)
        // TODO: sort resulting events according to some strategy?
        return candidateEvents
            .mapNotNull { other ->
                val syncLab = event.label.synchronize(other.label)
                    ?: return@mapNotNull null
                val (parent, dependency) = when {
                    event.label.isRequest -> event to other
                    other.label.isRequest -> other to event
                    else -> unreachable()
                }
                check(parent.label.isRequest && dependency.label.isSend && syncLab.isResponse)
                Triple(syncLab, parent, dependency)
            }.sortedBy { (_, _, dependency) ->
                dependency
            }.mapNotNull { (syncLab, parent, dependency) ->
                addEvent(parent.threadId, syncLab, parent, dependencies = listOf(dependency))
            }
    }

    private fun addBarrierSynchronizedEvents(event: ThreadEvent, candidateEvents: Collection<ThreadEvent>): List<ThreadEvent> {
        require(event.label.syncType == SynchronizationType.Barrier)
        val (syncLab, dependencies) =
            candidateEvents.fold(event.label to listOf(event)) { (lab, deps), candidateEvent ->
                candidateEvent.label.synchronize(lab)?.let {
                    (it to deps + candidateEvent)
                } ?: (lab to deps)
            }
        if (syncLab.isBlocking && !syncLab.unblocked)
            return listOf()
        // We assume that at most, one of the events participating into synchronization
        // is a request event, and the result of synchronization is a response event.
        check(syncLab.isResponse)
        val parent = dependencies.first { it.label.isRequest }
        val responseEvent = addEvent(parent.threadId, syncLab, parent, dependencies.filter { it != parent })
        return listOfNotNull(responseEvent)
    }

    private fun addRootEvent(): ThreadEvent {
        // we do not mark root event as visited purposefully;
        // this is just a trick to make the first call to `startNextExploration`
        // to pick the root event as the next event to explore from.
        val label = InitializationLabel(mainThreadId, memoryInitializer) { obj ->
            obj !in allocationEvents
        }
        return addEvent(initThreadId, label, parent = null, dependencies = emptyList())!!.also {
            addEventToCurrentExecution(it, visit = false)
        }
    }

    private fun addSendEvent(iThread: Int, label: EventLabel): ThreadEvent {
        require(label.isSend)
        tryReplayEvent(iThread)?.let { event ->
            // TODO: also check custom event/label specific rules when replaying,
            //   e.g. upon replaying write-exclusive check its location equal to
            //   the location of previous read-exclusive part
            if (label is ObjectAllocationLabel) {
                currentRemapping[event.label.obj?.unwrap()] = label.obj.unwrap()
            }
            currentRemapping.replay(event, syncAlgebra)
            addEventToCurrentExecution(event)
            return event
        }
        val parent = playedFrontier[iThread]
        val dependencies = listOf<ThreadEvent>()
        return addEvent(iThread, label, parent, dependencies)!!.also { event ->
            addEventToCurrentExecution(event)
        }
    }

    private fun addRequestEvent(iThread: Int, label: EventLabel): ThreadEvent {
        require(label.isRequest)
        tryReplayEvent(iThread)?.let { event ->
            currentRemapping.replay(event, syncAlgebra)
            addEventToCurrentExecution(event)
            return event
        }
        val parent = playedFrontier[iThread]
        return addEvent(iThread, label, parent, dependencies = emptyList())!!.also { event ->
            addEventToCurrentExecution(event)
        }
    }

    private fun addResponseEvents(requestEvent: ThreadEvent): Pair<ThreadEvent?, List<ThreadEvent>> {
        require(requestEvent.label.isRequest)
        tryReplayEvent(requestEvent.threadId)?.let { event ->
            check(event.label.isResponse)
            check(event.parent == requestEvent)
            // TODO: refactor & move to other replay-related functions
            val readyToReplay = event.dependencies.all {
                dependency -> dependency in playedFrontier
            }
            if (!readyToReplay) {
                return (null to listOf())
            }
            currentRemapping.replay(event, syncAlgebra)
            addEventToCurrentExecution(event)
            return event to listOf(event)
        }
        if (requestEvent.label.isBlocking && requestEvent in danglingEvents) {
            val event = getUnblockingResponse(requestEvent)
                ?: return (null to listOf())
            check(event.label.isResponse)
            check(event.parent == requestEvent)
            check(event is BacktrackableEvent)
            addEventToCurrentExecution(event)
            return event to listOf(event)
        }
        val responseEvents = addSynchronizedEvents(requestEvent)
        if (responseEvents.isEmpty()) {
            markBlockedDanglingRequest(requestEvent)
            return (null to listOf())
        }
        // TODO: use some other strategy to select the next event in the current exploration?
        // TODO: check consistency of chosen event!
        val chosenEvent = responseEvents.last().also { event ->
            addEventToCurrentExecution(event as BacktrackableEvent)
        }
        return (chosenEvent to responseEvents)
    }

    fun isBlockedRequest(request: ThreadEvent): Boolean {
        require(request.label.isRequest && request.label.isBlocking)
        return (request == playedFrontier[request.threadId])
    }

    fun isBlockedDanglingRequest(request: ThreadEvent): Boolean {
        require(request.label.isRequest && request.label.isBlocking)
        return currentExecution.isBlockedDanglingRequest(request)
    }

    fun isBlockedAwaitingRequest(request: ThreadEvent): Boolean {
        require(isBlockedRequest(request))
        if (inReplayPhase(request.threadId)) {
            return !canReplayNextEvent(request.threadId)
        }
        if (request in danglingEvents) {
            return danglingEvents[request] == null
        }
        return false
    }

    fun getBlockedRequest(iThread: Int): ThreadEvent? =
        playedFrontier[iThread]?.takeIf { it.label.isRequest && it.label.isBlocking }

    fun getBlockedAwaitingRequest(iThread: Int): ThreadEvent? =
        getBlockedRequest(iThread)?.takeIf { isBlockedAwaitingRequest(it) }

    private fun markBlockedDanglingRequest(request: ThreadEvent) {
        require(isBlockedDanglingRequest(request))
        check(request !in danglingEvents)
        check(danglingEvents.keys.all { it.threadId != request.threadId })
        danglingEvents.put(request, null).ensureNull()
    }

    private fun unmarkBlockedDanglingRequest(request: ThreadEvent) {
        require(request.label.isRequest && request.label.isBlocking)
        require(!isBlockedDanglingRequest(request))
        require(request in danglingEvents)
        danglingEvents.remove(request)
    }

    private fun setUnblockingResponse(response: ThreadEvent) {
        require(response.label.isResponse && response.label.isBlocking)
        val request = response.parent
            .ensure { it != null }
            .ensure { isBlockedDanglingRequest(it!!) }
            .ensure { it in danglingEvents }
        danglingEvents.put(request!!, response).ensureNull()
    }

    private fun getUnblockingResponse(request: ThreadEvent): ThreadEvent? {
        require(isBlockedDanglingRequest(request))
        require(request in danglingEvents)
        return danglingEvents[request]
    }

    fun addThreadStartEvent(iThread: Int): ThreadEvent {
        val label = ThreadStartLabel(
            threadId = iThread,
            kind = LabelKind.Request,
        )
        val requestEvent = addRequestEvent(iThread, label)
        val (responseEvent, responseEvents) = addResponseEvents(requestEvent)
        checkNotNull(responseEvent)
        check(responseEvents.size == 1)
        return responseEvent
    }

    fun addThreadFinishEvent(iThread: Int): ThreadEvent {
        val label = ThreadFinishLabel(
            threadId = iThread,
        )
        return addSendEvent(iThread, label)
    }

    fun addThreadForkEvent(iThread: Int, forkThreadIds: Set<Int>): ThreadEvent {
        val label = ThreadForkLabel(
            forkThreadIds = forkThreadIds
        )
        return addSendEvent(iThread, label)
    }

    fun addThreadJoinEvent(iThread: Int, joinThreadIds: Set<Int>): ThreadEvent {
        val label = ThreadJoinLabel(
            kind = LabelKind.Request,
            joinThreadIds = joinThreadIds,
        )
        val requestEvent = addRequestEvent(iThread, label)
        val (responseEvent, responseEvents) = addResponseEvents(requestEvent)
        // TODO: handle case when ThreadJoin is not ready yet
        checkNotNull(responseEvent)
        check(responseEvents.size == 1)
        return responseEvent
    }

    fun addObjectAllocationEvent(iThread: Int, value: OpaqueValue): ThreadEvent {
        val label = ObjectAllocationLabel(value, memoryInitializer)
        return addSendEvent(iThread, label)
    }

    fun addWriteEvent(iThread: Int, location: MemoryLocation, kClass: KClass<*>, value: OpaqueValue?,
                      isExclusive: Boolean = false): ThreadEvent {
        val label = WriteAccessLabel(
            location = location,
            _value = value,
            kClass = kClass,
            isExclusive = isExclusive,
        )
        return addSendEvent(iThread, label)
    }

    fun addReadEvent(iThread: Int, location: MemoryLocation, kClass: KClass<*>,
                     isExclusive: Boolean = false): ThreadEvent {
        // we first create read-request event with unknown (null) value,
        // value will be filled later in read-response event
        val label = ReadAccessLabel(
            kind = LabelKind.Request,
            location = location,
            _value = null,
            kClass = kClass,
            isExclusive = isExclusive,
        )
        val requestEvent = addRequestEvent(iThread, label)
        val (responseEvent, _) = addResponseEvents(requestEvent)
        // TODO: think again --- is it possible that there is no write to read-from?
        //  Probably not, because in Kotlin variables are always initialized by default?
        //  What about initialization-related issues?
        checkNotNull(responseEvent) {
            "hehn't"
        }
        return responseEvent
    }

    fun addLockRequestEvent(iThread: Int, mutex: OpaqueValue, reentranceDepth: Int = 1, reentranceCount: Int = 1, isWaitLock: Boolean = false): ThreadEvent {
        val label = LockLabel(
            kind = LabelKind.Request,
            mutex_ = mutex,
            reentranceDepth = reentranceDepth,
            reentranceCount = reentranceCount,
            isWaitLock = isWaitLock,
        )
        return addRequestEvent(iThread, label)
    }

    fun addLockResponseEvent(lockRequest: ThreadEvent): ThreadEvent? {
        require(lockRequest.label.isRequest && lockRequest.label is LockLabel)
        return addResponseEvents(lockRequest).first
    }

    fun addUnlockEvent(iThread: Int, mutex: OpaqueValue, reentranceDepth: Int = 1, reentranceCount: Int = 1, isWaitUnlock: Boolean = false): ThreadEvent {
        val label = UnlockLabel(
            mutex_ = mutex,
            reentranceDepth = reentranceDepth,
            reentranceCount = reentranceCount,
            isWaitUnlock = isWaitUnlock,
        )
        return addSendEvent(iThread, label)
    }

    fun addWaitRequestEvent(iThread: Int, mutex: OpaqueValue): ThreadEvent {
        val label = WaitLabel(
            kind = LabelKind.Request,
            mutex_ = mutex,
        )
        return addRequestEvent(iThread, label)

    }

    fun addWaitResponseEvent(waitRequest: ThreadEvent): ThreadEvent? {
        require(waitRequest.label.isRequest && waitRequest.label is WaitLabel)
        return addResponseEvents(waitRequest).first
    }

    fun addNotifyEvent(iThread: Int, mutex: OpaqueValue, isBroadcast: Boolean): Event {
        // TODO: we currently ignore isBroadcast flag and handle `notify` similarly as `notifyAll`.
        //   It is correct wrt. Java's semantics, since `wait` can wake-up spuriously according to the spec.
        //   Thus multiple wake-ups due to single notify can be interpreted as spurious.
        //   However, if one day we will want to support wait semantics without spurious wake-ups
        //   we will need to revisit this.
        val label = NotifyLabel(mutex, isBroadcast)
        return addSendEvent(iThread, label)
    }

    fun addParkRequestEvent(iThread: Int): ThreadEvent {
        val label = ParkLabel(LabelKind.Request, iThread)
        return addRequestEvent(iThread, label)
    }

    fun addParkResponseEvent(parkRequest: ThreadEvent): ThreadEvent? {
        require(parkRequest.label.isRequest && parkRequest.label is ParkLabel)
        return addResponseEvents(parkRequest).first
    }

    fun addUnparkEvent(iThread: Int, unparkingThreadId: Int): ThreadEvent {
        val label = UnparkLabel(unparkingThreadId)
        return addSendEvent(iThread, label)
    }

    /**
     * Calculates the view for specific memory location observed at the given point of execution
     * given by [observation] vector clock. Memory location view is a vector clock itself
     * that maps each thread id to the last write access event to the given memory location at the given thread.
     *
     * @param location the memory location.
     * @param observation the vector clock specifying the point of execution for the view calculation.
     * @return the view (i.e. vector clock) for the given memory location.
     *
     * TODO: move to Execution?
     */
    fun calculateMemoryLocationView(location: MemoryLocation, observation: ExecutionFrontier): ExecutionFrontier =
        observation.threadMap.map { (tid, event) ->
            val lastWrite = event
                ?.ensure { it in currentExecution }
                ?.pred(inclusive = true) {
                    it.label.asMemoryAccessLabel(location)?.takeIf { label -> label.isWrite } != null
                }
            (tid to lastWrite)
        }.let {
            executionFrontierOf(*it.toTypedArray())
        }

    /**
     * Calculates a list of all racy writes to specific memory location observed at the given point of execution
     * given by [observation] vector clock. In other words, the resulting list contains all program-order maximal
     * racy writes observed at the given point.
     *
     * @param location the memory location.
     * @param observation the vector clock specifying the point of execution for the view calculation.
     * @return list of program-order maximal racy write events.
     *
     * TODO: move to Execution?
     */
    fun calculateRacyWrites(location: MemoryLocation, observation: ExecutionFrontier): List<ThreadEvent> {
        val writes = calculateMemoryLocationView(location, observation).events
        return writes.filter { write ->
            !writes.any { other ->
                causalityOrder.lessThan(write, other)
            }
        }
    }

}

private class BacktrackableEvent(
    label: EventLabel,
    threadId: Int,
    parent: ThreadEvent?,
    causalityClock: VectorClock,
    senders: List<ThreadEvent> = listOf(),
    allocation: ThreadEvent? = null,
    source: ThreadEvent? = null,
    /**
     * State of the execution frontier at the point when event is created.
     */
    val frontier: ExecutionFrontier,
    pinnedEvents: MutableExecutionFrontier,
) : AbstractAtomicThreadEvent(label, threadId, parent, causalityClock, senders, allocation, source) {

    var visited: Boolean = false
        private set

    /**
     * Frontier of pinned events.
     * Pinned events are the events that should not be
     * considered for branching in an exploration starting from this event.
     */
    val pinnedEvents: ExecutionFrontier =
        pinnedEvents.apply { set(threadId, this@BacktrackableEvent) }

    init {
        validate()
    }

    fun visit() {
        visited = true
    }

}