package com.github.pstrelchenko.podlodkacrew2024plugintalk.core

import com.intellij.psi.util.childrenOfType
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.idea.base.psi.kotlinFqName
import org.jetbrains.kotlin.idea.caches.resolve.resolveToCall
import org.jetbrains.kotlin.idea.completion.reference
import org.jetbrains.kotlin.idea.structuralsearch.visitor.KotlinRecursiveElementVisitor
import org.jetbrains.kotlin.js.translate.callTranslator.getReturnType
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.parents
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.resolve.source.getPsi

class ComposableFunctionsCollector {

    private companion object {
        val ComposableAnnotationFqName = FqName("androidx.compose.runtime.Composable")
    }

    fun collectComposableFunctions(
        ktNamedFunction: KtNamedFunction,
        testTagPrefix: String = "",
    ): List<PropertyData> {
        val collectedKtCallExpressions = mutableListOf<CollectorData>()
        collectComposableFunctionsWithModifiers(
            ktNamedFunction,
            collectedKtCallExpressions,
            prefix = testTagPrefix
        )

        return collectedKtCallExpressions
            .mapNotNull { data ->
                val modifierValueArgument = data.callExpression.valueArguments.firstOrNull { valueArg ->
                    valueArg.getArgumentExpression()?.resolveToCall()?.getReturnType()?.toString() == "Modifier"
                }
                modifierValueArgument?.let {
                    ExpressionWithModifierArgumentData(data, it)
                }
            }.filter { expressionWithModifierArgumentData ->
                expressionWithModifierArgumentData.modifierValueArgument.text.contains(".testTag")
            }
            .mapNotNull { expressionWithModifierArgumentData ->

                // Каждый вызов `Modifier.<something>` - это KtDotQualifiedExpression->CallExpression
                val callExpression = expressionWithModifierArgumentData.modifierValueArgument
                    .childrenOfType<KtDotQualifiedExpression>()[0]
                    .childrenOfType<KtCallExpression>()
                    .firstOrNull { it.text.startsWith("testTag") }

                var testTagValue = ""

                // .testTag("something")
                val stringTemplate = callExpression?.valueArguments
                    ?.getOrNull(0)
                    ?.childrenOfType<KtStringTemplateExpression>()
                    ?.getOrNull(0)
                if (stringTemplate != null) {
                    testTagValue = stringTemplate.text
                }

                // .testTag(TestTagsHolder.value)
                val dotExpression = callExpression?.valueArguments
                    ?.getOrNull(0)
                    ?.childrenOfType<KtDotQualifiedExpression>()
                    ?.getOrNull(0)
                if (dotExpression != null) {
                    val text = dotExpression.text.removePrefix(dotExpression.receiverExpression.text.orEmpty())
                    val objectName = dotExpression.receiverExpression.reference()?.resolve()?.kotlinFqName?.asString()

                    testTagValue = "${objectName}$text"
                }

                PropertyData(
                    lastAddedTestTagPart = expressionWithModifierArgumentData.data.lastAddedTestTagPart,
                    testTag = testTagValue,
                    functionDescriptorName = expressionWithModifierArgumentData.data.functionDescriptor.name.asString(),
                ).takeIf { testTagValue.isNotBlank() }
            }

    }

    /**
     * Рекурсивно собирает все вызовы Composable-функций, у которых есть Modifier-ы,
     * начиная от переданной [currentNamedFunction].
     *
     * @param currentNamedFunction Стартовая функция для сбора вызовов.
     * @param collectedKtCallExpressions Агрегатор для сбора списка вызовов.
     * @param prefix Стартовый префикс тестовых тегов.
     */
    private fun collectComposableFunctionsWithModifiers(
        currentNamedFunction: KtNamedFunction,
        collectedKtCallExpressions: MutableList<CollectorData>,
        prefix: String,
    ) {
        currentNamedFunction.acceptChildren(
            object : KotlinRecursiveElementVisitor() {

                override fun visitNamedFunction(function: KtNamedFunction) {
                    super.visitNamedFunction(function)
                    function.acceptChildren(this)
                }

                override fun visitCallExpression(expression: KtCallExpression) {
                    super.visitCallExpression(expression)

                    val resolvedFunctionDescriptor = expression.resolveToCall(bodyResolveMode = BodyResolveMode.FULL)
                        ?.resultingDescriptor as? FunctionDescriptor
                    val namedFunction = resolvedFunctionDescriptor?.source?.getPsi() as? KtNamedFunction

                    // Собираем все нужные нам для модификации вызовы Composable-функций.
                    if (
                        resolvedFunctionDescriptor != null &&
                        resolvedFunctionDescriptor.hasComposableAnnotation() &&
                        resolvedFunctionDescriptor.hasModifierParameter()
                    ) {

                        // Для composable-функций, которые вызываются внутри слота,
                        // хочется украсить тестовый тег именем слота.
                        val lambda = expression.parents.firstOrNull {
                            it is KtLambdaExpression && it.parent is KtValueArgument
                        } as? KtLambdaExpression
                        val slotName = (lambda?.parent as? KtValueArgument)?.getArgumentName()?.text

                        val lastAddedTestTagPart = slotName
                            ?: resolvedFunctionDescriptor.name.asString().replaceFirstChar { it.lowercaseChar() }
                        val newTestTag = "${prefix}:${lastAddedTestTagPart}"

                        collectedKtCallExpressions.add(
                            CollectorData(
                                callExpression = expression,
                                functionDescriptor = resolvedFunctionDescriptor,
                                testTag = newTestTag,
                                lastAddedTestTagPart = lastAddedTestTagPart,
                            )
                        )

                    }

                    // Запускаем рекурсивный поиск
                    if (
                        resolvedFunctionDescriptor != null &&
                        resolvedFunctionDescriptor.shouldContinueRecursion() &&
                        namedFunction != null
                    ) {
                        // Если мы нашли Composable-функцию, объявленную в нашем коде, у которой есть Modifier ->
                        // спускаемся вглубь
                        collectComposableFunctionsWithModifiers(
                            currentNamedFunction = namedFunction,
                            collectedKtCallExpressions = collectedKtCallExpressions,
                            prefix = "${prefix}:${
                                resolvedFunctionDescriptor.name.asString().replaceFirstChar { it.lowercaseChar() }
                            }"
                        )
                    } else {
                        // Спускаться вглубь нужно и для дочерних узлов KtCallExpression
                        expression.acceptChildren(this)
                    }
                }

            }
        )
    }

    private fun FunctionDescriptor.hasComposableAnnotation(): Boolean {
        return annotations.hasAnnotation(ComposableAnnotationFqName)
    }

    private fun FunctionDescriptor.shouldContinueRecursion(): Boolean {
        return when {
            // Если встретили функцию Box / Column / LazyColumn / LaunchedEffect / etc -> вглубь уходить не нужно.
            isComposeStandardLibrariesFunction() -> false

            // Если встретили функцию "нашей" дизайн-системы -> вглубь уходить не нужно.
            isOurDesignSystemFunction() -> false

            // Если у функции нет параметра типа Modifier -> вглубь уходить не нужно.
            hasModifierParameter().not() -> false

            // В остальных случаях пытаемся спуститься поглубже.
            else -> true
        }
    }

    private fun FunctionDescriptor.isComposeStandardLibrariesFunction(): Boolean {
        val fqName = fqNameSafe.asString()
        return when {
            fqName.startsWith("androidx.compose.foundation") -> true
            fqName.startsWith("androidx.compose.material") -> true
            fqName.startsWith("androidx.compose.material3") -> true
            fqName.startsWith("androidx.compose.runtime") -> true
            else -> false
        }
    }

    private fun FunctionDescriptor.isOurDesignSystemFunction(): Boolean {
        val fqName = fqNameSafe.asString()
        return fqName.startsWith("com.google.samples.apps.nowinandroid.core.designsystem.component")
    }

    private fun FunctionDescriptor.hasModifierParameter(): Boolean {
        return valueParameters.any { parameterDescriptor ->
            parameterDescriptor.type.toString().contains("Modifier")
        }
    }


}