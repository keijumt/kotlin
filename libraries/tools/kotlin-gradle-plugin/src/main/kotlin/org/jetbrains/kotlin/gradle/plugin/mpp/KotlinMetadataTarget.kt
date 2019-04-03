/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.mpp

import org.gradle.api.Project
import org.gradle.api.artifacts.ConfigurablePublishArtifact
import org.gradle.api.attributes.Usage.JAVA_API
import org.jetbrains.kotlin.gradle.dsl.kotlinExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinTargetComponent
import org.jetbrains.kotlin.gradle.plugin.usageByName
import org.jetbrains.kotlin.gradle.targets.metadata.METADATA_DEPENDENCY_ELEMENTS_CONFIGURATION_NAME
import org.jetbrains.kotlin.gradle.utils.isGradleVersionAtLeast


open class KotlinMetadataTarget(project: Project) : KotlinOnlyTarget<KotlinCommonCompilation>(project, KotlinPlatformType.common) {
    override val kotlinComponents: Set<KotlinTargetComponent> by lazy {
        val usageContexts = mutableSetOf<DefaultKotlinUsageContext>()

        // This usage value is only needed for scopes mapping. Don't replace it with KotlinUsages
        val apiUsage = if (isGradleVersionAtLeast(5, 3)) "java-api-jars" else JAVA_API

        usageContexts += DefaultKotlinUsageContext(
            compilations.getByName(KotlinCompilation.MAIN_COMPILATION_NAME),
            project.usageByName(apiUsage),
            apiElementsConfigurationName
        )

        usageContexts += DefaultKotlinUsageContext(
            compilations.getByName(KotlinCompilation.MAIN_COMPILATION_NAME),
            project.usageByName(apiUsage),
            METADATA_DEPENDENCY_ELEMENTS_CONFIGURATION_NAME
        )

        val component =
            createKotlinVariant("kotlinMetadata", compilations.getByName(KotlinCompilation.MAIN_COMPILATION_NAME), usageContexts)

        val sourcesJarTask =
            sourcesJarTask(project, lazy { project.kotlinExtension.sourceSets.toSet() }, null, targetName.toLowerCase())

        val sourcesArtifactsConfiguration = project.configurations.create(disambiguateName("SourcesArtifacts"))

        component.sourcesArtifacts = setOf(
            project.artifacts.add(sourcesArtifactsConfiguration.name, sourcesJarTask).apply {
                this as ConfigurablePublishArtifact
                classifier = "sources"
            }
        )

        setOf(component)
    }
}