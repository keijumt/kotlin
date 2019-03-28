/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.mpp

import org.gradle.api.Project
import org.gradle.api.artifacts.*
import org.gradle.api.file.FileCollection
import org.jetbrains.kotlin.gradle.dsl.multiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.sources.getSourceSetHierarchy
import org.jetbrains.kotlin.gradle.targets.metadata.ALL_SOURCE_SETS_API_METADATA_CONFIGURATION_NAME
import org.jetbrains.kotlin.gradle.targets.metadata.buildKotlinProjectStructureMetadata
import java.io.File
import java.util.*
import java.util.concurrent.Callable
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

internal open class KotlinGranularMetadataTransformation(
    val project: Project,
    val kotlinSourceSet: KotlinSourceSet,
    val outputsDir: File
) {
    private val lazyTransform: Unit by lazy {
        outputsDir.deleteRecursively()
        transform()
    }

    private val transformedOutputsImpl = mutableListOf<FileCollection>()

    private val transformedOutputs = project.files(Callable {
        lazyTransform
        transformedOutputsImpl
    })

    private val visitedDependenciesImpl = mutableSetOf<ResolvedDependency>()

    val unrequestedDependencies: Set<ResolvedDependency> by lazy {
        lazyTransform
        allDependenciesImpl - visitedDependenciesImpl
    }

    private lateinit var allDependenciesImpl: Set<ResolvedDependency>

    val allOutputFiles: FileCollection
        get() = transformedOutputs//.filter { it.exists() }

    private val knownProjectDependencies = mutableMapOf<Pair<String?, String>, ProjectDependency>()

    private fun transform() {
        // Keep parents of each dependency, too. We need a dependency's parent when it's an MPP's metadata module dependency:
        // in this case, the parent is the MPP's root module
        data class ResolvedDependencyWithParent(
            val dependency: ResolvedDependency,
            val parent: ResolvedDependency?
        )

        val requestedModules = run {
            val requestedDirectDependencies = kotlinSourceSet.getSourceSetHierarchy().flatMapTo(mutableSetOf()) {
                project.configurations.getByName(it.apiConfigurationName).allDependencies
            }

            requestedDirectDependencies.filterIsInstance<ProjectDependency>().associateTo(knownProjectDependencies) {
                (it.group to it.name) to it
            }

            requestedDirectDependencies.map { it.group to it.name }.toSet()
        }

        val resolvedDependenciesFromAllSourceSets =
            project.configurations.getByName(ALL_SOURCE_SETS_API_METADATA_CONFIGURATION_NAME)
                .resolvedConfiguration.lenientConfiguration

        allDependenciesImpl = mutableSetOf<ResolvedDependency>().apply {
            fun visit(resolvedDependency: ResolvedDependency) {
                if (add(resolvedDependency)) {
                    resolvedDependency.children.forEach { visit(it) }
                }
            }
            resolvedDependenciesFromAllSourceSets.firstLevelModuleDependencies.forEach { visit(it) }
        }

        val resolvedDependencyQueue: Queue<ResolvedDependencyWithParent> = ArrayDeque<ResolvedDependencyWithParent>().apply {
            addAll(
                resolvedDependenciesFromAllSourceSets.firstLevelModuleDependencies
                    .filter { (it.moduleGroup to it.moduleName) in requestedModules }
                    .map { ResolvedDependencyWithParent(it, null) }
            )
        }

        while (resolvedDependencyQueue.isNotEmpty()) {
            val (resolvedDependency, parent) = resolvedDependencyQueue.poll()

            val group = resolvedDependency.moduleGroup
            val name = resolvedDependency.moduleName

            var projectDependency: ProjectDependency? = null

            knownProjectDependencies[group to name]?.let {
                projectDependency = it
                val dependencyProject = it.dependencyProject
                val configuration = dependencyProject.configurations.getByName(resolvedDependency.configuration)
                configuration.allDependencies.filterIsInstance<ProjectDependency>().associateTo(knownProjectDependencies) {
                    (it.group to it.name) to it
                }
            }

            visitedDependenciesImpl.add(resolvedDependency)

            val transitiveDependenciesToVisit =
                processModuleAndGetTransitiveDependencies(resolvedDependency, parent, projectDependency)

            resolvedDependencyQueue.addAll(
                transitiveDependenciesToVisit
                    .filter { it !in visitedDependenciesImpl }
                    .map { ResolvedDependencyWithParent(it, resolvedDependency) }
            )
        }
    }

    /**
     * If the [module] it is an MPP metadata module, we extract [KotlinProjectStructureMetadata] and do the following:
     *
     * * determine the set *S* of source sets that should be seen in the [kotlinSourceSet] by finding which variants the [parent]
     *   dependency got resolved for the compilations where [kotlinSourceSet] participates:
     *
     * * transform the single Kotlin metadata artifact into a set of Kotlin metadata artifacts for the particular source sets in
     *   *S* and add them to [transformedOutputsImpl]
     *
     * * based on the project structure metadata, determine which of the module's dependencies are requested by the
     *   source sets in *S*, only these transitive dependencies, ignore the others;
     */
    private fun processModuleAndGetTransitiveDependencies(
        module: ResolvedDependency,
        parent: ResolvedDependency?,
        projectDependency: ProjectDependency?
    ): Set<ResolvedDependency> {

        // If the module is non-MPP, we need to visit all of its children, but otherwise, they are filtered below.
        val transitiveDependenciesToVisit = module.children.toMutableSet()

        val mppDependencyMetadataExtractor =
            when {
                projectDependency != null -> ProjectMppDependencyMetadataExtractor(project, module, projectDependency.dependencyProject)
                parent != null -> JarArtifactMppDependencyMetadataExtractor(project, module, outputsDir)
                else -> null
            }

        val projectStructureMetadata = mppDependencyMetadataExtractor?.getProjectStructureMetadata()

        if (projectStructureMetadata != null) {
            val allVisibleSourceSets = SourceSetVisibilityProvider(project).getVisibleSourceSets(
                kotlinSourceSet,
                parent ?: module,
                projectStructureMetadata,
                projectDependency?.dependencyProject
            )

            // Keep only the transitive dependencies requested by the visible source sets:
            val requestedTransitiveDependencies: Set<Pair<String, String>> =
                mutableSetOf<Pair<String, String>>().apply {
                    projectStructureMetadata.sourceSetModuleDependencies
                        .filterKeys { it in allVisibleSourceSets }
                        .forEach { addAll(it.value) }
                }

            transitiveDependenciesToVisit.removeIf {
                (it.moduleGroup to it.moduleName) !in requestedTransitiveDependencies
            }

            val visibleSourceSetsExcludingDependsOn: Set<String> = SourceSetVisibilityProvider(project)
                .getVisibleSourceSetsExcludingDependsOn(
                    kotlinSourceSet,
                    parent ?: module,
                    projectStructureMetadata,
                    projectDependency?.dependencyProject
                )

            transformedOutputsImpl.add(mppDependencyMetadataExtractor.getVisibleSourceSetsMetadata(visibleSourceSetsExcludingDependsOn))
        }

        return transitiveDependenciesToVisit
    }
}

@Suppress("UnstableApiUsage")
internal fun KotlinGranularMetadataTransformation.applyToConfiguration(project: Project, configuration: Configuration) {
    project.dependencies.add(configuration.name, project.dependencies.create(allOutputFiles))

    configuration.withDependencies { _ ->
        unrequestedDependencies.forEach {
            configuration.exclude(mapOf("group" to it.moduleGroup, "module" to it.moduleName))
        }
    }
}

private abstract class MppDependencyMetadataExtractor(val project: Project, val dependency: ResolvedDependency) {
    abstract fun getProjectStructureMetadata(): KotlinProjectStructureMetadata?
    abstract fun getVisibleSourceSetsMetadata(visibleSourceSetNames: Set<String>): FileCollection
}

private class ProjectMppDependencyMetadataExtractor(
    project: Project,
    dependency: ResolvedDependency,
    private val dependencyProject: Project
) : MppDependencyMetadataExtractor(project, dependency) {
    override fun getProjectStructureMetadata(): KotlinProjectStructureMetadata? = buildKotlinProjectStructureMetadata(dependencyProject)

    override fun getVisibleSourceSetsMetadata(visibleSourceSetNames: Set<String>): FileCollection =
        project.files(
            dependencyProject.multiplatformExtension.targets.getByName(KotlinMultiplatformPlugin.METADATA_TARGET_NAME).compilations
                .filter { it.defaultSourceSet.name in visibleSourceSetNames }
                .map { it.output.classesDirs }
        )
}

private class JarArtifactMppDependencyMetadataExtractor(
    project: Project,
    dependency: ResolvedDependency,
    private val outputsDir: File
) : MppDependencyMetadataExtractor(project, dependency) {

    private val artifact = dependency.moduleArtifacts.singleOrNull { it.extension == "jar" }

    override fun getProjectStructureMetadata(): KotlinProjectStructureMetadata? {
        val artifactFile = artifact?.file ?: return null

        return ZipFile(artifactFile).use { zip ->
            val metadata = zip.getEntry("META-INF/$MULTIPLATFORM_PROJECT_METADATA_FILE_NAME")
                ?: return null

            val metadataXmlDocument = zip.getInputStream(metadata).use { inputStream ->
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(inputStream)
            }
            parseKotlinSourceSetMetadataFromXml(metadataXmlDocument)
        }
    }

    override fun getVisibleSourceSetsMetadata(visibleSourceSetNames: Set<String>): FileCollection {
        artifact as ResolvedArtifact
        val artifactFile = artifact.file
        val moduleId = artifact.moduleVersion.id
        return project.files(Callable { extractSourceSetMetadataFromJar(moduleId, visibleSourceSetNames, artifactFile) })
    }

    private fun extractSourceSetMetadataFromJar(
        module: ModuleVersionIdentifier,
        chooseSourceSetsByNames: Set<String>,
        artifactJar: File
    ): Iterable<File> {
        val moduleString = "${module.group}-${module.name}-${module.version}"
        val transformedModuleRoot = run { outputsDir.resolve(moduleString).also { it.mkdirs() } }

        ZipFile(artifactJar).use { zip ->
            zip.entries().asSequence().filter { it.name.substringBefore("/") in chooseSourceSetsByNames }
                .groupBy { it.name.substringBefore("/") }
                .forEach { (sourceSetName, entries) ->
                    val extractToJarFile = transformedModuleRoot.resolve("$moduleString-$sourceSetName.jar")
                    ZipOutputStream(extractToJarFile.outputStream()).use { zipOutput ->
                        entries.forEach forEachEntry@{ entry ->
                            if (entry.isDirectory) return@forEachEntry
                            val newEntry = ZipEntry(entry.name.substringAfter("/"))

                            zip.getInputStream(entry).use { inputStream ->
                                zipOutput.putNextEntry(newEntry)
                                zipOutput.write(inputStream.readBytes())
                                zipOutput.closeEntry()
                            }
                        }
                    }
                }
        }

        return transformedModuleRoot.listFiles().asList()
    }
}
