/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.kotlin.dsl.resolver


import org.gradle.kotlin.dsl.concurrent.EventLoop
import org.gradle.kotlin.dsl.concurrent.future

import org.gradle.kotlin.dsl.tooling.models.EditorPosition
import org.gradle.kotlin.dsl.tooling.models.EditorReport
import org.gradle.kotlin.dsl.tooling.models.EditorReportSeverity
import org.gradle.kotlin.dsl.tooling.models.KotlinBuildScriptModel

import org.gradle.tooling.BuildException

import java.io.File
import java.net.URI

import kotlin.script.dependencies.KotlinScriptExternalDependencies
import kotlin.script.dependencies.ScriptContents
import kotlin.script.dependencies.ScriptContents.Position
import kotlin.script.dependencies.ScriptDependenciesResolver
import kotlin.script.dependencies.ScriptDependenciesResolver.ReportSeverity


internal
typealias Environment = Map<String, Any?>


private
typealias Report = (ReportSeverity, String, Position?) -> Unit


private
fun Report.warning(message: String, position: Position? = null) =
    invoke(ReportSeverity.WARNING, message, position)


private
fun Report.error(message: String, position: Position? = null) =
    invoke(ReportSeverity.ERROR, message, position)


private
fun Report.fatal(message: String, position: Position? = null) =
    invoke(ReportSeverity.FATAL, message, position)


private
fun Report.editorReport(editorReport: EditorReport) = editorReport.run {
    invoke(severity.toIdeSeverity(), message, position?.toIdePosition())
}


private
fun EditorReportSeverity.toIdeSeverity(): ReportSeverity =
    when (this) {
        EditorReportSeverity.WARNING -> ReportSeverity.WARNING
        EditorReportSeverity.ERROR -> ReportSeverity.ERROR
    }


private
fun EditorPosition.toIdePosition(): Position =
    Position(if (line == 0) 0 else line - 1, column)


class KotlinBuildScriptDependenciesResolver internal constructor(

    private
    val logger: ResolverEventLogger

) : ScriptDependenciesResolver {

    @Suppress("unused")
    constructor() : this(DefaultResolverEventLogger)

    override fun resolve(
        script: ScriptContents,
        environment: Map<String, Any?>?,
        /**
         * Shows a message in the IDE.
         *
         * To report whole file errors (e.g. failure to query for dependencies), one can just pass a
         * null position so the error/warning will be shown in the top panel of the editor
         *
         * Also there is a FATAL Severity - in this case the highlighting of the file will be
         * switched off (may be it is useful for some errors).
         */
        report: (ReportSeverity, String, Position?) -> Unit,
        previousDependencies: KotlinScriptExternalDependencies?
    ) = future {

        try {
            logger.log(ResolutionRequest(script.file, environment, previousDependencies))
            assembleDependenciesFrom(
                script.file,
                environment!!,
                report,
                previousDependencies
            )
        } catch (e: BuildException) {
            logger.log(ResolutionFailure(script.file, e))
            if (previousDependencies == null) report.fatal(EditorMessages.buildConfigurationFailed)
            else report.warning(EditorMessages.buildConfigurationFailedUsingPrevious)
            previousDependencies
        } catch (e: Exception) {
            logger.log(ResolutionFailure(script.file, e))
            if (previousDependencies == null) report.fatal(EditorMessages.failure)
            else report.error(EditorMessages.failureUsingPrevious)
            previousDependencies
        }
    }

    private
    suspend fun assembleDependenciesFrom(
        scriptFile: File?,
        environment: Environment,
        report: Report,
        previousDependencies: KotlinScriptExternalDependencies?
    ): KotlinScriptExternalDependencies? {

        val scriptModelRequest = scriptModelRequestFrom(scriptFile, environment)
        logger.log(SubmittedModelRequest(scriptFile, scriptModelRequest))

        val response = DefaultKotlinBuildScriptModelRepository.scriptModelFor(scriptModelRequest)
        if (response == null) {
            logger.log(RequestCancelled(scriptFile, scriptModelRequest))
            return null
        }
        logger.log(ReceivedModelResponse(scriptFile, response))

        response.editorReports.forEach { editorReport ->
            report.editorReport(editorReport)
        }

        return when {
            response.exceptions.isEmpty() ->
                dependenciesFrom(response).also {
                    logger.log(ResolvedDependencies(scriptFile, it))
                }
            previousDependencies != null && previousDependencies.classpath.count() > response.classPath.size ->
                previousDependencies.also {
                    logger.log(ResolvedToPreviousWithErrors(scriptFile, previousDependencies, response.exceptions))
                }
            else ->
                dependenciesFrom(response).also {
                    logger.log(ResolvedDependenciesWithErrors(scriptFile, it, response.exceptions))
                }
        }
    }

    private
    fun scriptModelRequestFrom(scriptFile: File?, environment: Environment): KotlinBuildScriptModelRequest {

        @Suppress("unchecked_cast")
        fun stringList(key: String) =
            (environment[key] as? List<String>) ?: emptyList()

        fun path(key: String) =
            (environment[key] as? String)?.let(::File)

        val projectDir = environment["projectRoot"] as File
        return KotlinBuildScriptModelRequest(
            projectDir = projectDir,
            scriptFile = scriptFile,
            gradleInstallation = gradleInstallationFrom(environment),
            gradleUserHome = path("gradleUserHome"),
            javaHome = path("gradleJavaHome"),
            options = stringList("gradleOptions"),
            jvmOptions = stringList("gradleJvmOptions")
        )
    }

    private
    fun gradleInstallationFrom(environment: Environment): GradleInstallation =
        (environment["gradleHome"] as? File)?.let(GradleInstallation::Local)
            ?: (environment["gradleUri"] as? URI)?.let(GradleInstallation::Remote)
            ?: (environment["gradleVersion"] as? String)?.let(GradleInstallation::Version)
            ?: GradleInstallation.Wrapper

    private
    fun dependenciesFrom(response: KotlinBuildScriptModel) =
        KotlinBuildScriptDependencies(
            response.classPath,
            response.sourcePath,
            response.implicitImports
        )
}


internal
class KotlinBuildScriptDependencies(
    override val classpath: Iterable<File>,
    override val sources: Iterable<File>,
    override val imports: Iterable<String>
) : KotlinScriptExternalDependencies


/**
 * Handles all incoming [KotlinBuildScriptModelRequest]s via a single [EventLoop] to avoid spawning
 * multiple competing Gradle daemons.
 */
private
object DefaultKotlinBuildScriptModelRepository : KotlinBuildScriptModelRepository()
