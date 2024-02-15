/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.coverage

import com.intellij.rt.coverage.data.ProjectData
import com.intellij.rt.coverage.instrumentation.CoverageRuntime
import com.intellij.rt.coverage.util.CoverageReport
import com.intellij.rt.coverage.util.classFinder.ClassFinder
import com.intellij.rt.coverage.verify.ProjectTargetProcessor
import com.intellij.rt.coverage.verify.Verifier.CollectedCoverage
import java.util.regex.Pattern

/**
 * Creates object with coverage options.
 *
 * @param branchCoverage flag to run line coverage or branch coverage otherwise.
 * @param excludePatterns patterns for classnames to exclude from coverage report (use can use regex syntax).
 * @param onShutdown callback with coverage results.
 */
class CoverageOptions(
    private val branchCoverage: Boolean = false,
    excludePatterns: List<String> = listOf(),
    private val onShutdown: ((ProjectData, CollectedCoverage) -> Unit)? = null,
) {
    private val excludes = listOf<Pattern>(
        Pattern.compile("org\\.jetbrains\\.kotlinx\\.lincheck\\..*"), // added to exclude ManagedStrategyStateHolder
    ) + excludePatterns.map(Pattern::compile)
    val projectData = ProjectData(null, branchCoverage, null)
    val cf = ClassFinder(listOf(), excludes)
    var coverageResult: CoverageResult? = null
    private val collectedCoverage: CollectedCoverage = CollectedCoverage()


    init {
        // only allow to insert `__$hits$__[index] = 1` instructions by coverage transformer
        com.intellij.rt.coverage.util.OptionsUtil.CALCULATE_HITS_COUNT = false

        projectData.excludePatterns = excludes
        CoverageRuntime.installRuntime(projectData)
        CoverageRuntime.installRuntime(projectData)
    }

    fun collectCoverage() {
        CoverageReport.finalizeCoverage(projectData, false, cf, false)

        for (classData in projectData.classesCollection) {
            collectedCoverage.add(ProjectTargetProcessor.collectClassCoverage(projectData, classData))
        }

        coverageResult = CoverageResult(collectedCoverage)
    }

    fun onShutdown() {
        onShutdown?.let { it(projectData, collectedCoverage) }
    }
}