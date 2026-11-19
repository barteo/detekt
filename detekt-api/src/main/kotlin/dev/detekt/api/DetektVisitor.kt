package dev.detekt.api

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPlainText
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.impl.source.tree.LeafElement
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * Basic visitor which is used inside detekt.
 * Guarantees a better looking name as the extended base class :).
 *
 * A leaf of the tree, a token, white space or a comment, is visited only by
 * [visitElement], [visitWhiteSpace], [visitComment] and [visitPlainText]. A
 * visitor that overrides none of them sees nothing of the leaves, so its walk
 * steps over them: they are most of a file's elements.
 */
open class DetektVisitor : KtTreeVisitorVoid() {
    private val visitsLeaves: Boolean = visitsLeaves(javaClass)

    override fun visitElement(element: PsiElement) {
        val node = element.node
        if (visitsLeaves || node == null) {
            super.visitElement(element)
            return
        }
        var child = node.firstChildNode
        while (child != null) {
            if (child !is LeafElement) child.psi?.accept(this)
            child = child.treeNext
        }
    }

    private companion object {
        private val leafVisits = listOf(
            "visitElement" to PsiElement::class.java,
            "visitWhiteSpace" to PsiWhiteSpace::class.java,
            "visitComment" to PsiComment::class.java,
            "visitPlainText" to PsiPlainText::class.java,
        )

        private val cache = object : ClassValue<Boolean>() {
            override fun computeValue(type: Class<*>): Boolean {
                var c: Class<*>? = type
                while (c != null && c != DetektVisitor::class.java) {
                    for ((name, param) in leafVisits) {
                        if (c.declaredMethods.any { it.name == name && it.parameterCount == 1 && it.parameterTypes[0] == param }) {
                            return true
                        }
                    }
                    c = c.superclass
                }
                return System.getProperty("detekt.visitLeaves") == "true"
            }
        }

        fun visitsLeaves(type: Class<*>): Boolean = cache.get(type)
    }
}
