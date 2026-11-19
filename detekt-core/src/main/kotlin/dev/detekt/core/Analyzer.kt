package dev.detekt.core

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.FileProcessListener
import dev.detekt.api.Finding
import dev.detekt.api.Issue
import dev.detekt.api.Location
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import dev.detekt.api.RuleInstance
import dev.detekt.api.Severity
import dev.detekt.api.internal.whichDetekt
import dev.detekt.api.internal.whichJava
import dev.detekt.api.internal.whichOS
import dev.detekt.core.suppressors.buildSuppressors
import dev.detekt.core.suppressors.isSuppressedBy
import dev.detekt.core.util.analyzedPath
import dev.detekt.core.util.createPathFilters
import dev.detekt.core.util.shouldAnalyzeFile
import dev.detekt.utils.PathFilters
import java.util.IdentityHashMap
import dev.detekt.psi.absolutePath
import dev.detekt.tooling.api.AnalysisMode
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicIntegerArray

// kc's session (kotlin-compiler-go) analyzes the sources in the background
// while detekt sets up, and with -Dkc.overlap=true the rules that need no
// types run before it is done. It is off by default: on 4 cores, those rules
// and kc's analysis compete for the same CPUs, and it measured no faster on
// kotlinpoet and slower on kotlinx-coroutines-core. Without it each file's
// rules run in one task, as in stock detekt. -Dkc.schedule=false keeps the
// files' order and one task per file and phase.
private val overlap = System.getProperty("kc.overlap") == "true"
private val schedule = System.getProperty("kc.schedule") != "false"

internal class Analyzer(
    private val settings: ProcessingSettings,
    private val rules: List<RuleDescriptor>,
    private val processors: List<FileProcessListener>,
    private val analysisMode: AnalysisMode,
) {
    /**
     * The path filters of each rule's config and of its rule set's, made
     * once: compiling their globs for each rule and file cost more than
     * matching them.
     */
    private val pathFilters: Map<Config, PathFilters?> = IdentityHashMap<Config, PathFilters?>().also { m ->
        for (r in rules) {
            listOfNotNull(r.config, r.config.parent).forEach { c -> if (!m.containsKey(c)) m[c] = c.createPathFilters() }
        }
    }

    private fun filters(c: Config): PathFilters? = if (pathFilters.containsKey(c)) pathFilters[c] else c.createPathFilters()

    fun run(ktFiles: Collection<KtFile>): List<Issue> {
        val languageVersionSettings = settings.languageVersionSettings

        return if (settings.spec.executionSpec.parallelAnalysis) {
            runAsync(ktFiles, languageVersionSettings)
        } else {
            runSync(ktFiles, languageVersionSettings)
        }
    }

    private fun runSync(ktFiles: Collection<KtFile>, languageVersionSettings: LanguageVersionSettings): List<Issue> =
        ktFiles.flatMap { file ->
            processors.forEach { it.onProcess(file) }
            val issues = runCatching { analyze(file, languageVersionSettings) }.fold(
                onSuccess = { it },
                onFailure = { throwIllegalStateException(file, it) }
            )
            processors.forEach { it.onProcessComplete(file, issues) }
            issues
        }

    private fun runAsync(ktFiles: Collection<KtFile>, languageVersionSettings: LanguageVersionSettings): List<Issue> {
        if (!overlap || settings.spec.rulesSpec.autoCorrect) return runAsyncByFile(ktFiles, languageVersionSettings)
        val service = settings.taskPool
        val files = ktFiles.toList()
        // The rules that need no types run first, for every file, while the
        // Analysis API session may still be analyzing; then the ones that
        // need it (RequiresAnalysisApi), which wait for it. Autocorrect,
        // which changes the PSI, keeps a file's rules in one task
        // (runAsyncByFile).
        val needsTypes = rules.map { it.ruleProvider(Config.empty) is RequiresAnalysisApi }
        val phases = listOf(rules.indices.filter { !needsTypes[it] }, rules.indices.filter { needsTypes[it] })
            .filter { it.isNotEmpty() }
        // Largest files first, and a large file's rules split over several
        // tasks, so that no thread is left with a long file at the end.
        val threads = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val order = if (schedule) files.indices.sortedByDescending { files[it].textLength } else files.indices.toList()
        val share = (files.sumOf { it.textLength.toLong() } / (2L * threads)).coerceAtLeast(1)
        val parts = IntArray(files.size) { i ->
            if (schedule) ((files[i].textLength + share - 1) / share).toInt().coerceIn(1, threads) else 1
        }
        val paths = files.map { analyzedPath(it, settings.spec.projectSpec.basePath) }
        val results = Array(files.size) { ConcurrentLinkedQueue<RuleResult>() }
        val started = Array(files.size) { AtomicBoolean() }
        val left = AtomicIntegerArray(IntArray(files.size) { parts[it] * phases.size })
        val tasks = ArrayList<Task<Unit>>()
        for (phase in phases) {
            for (i in order) {
                val file = files[i]
                for (k in 0 until parts[i]) {
                    val group = phase.filterIndexed { j, _ -> j % parts[i] == k }
                    tasks += service.task {
                        try {
                            if (started[i].compareAndSet(false, true)) processors.forEach { it.onProcess(file) }
                            for (d in group) analyze(file, paths[i], d, languageVersionSettings)?.let(results[i]::add)
                            if (left.decrementAndGet(i) == 0) {
                                processors.forEach { it.onProcessComplete(file, issuesOf(results[i])) }
                            }
                        } catch (e: Throwable) {
                            throwIllegalStateException(file, e)
                        }
                    }
                }
            }
        }
        awaitAll(tasks)
        return results.flatMap(::issuesOf)
    }

    /** The issues of a file's rules, in the order a serial run finds them: correctable rules first. */
    private fun issuesOf(results: Collection<RuleResult>): List<Issue> =
        results.sortedWith(compareBy<RuleResult>({ if (it.autoCorrect) 0 else 1 }, { it.index })).flatMap { it.issues }

    private class RuleResult(val index: Int, val autoCorrect: Boolean, val issues: List<Issue>)

    /** The issues rule [index] finds in [file], or null if it doesn't run on it. */
    private fun analyze(file: KtFile, path: Path, index: Int, languageVersionSettings: LanguageVersionSettings): RuleResult? {
        val ruleDescriptor = rules[index]
        val basePath = settings.spec.projectSpec.basePath
        if (ruleDescriptor.config.parent?.let { shouldAnalyzeFile(filters(it), path) } == false) return null
        if (!shouldAnalyzeFile(filters(ruleDescriptor.config), path)) return null
        val ruleInstance = ruleDescriptor.ruleInstance
        val rule = ruleDescriptor.ruleProvider(ruleDescriptor.config)
        if (file.isSuppressedBy(ruleInstance.id, rule.aliases, ruleInstance.ruleSetId)) return null
        val issues = rule.visitFile(file, languageVersionSettings)
            .filterNot { it.entity.ktElement.isSuppressedBy(ruleInstance.id, rule.aliases, ruleInstance.ruleSetId) }
            .filterSuppressedFindings(rule, analysisMode)
            .map { it.toIssue(ruleInstance, ruleInstance.severity, basePath) }
        return RuleResult(index, rule.autoCorrect, issues)
    }

    private fun runAsyncByFile(ktFiles: Collection<KtFile>, languageVersionSettings: LanguageVersionSettings): List<Issue> {
        val service = settings.taskPool
        val tasks: TaskList<List<Issue>?> = ktFiles.map { file ->
            service.task {
                processors.forEach { it.onProcess(file) }
                val issues = analyze(file, languageVersionSettings)
                processors.forEach { it.onProcessComplete(file, issues) }
                issues
            }.recover { throwIllegalStateException(file, it) }
        }
        return awaitAll(tasks).filterNotNull().flatten()
    }

    private fun analyze(file: KtFile, languageVersionSettings: LanguageVersionSettings): List<Issue> {
        val path = analyzedPath(file, settings.spec.projectSpec.basePath)
        val (correctableRules, otherRules) = rules.asSequence()
            .filter { ruleDescriptor ->
                ruleDescriptor.config.parent?.let { shouldAnalyzeFile(filters(it), path) } != false
            }
            .filter { ruleDescriptor ->
                shouldAnalyzeFile(filters(ruleDescriptor.config), path)
            }
            .map { ruleDescriptor ->
                ruleDescriptor.ruleInstance to ruleDescriptor.ruleProvider(ruleDescriptor.config)
            }
            .filterNot { (ruleInstance, rule) ->
                file.isSuppressedBy(ruleInstance.id, rule.aliases, ruleInstance.ruleSetId)
            }
            .partition { (_, rule) -> rule.autoCorrect }

        return (correctableRules + otherRules).flatMap { (ruleInstance, rule) ->
            rule.visitFile(file, languageVersionSettings)
                .filterNot {
                    it.entity.ktElement.isSuppressedBy(ruleInstance.id, rule.aliases, ruleInstance.ruleSetId)
                }
                .filterSuppressedFindings(rule, analysisMode)
                .map { it.toIssue(ruleInstance, ruleInstance.severity, settings.spec.projectSpec.basePath) }
        }
    }
}

private fun List<Finding>.filterSuppressedFindings(rule: Rule, analysisMode: AnalysisMode): List<Finding> {
    val suppressors = buildSuppressors(rule, analysisMode)
    return if (suppressors.isNotEmpty()) {
        filter { finding -> !suppressors.any { suppressor -> suppressor.shouldSuppress(finding) } }
    } else {
        this
    }
}

private fun throwIllegalStateException(file: KtFile, error: Throwable): Nothing {
    val message = """
        Analyzing ${file.absolutePath()} led to an exception.
        Location: ${error.stackTrace.firstOrNull()?.toString()}
        The original exception message was: ${error.localizedMessage}
        Running detekt '${whichDetekt()}' on Java '${whichJava()}' on OS '${whichOS()}'
        If the exception message does not help, please feel free to create an issue on our GitHub page.
    """.trimIndent()
    throw IllegalStateException(message, error)
}

private fun Finding.toIssue(rule: RuleInstance, severity: Severity, basePath: Path): Issue =
    Issue(
        ruleInstance = rule,
        entity = entity.toIssue(basePath),
        references = references.map { it.toIssue(basePath) },
        message = message,
        severity = severity,
        suppressReasons = suppressReasons,
    )

private fun Entity.toIssue(basePath: Path): Issue.Entity = Issue.Entity(signature, location.toIssue(basePath))

private fun Location.toIssue(basePath: Path): Issue.Location =
    Issue.Location(source, endSource, text, basePath.relativize(path))

private val Rule.aliases: Set<String> get() = config.valueOrDefault(Config.ALIASES_KEY, emptyList<String>()).toSet()
