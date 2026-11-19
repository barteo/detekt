package dev.detekt.cli.runners

import dev.detekt.cli.CliArgs
import dev.detekt.cli.createSpec
import dev.detekt.tooling.api.AnalysisResult
import dev.detekt.tooling.api.Detekt
import dev.detekt.tooling.api.DetektProvider
import dev.detekt.tooling.api.UnexpectedError
import dev.detekt.tooling.api.spec.ProcessingSpec
import dev.detekt.tooling.api.AnalysisMode
import dev.detekt.tooling.internal.NotApiButProbablyUsedByUsers
import io.github.barteo.kc.aa.KcModuleConfig
import io.github.barteo.kc.aa.KcStandalone
import java.io.File
import java.util.concurrent.Callable

class Runner(private val spec: ProcessingSpec) :
    Executable,
    Callable<AnalysisResult> {

    @NotApiButProbablyUsedByUsers
    constructor(
        arguments: CliArgs,
        outputPrinter: Appendable,
        errorPrinter: Appendable,
    ) : this(arguments.createSpec(outputPrinter, errorPrinter))

    override fun execute() {
        val result = call()
        when (val error = result.error) {
            is UnexpectedError -> throw error.cause
            else -> error?.let { throw it }
        }
    }

    override fun call(): AnalysisResult {
        if (spec.projectSpec.analysisMode == AnalysisMode.full) prestartKc(spec)
        val provider = DetektProvider.load()
        val detekt: Detekt = provider.get(spec)
        return detekt.run()
    }
}

/**
 * Starts kc's check of the sources (kotlin-compiler-go's jvm/kc-aa) now,
 * before detekt reads its configuration and builds its compiler
 * configuration: it reads only the sources' paths, the class path and the
 * options. The session detekt opens later takes it over if it gets the
 * same module (KcStandalone.prestart), and checks again otherwise.
 */
private fun prestartKc(spec: ProcessingSpec) {
    val args = spec.compilerSpec.freeCompilerArgs
    val optIns = buildList {
        args.forEachIndexed { i, a ->
            when {
                a.startsWith("-opt-in=") -> add(a.substringAfter('='))
                a == "-opt-in" -> args.getOrNull(i + 1)?.let(::add)
            }
        }
    }
    val friends = args.filter { it.startsWith("-Xfriend-paths=") }
        .flatMap { it.substringAfter('=').split(',') }
        .filter { it.isNotEmpty() }
        .map(::File)
    KcStandalone.prestart(
        KcModuleConfig(
            moduleName = "source",
            sourceRoots = spec.projectSpec.inputPaths.map { it.toFile() },
            libraries = spec.compilerSpec.classpath.map { it.toFile() },
            friends = friends,
            jdkHome = spec.compilerSpec.jdkHome?.toFile(),
            optIns = optIns,
        ),
    )
}
