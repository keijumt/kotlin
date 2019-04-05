/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.mpp

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedDependency
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilationToRunnableFiles
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.sources.KotlinDependencyScope
import org.jetbrains.kotlin.gradle.targets.metadata.getPublishedPlatformCompilations

internal data class DependencySourceSetVisibilityResult(
    val sourceSetsVisibleByThisSourceSet: Set<String>,
    val sourceSetsVisibleThroughDependsOn: Set<String>
)

internal class SourceSetVisibilityProvider(
    private val project: Project
) {
    fun getVisibleSourceSets(
        visibleFrom: KotlinSourceSet,
        dependencyScopes: Iterable<KotlinDependencyScope>,
        mppDependency: ResolvedDependency,
        dependencyProjectStructureMetadata: KotlinProjectStructureMetadata,
        otherProject: Project?
    ): DependencySourceSetVisibilityResult {
        val visibleByThisSourceSet =
            getVisibleSourceSetsImpl(visibleFrom, dependencyScopes, mppDependency, dependencyProjectStructureMetadata, otherProject)

        val visibleByParents = visibleFrom.dependsOn
            .flatMapTo(mutableSetOf()) {
                getVisibleSourceSetsImpl(it, dependencyScopes, mppDependency, dependencyProjectStructureMetadata, otherProject)
            }

        return DependencySourceSetVisibilityResult(visibleByThisSourceSet, visibleByParents)
    }

    @Suppress("UnstableApiUsage")
    private fun getVisibleSourceSetsImpl(
        visibleFrom: KotlinSourceSet,
        dependencyScopes: Iterable<KotlinDependencyScope>,
        mppDependency: ResolvedDependency,
        dependencyProjectMetadata: KotlinProjectStructureMetadata,
        otherProject: Project?
    ): Set<String> {
        val compilations = CompilationSourceSetUtil.compilationsBySourceSets(project).getValue(visibleFrom)

        var visiblePlatformVariantNames: Set<String> =
            compilations
                .filter { it.target.platformType != KotlinPlatformType.common }
                .flatMapTo(mutableSetOf()) { compilation ->
                    val configurations = dependencyScopes.mapNotNullTo(mutableSetOf()) { scope ->
                        project.resolvableConfigurationFromCompilationByScope(compilation, scope)
                    }
                    configurations.mapNotNull { configuration ->
                        // Resolve the configuration but don't trigger artifacts download, only download component metadata:
                        configuration.incoming.resolutionResult.allComponents
                            .find {
                                it.moduleVersion?.group == mppDependency.moduleGroup && it.moduleVersion?.name == mppDependency.moduleName
                            }
                            ?.variant?.displayName
                    }
                }

        if (visiblePlatformVariantNames.isEmpty()) {
            return emptySet()
        }

        if (otherProject != null) {
            val publishedVariants = getPublishedPlatformCompilations(otherProject).keys
            visiblePlatformVariantNames = visiblePlatformVariantNames
                .map { configurationName ->
                    publishedVariants.first { it.dependencyConfigurationName == configurationName }.name
                }.toSet()
        }

        return dependencyProjectMetadata.sourceSetNamesByVariantName
            .filterKeys { it in visiblePlatformVariantNames }
            .values.let { if (it.isEmpty()) emptySet() else it.reduce { acc, item -> acc intersect item } }
    }
}

internal fun Project.resolvableConfigurationFromCompilationByScope(
    compilation: KotlinCompilation<*>,
    scope: KotlinDependencyScope
): Configuration? {
    val configurationName = when (scope) {
        KotlinDependencyScope.API_SCOPE, KotlinDependencyScope.IMPLEMENTATION_SCOPE, KotlinDependencyScope.COMPILE_ONLY_SCOPE -> compilation.compileDependencyConfigurationName

        KotlinDependencyScope.RUNTIME_ONLY_SCOPE ->
            (compilation as? KotlinCompilationToRunnableFiles<*>)?.runtimeDependencyConfigurationName
                ?: return null
    }

    return project.configurations.getByName(configurationName)
}

