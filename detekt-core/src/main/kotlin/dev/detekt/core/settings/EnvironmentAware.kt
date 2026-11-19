package dev.detekt.core.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.pom.PomModel
import com.intellij.pom.tree.TreeAspect
import dev.detekt.core.parser.createCompilerConfiguration
import dev.detekt.parser.DetektPomModel
import dev.detekt.tooling.api.spec.CompilerSpec
import dev.detekt.tooling.api.spec.LoggingSpec
import dev.detekt.tooling.api.spec.ProjectSpec
import io.github.barteo.kc.aa.KcModuleConfig
import io.github.barteo.kc.aa.KcStandalone
import org.jetbrains.kotlin.cli.common.config.kotlinSourceRoots
import org.jetbrains.kotlin.cli.jvm.config.jvmClasspathRoots
import org.jetbrains.kotlin.config.AnalysisFlags
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.config.friendPaths
import org.jetbrains.kotlin.config.jdkHome
import org.jetbrains.kotlin.config.jvmTarget
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.psi.KtFile
import java.io.File
import java.io.OutputStream
import java.io.PrintStream

interface EnvironmentAware {
    val languageVersionSettings: LanguageVersionSettings
    val ktFiles: List<KtFile>
}

internal class EnvironmentFacade(projectSpec: ProjectSpec, compilerSpec: CompilerSpec, loggingSpec: LoggingSpec) :
    AutoCloseable,
    EnvironmentAware {

    private val printStream = if (loggingSpec.debug) loggingSpec.errorChannel.asPrintStream() else NullPrintStream

    private val configuration: CompilerConfiguration = createCompilerConfiguration(
        projectSpec.inputPaths.toList(),
        compilerSpec.classpath,
        compilerSpec.apiVersion,
        compilerSpec.languageVersion,
        compilerSpec.jvmTarget,
        compilerSpec.jdkHome,
        compilerSpec.freeCompilerArgs,
        printStream,
    )

    private val disposable: Disposable = Disposer.newDisposable()

    override val languageVersionSettings: LanguageVersionSettings
        get() = configuration.languageVersionSettings

    override val ktFiles: List<KtFile>
        get() = session.ktFiles

    // kc's Analysis API session (kotlin-compiler-go's jvm/kc-aa) stands in for
    // the standalone one: the source module, with the JDK, the friend paths
    // and the class path its dependencies.
    private val session: KcStandalone.Session

    init {
        configuration.putIfAbsent(CommonConfigurationKeys.MODULE_NAME, "<no module name provided>")

        val languageVersionSettings = configuration.languageVersionSettings
        session = KcStandalone.open(
            disposable,
            KcModuleConfig(
                moduleName = "source",
                sourceRoots = configuration.kotlinSourceRoots.map { File(it.path) },
                libraries = configuration.jvmClasspathRoots,
                friends = configuration.friendPaths.map(::File),
                jdkHome = configuration.jdkHome,
                jvmTarget = configuration.jvmTarget?.description,
                optIns = languageVersionSettings.getFlag(AnalysisFlags.optIn),
                languageVersionSettings = languageVersionSettings,
            ),
        )

        // Required for autocorrect support
        session.registerProjectService(TreeAspect::class.java)
        session.registerProjectService(PomModel::class.java, DetektPomModel(session.project))
    }

    override fun close() {
        Disposer.dispose(disposable)
    }
}

private object NullPrintStream : PrintStream(
    object : OutputStream() {
        override fun write(b: Int) {
            // no-op
        }
    }
)

private fun Appendable.asPrintStream(): PrintStream {
    val appendable = this
    return if (appendable is PrintStream) {
        appendable
    } else {
        PrintStream(
            object : OutputStream() {
                override fun write(b: Int) {
                    appendable.append(b.toChar())
                }
            }
        )
    }
}
