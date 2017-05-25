/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.gradle.internal

import com.android.build.gradle.BaseExtension
import com.android.build.gradle.api.AndroidSourceSet
import com.android.builder.model.SourceProvider
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.plugin.*
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectOutputStream
import javax.xml.bind.DatatypeConverter.printBase64Binary

// apply plugin: 'kotlin-kapt'
class Kapt3GradleSubplugin : Plugin<Project> {
    companion object {
        fun isEnabled(project: Project) = project.plugins.findPlugin(Kapt3GradleSubplugin::class.java) != null
    }

    override fun apply(project: Project) {}
}

abstract class KaptVariantData<T>(val variantData: T) {
    abstract val name: String
    abstract val sourceProviders: Iterable<SourceProvider>
    abstract fun addJavaSourceFoldersToModel(generatedFilesDir: File)
    abstract val annotationProcessorOptions: Map<String, String>?
    abstract fun wireKaptTask(project: Project, task: KaptTask, kotlinTask: KotlinCompile, javaTask: AbstractCompile)
}

// Subplugin for the Kotlin Gradle plugin
class Kapt3KotlinGradleSubplugin : KotlinGradleSubplugin<KotlinCompile> {
    companion object {
        private val VERBOSE_OPTION_NAME = "kapt.verbose"

        val MAIN_KAPT_CONFIGURATION_NAME = "kapt"

        fun getKaptConfigurationName(sourceSetName: String): String {
            return if (sourceSetName != "main")
                "$MAIN_KAPT_CONFIGURATION_NAME${sourceSetName.capitalize()}"
            else
                MAIN_KAPT_CONFIGURATION_NAME
        }

        fun Project.findKaptConfiguration(sourceSetName: String): Configuration? {
            return project.configurations.findByName(getKaptConfigurationName(sourceSetName))
        }

        fun findMainKaptConfiguration(project: Project) = project.findKaptConfiguration(MAIN_KAPT_CONFIGURATION_NAME)

        fun getKaptClasssesDir(project: Project, sourceSetName: String): File =
                File(project.project.buildDir, "tmp/kapt3/classes/$sourceSetName")
    }

    private val kotlinToKaptTasksMap = mutableMapOf<KotlinCompile, KaptTask>()

    override fun isApplicable(project: Project, task: KotlinCompile) = Kapt3GradleSubplugin.isEnabled(project)

    fun getKaptGeneratedDir(project: Project, sourceSetName: String): File {
        return File(project.project.buildDir, "generated/source/kapt/$sourceSetName")
    }

    fun getKaptGeneratedDirForKotlin(project: Project, sourceSetName: String): File {
        return File(project.project.buildDir, "generated/source/kaptKotlin/$sourceSetName")
    }

    fun getKaptStubsDir(project: Project, sourceSetName: String): File {
        val dir = File(project.project.buildDir, "tmp/kapt3/stubs/$sourceSetName")
        dir.mkdirs()
        return dir
    }

    private inner class Kapt3SubpluginContext(
            val project: Project,
            val kotlinCompile: KotlinCompile,
            val javaCompile: AbstractCompile,
            val kaptVariantData: KaptVariantData<*>?,
            val sourceSetName: String,
            val kaptExtension: KaptExtension,
            val kaptClasspath: MutableList<File>) {
        val sourcesOutputDir = getKaptGeneratedDir(project, sourceSetName)
        val kotlinSourcesOutputDir = getKaptGeneratedDirForKotlin(project, sourceSetName)
        val classesOutputDir = getKaptClasssesDir(project, sourceSetName)
    }

    override fun apply(
            project: Project, 
            kotlinCompile: KotlinCompile,
            javaCompile: AbstractCompile,
            variantData: Any?,
            javaSourceSet: SourceSet?
    ): List<SubpluginOption> {
        assert((variantData != null) xor (javaSourceSet != null))

        val kaptClasspath = arrayListOf<File>()

        fun handleSourceSet(sourceSetName: String) {
            val kaptConfiguration = project.findKaptConfiguration(sourceSetName)
            val filteredDependencies = kaptConfiguration?.dependencies?.filter {
                it.group != getGroupName() || it.name != getArtifactName()
            } ?: emptyList()

            if (kaptConfiguration != null && filteredDependencies.isNotEmpty()) {
                javaCompile.dependsOn(kaptConfiguration.buildDependencies)
                kaptClasspath.addAll(kaptConfiguration.resolve())
            }
        }

        val kaptVariantData = variantData as? KaptVariantData<*>

        val sourceSetName = if (kaptVariantData != null) {
            for (provider in kaptVariantData.sourceProviders) {
                handleSourceSet((provider as AndroidSourceSet).name)
            }

            kaptVariantData.name
        }
        else {
            if (javaSourceSet == null) error("Java source set should not be null")

            handleSourceSet(javaSourceSet.name)
            javaSourceSet.name
        }

        val kaptExtension = project.extensions.getByType(KaptExtension::class.java)

        val context = Kapt3SubpluginContext(project, kotlinCompile, javaCompile,
                kaptVariantData, sourceSetName, kaptExtension, kaptClasspath)

        context.createKaptKotlinTask()

        /** Plugin options are applied to kapt*Compile inside [createKaptKotlinTask] */
        return emptyList()
    }

    override fun getSubpluginKotlinTasks(project: Project, kotlinCompile: KotlinCompile): List<KaptTask> {
        return kotlinToKaptTasksMap[kotlinCompile]?.let { listOf(it) } ?: emptyList()
    }

    // This method should be called no more than once for each Kapt3SubpluginContext
    private fun Kapt3SubpluginContext.buildOptions(): List<SubpluginOption> {
        val pluginOptions = mutableListOf<SubpluginOption>()

        val generatedFilesDir = getKaptGeneratedDir(project, sourceSetName)
        kaptVariantData?.addJavaSourceFoldersToModel(generatedFilesDir)

        pluginOptions += SubpluginOption("aptOnly", "true")
        disableAnnotationProcessingInJavaTask()

        // Skip annotation processing in kotlinc if no kapt dependencies were provided
        if (kaptClasspath.isEmpty()) return pluginOptions

        kaptClasspath.forEach { pluginOptions += SubpluginOption("apclasspath", it.absolutePath) }

        javaCompile.source(generatedFilesDir)

        pluginOptions += SubpluginOption("sources", generatedFilesDir.canonicalPath)
        pluginOptions += SubpluginOption("classes", getKaptClasssesDir(project, sourceSetName).canonicalPath)

        val annotationProcessors = kaptExtension.processors
        if (annotationProcessors.isNotEmpty()) {
            pluginOptions += SubpluginOption("processors", annotationProcessors)
        }

        val androidPlugin = kaptVariantData?.let {
            project.extensions.findByName("android") as? BaseExtension
        }

        val androidOptions = kaptVariantData?.annotationProcessorOptions ?: emptyMap()

        kotlinSourcesOutputDir.mkdirs()

        val apOptions = kaptExtension.getAdditionalArguments(
                project,
                kaptVariantData?.variantData,
                androidPlugin
        ) + androidOptions + mapOf("kapt.kotlin.generated" to kotlinSourcesOutputDir.absolutePath)

        pluginOptions += SubpluginOption("apoptions", encodeOptions(apOptions))

        pluginOptions += SubpluginOption("javacArguments", encodeOptions(kaptExtension.getJavacOptions()))

        addMiscOptions(pluginOptions)

        return pluginOptions
    }

    fun encodeOptions(options: Map<String, String>): String {
        val os = ByteArrayOutputStream()
        val oos = ObjectOutputStream(os)

        oos.writeInt(options.size)
        for ((k, v) in options.entries) {
            oos.writeUTF(k)
            oos.writeUTF(v)
        }

        oos.flush()
        return printBase64Binary(os.toByteArray())
    }

    private fun Kapt3SubpluginContext.addMiscOptions(pluginOptions: MutableList<SubpluginOption>) {
        if (kaptExtension.generateStubs) {
            project.logger.warn("'kapt.generateStubs' is not used by the 'kotlin-kapt' plugin")
        }

        pluginOptions += SubpluginOption("useLightAnalysis", "${kaptExtension.useLightAnalysis}")
        pluginOptions += SubpluginOption("correctErrorTypes", "${kaptExtension.correctErrorTypes}")
        pluginOptions += SubpluginOption("stubs", getKaptStubsDir(project, sourceSetName).canonicalPath)

        if (project.hasProperty(VERBOSE_OPTION_NAME) && project.property(VERBOSE_OPTION_NAME) == "true") {
            pluginOptions += SubpluginOption("verbose", "true")
        }
    }

    private fun Kapt3SubpluginContext.createKaptKotlinTask() {
        // Replace compile*Kotlin to kapt*Kotlin
        assert(kotlinCompile.name.startsWith("compile"))
        val kaptTaskName = kotlinCompile.name.replaceFirst("compile", "kapt")
        val kaptTask = project.tasks.create(kaptTaskName, KaptTask::class.java)
        kaptTask.kotlinCompileTask = kotlinCompile
        kotlinToKaptTasksMap[kotlinCompile] = kaptTask

        project.resolveSubpluginArtifacts(listOf(this@Kapt3KotlinGradleSubplugin)).flatMap { it.value }.forEach {
            kaptTask.pluginOptions.addClasspathEntry(it)
        }

        kaptTask.stubsDir = getKaptStubsDir(project, sourceSetName)

        kaptTask.mapClasspath { kotlinCompile.classpath }
        kaptTask.destinationDir = sourcesOutputDir
        kaptTask.classesDir = classesOutputDir

        kotlinCompile.dependsOn(kaptTask)
        kotlinCompile.source(sourcesOutputDir, kotlinSourcesOutputDir)

        if (kaptVariantData != null) {
            kaptVariantData.wireKaptTask(project, kaptTask, kotlinCompile, javaCompile)
        } else {
            wireKaptTaskForJavaProject(kaptTask, kotlinCompile, javaCompile)
        }

        val pluginOptions = kaptTask.pluginOptions
        val compilerPluginId = getCompilerPluginId()
        for (option in buildOptions()) {
            pluginOptions.addPluginArgument(compilerPluginId, option.key, option.value)
        }
    }

    private fun Kapt3SubpluginContext.disableAnnotationProcessingInJavaTask() {
        (javaCompile as? JavaCompile)?.let { javaCompile ->
            val options = javaCompile.options
            // 'android-apt' (com.neenbedankt) adds a File instance to compilerArgs (List<String>).
            // Although it's not our problem, we need to handle this case properly.
            val oldCompilerArgs: List<Any> = options.compilerArgs
            val newCompilerArgs = oldCompilerArgs.filterTo(mutableListOf()) {
                it !is CharSequence || !it.toString().startsWith("-proc:")
            }
            newCompilerArgs.add("-proc:none")
            @Suppress("UNCHECKED_CAST")
            options.compilerArgs = newCompilerArgs as List<String>
        }
    }

    override fun getCompilerPluginId() = "org.jetbrains.kotlin.kapt3"
    override fun getGroupName() = "org.jetbrains.kotlin"
    override fun getArtifactName() = "kotlin-annotation-processing"
}

internal fun wireKaptTaskForJavaProject(task: KaptTask, kotlinTask: KotlinCompile, javaTask: AbstractCompile) {
    task.dependsOn(*(javaTask.dependsOn.filter { it !== kotlinTask && it != kotlinTask.name }.toTypedArray()))
    javaTask.source(task.destinationDir)
}