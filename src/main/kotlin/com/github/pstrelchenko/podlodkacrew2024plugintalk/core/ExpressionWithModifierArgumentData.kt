package com.github.pstrelchenko.podlodkacrew2024plugintalk.core

import org.jetbrains.kotlin.psi.KtValueArgument

data class ExpressionWithModifierArgumentData(
    val data: CollectorData,
    val modifierValueArgument: KtValueArgument
)