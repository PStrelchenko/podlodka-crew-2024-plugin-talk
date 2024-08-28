package com.github.pstrelchenko.podlodkacrew2024plugintalk.actions

import com.github.pstrelchenko.podlodkacrew2024plugintalk.core.ComposableFunctionsCollector
import com.intellij.codeInsight.CodeInsightActionHandler
import com.intellij.codeInsight.actions.CodeInsightAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilBase
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Algorithm:
 *
 * 1. Context
 * 2. Data
 * 3. Generate
 */
class CreatePageObjectAction : CodeInsightAction(), CodeInsightActionHandler {

    override fun getHandler(): CodeInsightActionHandler {
        return this
    }

    override fun isValidForFile(project: Project, editor: Editor, file: PsiFile): Boolean {
        if (file !is KtFile) {
            return false
        }

        val psiElement = PsiUtilBase.getElementAtCaret(editor) ?: return false
        val composableFunction = PsiTreeUtil.getParentOfType(psiElement, KtNamedFunction::class.java) ?: return false

        return composableFunction.annotationEntries.map { it.text }.any { it.contains("Composable") }
    }

    override fun invoke(project: Project, editor: Editor, psiFile: PsiFile) {
        val psiElement = PsiUtilBase.getElementAtCaret(editor) ?: return
        val composableFunction = PsiTreeUtil.getParentOfType(psiElement, KtNamedFunction::class.java) ?: return

        val collectedItems = ComposableFunctionsCollector().collectComposableFunctions(composableFunction)

        val propertiesDeclarations = collectedItems.joinToString(separator = "\n") {
            "private val ${it.lastAddedTestTagPart} = com.kakao.compose.nodes.KNode { hasTestTag(${it.testTag})"
        }

        val pageObjectText = """
            class MyPageObject : ru.hh.shared.core.tests.PageObject<MyPageObject>() {
            
               ${propertiesDeclarations}
    
            }
        """.trimIndent()

        println("Text page object: ${pageObjectText}")
    }

}
