/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package org.jetbrains.kotlinx.lincheck_benchmark

import org.jetbrains.kotlinx.lincheck.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import kotlin.math.*
import java.io.File


typealias BenchmarkID = String

@Serializable
data class BenchmarksReport(
    val data: Map<String, BenchmarkStatistics>
)

@Serializable
data class BenchmarkStatistics(
    val mode: LincheckMode,
    val name: String,
    val runningTimeNano: Long,
    val iterationsCount: Int,
    val invocationsCount: Int,
    val scenariosStatistics: List<ScenarioStatistics>,
    // TODO: check array is not empty
    val invocationsRunningTimeNano: LongArray,
)

@Serializable
data class ScenarioStatistics(
    val threads: Int,
    val operations: Int,
    val invocationsCount: Int,
    val runningTimeNano: Long,
    val invocationAverageTimeNano: Long,
    val invocationStandardDeviationTimeNano: Long,
)

val BenchmarksReport.benchmarkNames: List<String>
    get() = data.map { (_, statistics) -> statistics.name }.distinct()

fun BenchmarksReport.saveJson(filename: String) {
    val file = File("$filename.json")
    file.outputStream().use { outputStream ->
        Json.encodeToStream(this, outputStream)
    }
}

val BenchmarkStatistics.id: BenchmarkID
    get() = "$name-$mode"

fun Statistics.toBenchmarkStatistics(name: String, mode: LincheckMode) = BenchmarkStatistics(
    name = name,
    mode = mode,
    runningTimeNano = runningTimeNano,
    iterationsCount = iterationsCount,
    invocationsCount = invocationsCount,
    invocationsRunningTimeNano = iterationsStatistics
        .map { it.invocationsRunningTimeNano }
        .flatten(),
    scenariosStatistics = iterationsStatistics
        .groupBy { (it.scenario.nThreads to it.scenario.parallelExecution[0].size) }
        .map { (key, statistics) ->
            val (threads, operations) = key
            val invocationsRunningTime = statistics
                .map { it.invocationsRunningTimeNano }
                .flatten()
            ScenarioStatistics(
                threads = threads,
                operations = operations,
                invocationsCount = statistics.sumOf { it.invocationsCount },
                runningTimeNano = statistics.sumOf { it.runningTimeNano },
                invocationAverageTimeNano = invocationsRunningTime.average().toLong(),
                invocationStandardDeviationTimeNano = invocationsRunningTime.standardDeviation().toLong(),
            )
        }
)

fun Iterable<LongArray>.flatten(): LongArray {
    val size = sumOf { it.size }
    val result = LongArray(size)
    var i = 0
    for (array in this) {
        for (element in array) {
            result[i++] = element
        }
    }
    return result
}

fun LongArray.standardDeviation(): Double {
    val mean = average()
    return sqrt(map { (it - mean).pow(2) }.average())
}