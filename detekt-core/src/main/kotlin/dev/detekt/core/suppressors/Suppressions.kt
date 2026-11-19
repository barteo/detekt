package dev.detekt.core.suppressors

import dev.detekt.api.RuleName
import dev.detekt.api.RuleSetId
import dev.detekt.core.extractRuleName
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import kotlin.text.RegexOption.IGNORE_CASE

internal fun isForbiddenSuppressById(id: String) = extractRuleName(id) == FORBIDDEN_SUPPRESS_NAME
private val FORBIDDEN_SUPPRESS_NAME = RuleName("ForbiddenSuppress")

/**
 * Checks if this psi element is suppressed by @Suppress or @SuppressWarnings annotations.
 * If this element cannot have annotations, the first annotative parent is searched.
 */
fun KtElement.isSuppressedBy(id: String, aliases: Set<String>, ruleSetId: RuleSetId? = null): Boolean {
    if (isForbiddenSuppressById(id)) return false

    // Each annotated element's suppressions are read once: every rule asks
    // about every file and each of its findings' elements.
    var element: KtElement? = this
    while (element != null) {
        if (element is KtAnnotated) {
            for (s in suppressions(element)) {
                if (s == id || s in allSuppressions || s in aliases) return true
                if (ruleSetId != null && (s == ruleSetId.value || s == "$ruleSetId.$id" || s == "$ruleSetId:$id")) return true
            }
        }
        element = element.getStrictParentOfType<KtAnnotated>()
    }
    return false
}

private val allSuppressions = setOf("ALL", "all", "All")

/** The ids [element]'s @Suppress and @SuppressWarnings annotations name, without detekt's prefix and quotes. */
private fun suppressions(element: KtAnnotated): List<String> =
    suppressionCache.getOrPut(element) {
        element.annotationEntries
            .filter { it.typeReference?.text in suppressionAnnotations }
            .flatMap { it.valueArguments }
            .mapNotNull { it.getArgumentExpression()?.text }
            .map { it.replace(detektSuppressionPrefixRegex, "") }
            .map { it.replace(QUOTES, "") }
    }

private val suppressionCache = java.util.concurrent.ConcurrentHashMap<KtAnnotated, List<String>>()

private val detektSuppressionPrefixRegex = "detekt[.:]".toRegex(IGNORE_CASE)
private const val QUOTES = "\""
private val suppressionAnnotations = setOf("Suppress", "SuppressWarnings")
