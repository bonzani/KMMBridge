package co.touchlab.faktory

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.UnknownTaskException
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.*
import org.jetbrains.kotlin.gradle.plugin.cocoapods.CocoapodsExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.*
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFrameworkConfig
import java.io.*
import java.util.*

interface KmmBridgeExtension {
    /**
     * The name of the kotlin framework, which will be wrapped into a cocoapod. May be the same or different from podName.
     * This should be the same as
     */
    val frameworkName: Property<String>

    val dependencyManagers: ListProperty<DependencyManager>

    /**
     * If the code is built externally, just pass in a path.
     */
    val xcFrameworkPath: Property<String>

    val artifactManager: Property<ArtifactManager>

    val faktoryReadKey: Property<String>

    val buildType: Property<NativeBuildType>

    val versionManager: Property<VersionManager>

    val versionPrefix: Property<String>

    fun s3Public(
        region: String,
        bucket: String,
        accessKeyId: String,
        secretAccessKey: String,
        makeArtifactsPublic: Boolean = true,
        altBaseUrl: String? = null,
    ) {
        artifactManager.set(
            AwsS3PublicArtifactManager(
                region,
                bucket,
                accessKeyId,
                secretAccessKey,
                makeArtifactsPublic,
                altBaseUrl
            )
        )
    }

    fun defaultUpload() {
        artifactManager.set(FaktoryServerArtifactManager())
    }

    fun Project.spm(
        spmDirectory: String? = null
    ) {
        val swiftPackageFolder = spmDirectory ?: projectDir.path
        val dependencyManager = SpmDependencyManager(swiftPackageFolder)
        dependencyManagers.set(dependencyManagers.getOrElse(emptyList()) + dependencyManager)
    }

    fun Project.cocoapods(specRepoUrl: String?) {
        kotlin.cocoapods // This will throw error if we didn't apply cocoapods plugin

        val specRepo = if (specRepoUrl == null) SpecRepo.Trunk else SpecRepo.Private(specRepoUrl)

        val dependencyManager = MultiRepoCocoapodsDependencyManager(specRepo)
        dependencyManagers.set(dependencyManagers.getOrElse(emptyList()) + dependencyManager)
    }
}

interface DependencyManager {
    /**
     * Do configuration specific to this `DependencyManager`. Generally this involves creating tasks that depend on
     * [uploadTask] and are dependencies of [publishRemoteTask].
     */
    fun doExtraConfiguration(project: Project, uploadTask: Task, publishRemoteTask: Task) {}
}

interface ArtifactManager {
    /**
     * Send the thing, and return a link to the thing...
     */
    fun deployArtifact(project: Project, zipFilePath: File, fileName: String): String
}

interface VersionManager {
    /**
     * Compute a final version to use for publication, based on the plugin versionPrefix
     */
    fun getVersion(versionPrefix: String): String
}

internal const val TASK_GROUP_NAME = "kmmbridge"
private const val EXTENSION_NAME = "kmmbridge"

@Suppress("unused")
class KMMBridgePlugin : Plugin<Project> {

    override fun apply(project: Project): Unit = with(project) {
        val extension = extensions.create<KmmBridgeExtension>(EXTENSION_NAME)
        extension.dependencyManagers.convention(emptyList())
        extension.buildType.convention(NativeBuildType.RELEASE)
        extension.artifactManager.convention(FaktoryServerArtifactManager())
        extension.versionManager.convention(TimestampVersionManager())

        // Don't call `kotlin.cocoapods` because that will throw if we don't have cocoapods plugin applied
        val fallbackVersion =
            (kotlin as ExtensionAware).extensions.findByType<CocoapodsExtension>()?.version ?: version.toString()
        extension.versionPrefix.convention(fallbackVersion)

        afterEvaluate {
            if (extension.xcFrameworkPath.orNull == null) {
                configureXcFramework()
            }

            configureDeploy()
        }
    }

    // Collect all declared frameworks in project and combine into xcframework
    private fun Project.configureXcFramework() {
        val extension = extensions.getByType<KmmBridgeExtension>()
        var xcFrameworkConfig: XCFrameworkConfig? = null

        kotlin.targets
            .withType<KotlinNativeTarget>()
            .filter { it.konanTarget.family.isAppleFamily }
            .flatMap { it.binaries.filterIsInstance<Framework>() }
            .forEach { framework ->
                val theName = framework.baseName
                val currentName = extension.frameworkName.orNull
                if (currentName == null) {
                    extension.frameworkName.set(theName)
                } else {
                    if (currentName != theName) {
                        throw IllegalStateException("Only one framework name currently allowed. Found $currentName and $theName")
                    }
                }
                if (xcFrameworkConfig == null) {
                    xcFrameworkConfig = XCFramework(theName)
                }
                xcFrameworkConfig!!.add(framework)
            }
    }

    private fun Project.findXCFrameworkAssembleTask(): Task {
        val extension = extensions.getByType<KmmBridgeExtension>()
        val name = extension.frameworkName.get()
        val buildTypeString = extension.buildType.get().getName().capitalize()
        val taskWithoutName = "assemble${buildTypeString}XCFramework"
        val taskWithName = "assemble${name.capitalize()}${buildTypeString}XCFramework"
        return try {
            tasks.findByName(taskWithName) ?: tasks.findByPath(taskWithoutName)!!
        } catch (e: Exception) {
            throw UnknownTaskException("Cannot find XCFramework assemble task. Tried ${taskWithName} and ${taskWithoutName}.")
        }
    }

    private fun Project.configureDeploy() {
        val extension = extensions.getByType<KmmBridgeExtension>()

        val jbXcFrameworkBuild = extension.xcFrameworkPath.orNull == null

        val xcFrameworkPath = extension.xcFrameworkPath
            .getOrElse("$buildDir/XCFrameworks/${extension.buildType.get().getName()}")
        val artifactManager = extension.artifactManager.get()
        val zipFile = zipFilePath()

        val zipTask = task<Zip>("zipXCFramework") {
            group = TASK_GROUP_NAME
            if (jbXcFrameworkBuild) {
                dependsOn(findXCFrameworkAssembleTask())
            }

            from(xcFrameworkPath)
            destinationDirectory.set(zipFile.parentFile)
            archiveFileName.set(zipFile.name)
        }

        val dependencyManagers = extension.dependencyManagers.get()
        val uploadTask = task("uploadXCFramework") {
            group = TASK_GROUP_NAME

            dependsOn(zipTask)
            inputs.file(zipFile)
            outputs.file(urlFile)

            doLast {
                val fileName = artifactName(project)
                val deployUrl = artifactManager.deployArtifact(project, zipFile, fileName)
                urlFile.writeText(deployUrl)
            }
        }

        val publishRemoteTask = task("publishRemoteFramework") {
            group = TASK_GROUP_NAME
            dependsOn(uploadTask)
        }

        for (dependencyManager in dependencyManagers) {
            dependencyManager.doExtraConfiguration(this, uploadTask, publishRemoteTask)
        }
    }

    private fun artifactName(project: Project): String {
        val remoteFileId = UUID.randomUUID().toString()
        val frameworkName = project.kmmBridgeExtension.frameworkName.get()
        val buildTypeString = project.kmmBridgeExtension.buildType.get().getName()
        return "$frameworkName-$buildTypeString-$remoteFileId.xcframework.${project.kmmBridgeExtension.version}.zip"
    }
}
