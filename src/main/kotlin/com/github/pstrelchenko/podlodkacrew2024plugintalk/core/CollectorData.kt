package com.github.pstrelchenko.podlodkacrew2024plugintalk.core

import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.psi.KtCallExpression

data class CollectorData(
    val callExpression: KtCallExpression,
    val functionDescriptor: FunctionDescriptor,
    val testTag: String,
    val lastAddedTestTagPart: String,
)
