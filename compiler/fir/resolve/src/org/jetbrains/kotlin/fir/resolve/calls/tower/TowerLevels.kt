/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.calls.tower

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirCallableMemberDeclaration
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.isInner
import org.jetbrains.kotlin.fir.declarations.isStatic
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.builder.buildResolvedQualifier
import org.jetbrains.kotlin.fir.resolve.BodyResolveComponents
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.calls.*
import org.jetbrains.kotlin.fir.resolve.firSymbolProvider
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.resultType
import org.jetbrains.kotlin.fir.resolve.typeForQualifier
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.scopes.ProcessorAction
import org.jetbrains.kotlin.fir.scopes.impl.FirAbstractImportingScope
import org.jetbrains.kotlin.fir.scopes.impl.FirExplicitSimpleImportingScope
import org.jetbrains.kotlin.fir.scopes.processClassifiersByName
import org.jetbrains.kotlin.fir.symbols.AbstractFirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeNullability
import org.jetbrains.kotlin.fir.types.withNullability
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.util.OperatorNameConventions

interface TowerScopeLevel {

    sealed class Token<out T : AbstractFirBasedSymbol<*>> {
        object Properties : Token<FirVariableSymbol<*>>()
        object Functions : Token<FirFunctionSymbol<*>>()
        object Constructors : Token<FirConstructorSymbol>()
        object Objects : Token<AbstractFirBasedSymbol<*>>()
    }

    fun <T : AbstractFirBasedSymbol<*>> processElementsByName(
        token: Token<T>,
        name: Name,
        processor: TowerScopeLevelProcessor<T>
    ): ProcessorAction

    interface TowerScopeLevelProcessor<T : AbstractFirBasedSymbol<*>> {
        fun consumeCandidate(
            symbol: T,
            dispatchReceiverValue: ReceiverValue?,
            implicitExtensionReceiverValue: ImplicitReceiverValue<*>?,
            builtInExtensionFunctionReceiverValue: ReceiverValue? = null
        )
    }
}

abstract class SessionBasedTowerLevel(val session: FirSession) : TowerScopeLevel {
    protected fun FirCallableSymbol<*>.hasConsistentExtensionReceiver(extensionReceiver: Receiver?): Boolean {
        return (extensionReceiver != null) == hasExtensionReceiver()
    }

    open fun replaceReceiverValue(receiverValue: ReceiverValue) = this
}

// This is more like "dispatch receiver-based tower level"
// Here we always have an explicit or implicit dispatch receiver, and can access members of its scope
// (which is separated from currently accessible scope, see below)
// So: dispatch receiver = given explicit or implicit receiver (always present)
// So: extension receiver = either none, if dispatch receiver = explicit receiver,
//     or given implicit or explicit receiver, otherwise
class MemberScopeTowerLevel(
    session: FirSession,
    private val bodyResolveComponents: BodyResolveComponents,
    val dispatchReceiver: ReceiverValue,
    private val extensionReceiver: ReceiverValue? = null,
    private val implicitExtensionInvokeMode: Boolean = false,
    private val scopeSession: ScopeSession
) : SessionBasedTowerLevel(session) {
    private fun <T : AbstractFirBasedSymbol<*>> processMembers(
        output: TowerScopeLevel.TowerScopeLevelProcessor<T>,
        forInnerConstructorDelegationCalls: Boolean = false,
        processScopeMembers: FirScope.(processor: (T) -> Unit) -> Unit
    ): ProcessorAction {
        var empty = true
        val scope = dispatchReceiver.scope(session, scopeSession) ?: return ProcessorAction.NONE
        scope.processScopeMembers { candidate ->
            empty = false
            if (candidate is FirCallableSymbol<*> &&
                (implicitExtensionInvokeMode || candidate.hasConsistentExtensionReceiver(extensionReceiver))
            ) {
                val fir = candidate.fir
                if (forInnerConstructorDelegationCalls && candidate !is FirConstructorSymbol) {
                    return@processScopeMembers
                } else if ((fir as? FirCallableMemberDeclaration<*>)?.isStatic == true || (fir as? FirConstructor)?.isInner == false) {
                    return@processScopeMembers
                }
                val dispatchReceiverValue = NotNullableReceiverValue(dispatchReceiver)

                output.consumeCandidate(
                    candidate, dispatchReceiverValue,
                    implicitExtensionReceiverValue = extensionReceiver as? ImplicitReceiverValue<*>
                )

                if (implicitExtensionInvokeMode) {
                    output.consumeCandidate(
                        candidate, dispatchReceiverValue,
                        implicitExtensionReceiverValue = null,
                        builtInExtensionFunctionReceiverValue = this.extensionReceiver
                    )
                }
            } else if (candidate is FirClassLikeSymbol<*>) {
                output.consumeCandidate(candidate, null, extensionReceiver as? ImplicitReceiverValue<*>)
            }
        }

        if (!forInnerConstructorDelegationCalls) {
            val withSynthetic = FirSyntheticPropertiesScope(session, scope)
            withSynthetic.processScopeMembers { symbol ->
                empty = false
                output.consumeCandidate(symbol, NotNullableReceiverValue(dispatchReceiver), extensionReceiver as? ImplicitReceiverValue<*>)
            }
        }
        return if (empty) ProcessorAction.NONE else ProcessorAction.NEXT
    }

    override fun <T : AbstractFirBasedSymbol<*>> processElementsByName(
        token: TowerScopeLevel.Token<T>,
        name: Name,
        processor: TowerScopeLevel.TowerScopeLevelProcessor<T>
    ): ProcessorAction {
        val isInvoke = name == OperatorNameConventions.INVOKE && token == TowerScopeLevel.Token.Functions
        if (implicitExtensionInvokeMode && !isInvoke) {
            return ProcessorAction.NEXT
        }
        return when (token) {
            is TowerScopeLevel.Token.Properties -> processMembers(processor) { consumer ->
                this.processPropertiesByName(name) {
                    // WARNING, DO NOT CAST FUNCTIONAL TYPE ITSELF
                    @Suppress("UNCHECKED_CAST")
                    consumer(it as T)
                }
            }
            TowerScopeLevel.Token.Functions -> processMembers(processor) { consumer ->
                this.processFunctionsAndConstructorsByName(
                    name, session, bodyResolveComponents,
                    includeInnerConstructors = true,
                    processor = {
                        // WARNING, DO NOT CAST FUNCTIONAL TYPE ITSELF
                        @Suppress("UNCHECKED_CAST")
                        consumer(it as T)
                    }
                )
            }
            TowerScopeLevel.Token.Objects -> processMembers(processor) { consumer ->
                this.processClassifiersByName(name) {
                    // WARNING, DO NOT CAST FUNCTIONAL TYPE ITSELF
                    @Suppress("UNCHECKED_CAST")
                    consumer(it as T)
                }
            }
            TowerScopeLevel.Token.Constructors -> processMembers(processor, forInnerConstructorDelegationCalls = true) { consumer ->
                this.processConstructorsByName(
                    name, session, bodyResolveComponents,
                    includeSyntheticConstructors = false,
                    includeInnerConstructors = true,
                    processor = {
                        @Suppress("UNCHECKED_CAST")
                        consumer(it as T)
                    }
                )
            }
        }
    }

    override fun replaceReceiverValue(receiverValue: ReceiverValue): SessionBasedTowerLevel {
        return MemberScopeTowerLevel(
            session, bodyResolveComponents, receiverValue, extensionReceiver, implicitExtensionInvokeMode, scopeSession
        )
    }
}

// This is more like "scope-based tower level"
// We can access here members of currently accessible scope which is not influenced by explicit receiver
// We can either have no explicit receiver at all, or it can be an extension receiver
// An explicit receiver never can be a dispatch receiver at this level
// So: dispatch receiver = strictly none (EXCEPTIONS: importing scopes with import from objects, synthetic field variable)
// So: extension receiver = either none or explicit
// (if explicit receiver exists, it always *should* be an extension receiver)
class ScopeTowerLevel(
    session: FirSession,
    private val bodyResolveComponents: BodyResolveComponents,
    val scope: FirScope,
    val extensionReceiver: ReceiverValue?,
    private val extensionsOnly: Boolean,
    private val includeInnerConstructors: Boolean
) : SessionBasedTowerLevel(session) {
    private fun FirCallableSymbol<*>.hasConsistentReceivers(extensionReceiver: Receiver?): Boolean =
        when {
            extensionsOnly && !hasExtensionReceiver() -> false
            !hasConsistentExtensionReceiver(extensionReceiver) -> false
            scope is FirAbstractImportingScope -> true
            else -> true
        }

    private fun dispatchReceiverValue(scope: FirScope, candidate: FirCallableSymbol<*>): ReceiverValue? {
        val holderId = candidate.callableId.classId
        if (holderId != null && scope is FirExplicitSimpleImportingScope) {
            val symbol = session.firSymbolProvider.getClassLikeSymbolByFqName(holderId)
            if (symbol is FirRegularClassSymbol &&
                symbol.fir.classKind.let { it == ClassKind.OBJECT || it == ClassKind.ENUM_ENTRY }
            ) {
                val resolvedQualifier = buildResolvedQualifier {
                    packageFqName = holderId.packageFqName
                    relativeClassFqName = holderId.relativeClassName
                    safe = false
                    this.symbol = symbol
                }.apply {
                    resultType = bodyResolveComponents.typeForQualifier(this)
                }
                return ExpressionReceiverValue(resolvedQualifier)
            }
        }
        return when {
            candidate !is FirBackingFieldSymbol -> null
            candidate.callableId.classId != null -> {
                bodyResolveComponents.implicitReceiverStack.lastDispatchReceiver { implicitReceiverValue ->
                    implicitReceiverValue.type.classId == holderId
                }
            }
            else -> {
                bodyResolveComponents.implicitReceiverStack.lastDispatchReceiver()
            }
        }
    }

    override fun <T : AbstractFirBasedSymbol<*>> processElementsByName(
        token: TowerScopeLevel.Token<T>,
        name: Name,
        processor: TowerScopeLevel.TowerScopeLevelProcessor<T>
    ): ProcessorAction {
        var empty = true
        @Suppress("UNCHECKED_CAST")
        when (token) {
            TowerScopeLevel.Token.Properties -> scope.processPropertiesByName(name) { candidate ->
                empty = false
                if (candidate.hasConsistentReceivers(extensionReceiver)) {
                    val dispatchReceiverValue = dispatchReceiverValue(scope, candidate)
                    processor.consumeCandidate(
                        candidate as T, dispatchReceiverValue,
                        implicitExtensionReceiverValue = extensionReceiver as? ImplicitReceiverValue<*>
                    )
                }
            }
            TowerScopeLevel.Token.Functions -> scope.processFunctionsAndConstructorsByName(
                name,
                session,
                bodyResolveComponents,
                includeInnerConstructors = includeInnerConstructors
            ) { candidate ->
                empty = false
                if (candidate.hasConsistentReceivers(extensionReceiver)) {
                    val dispatchReceiverValue = dispatchReceiverValue(scope, candidate)
                    processor.consumeCandidate(
                        candidate as T, dispatchReceiverValue,
                        implicitExtensionReceiverValue = extensionReceiver as? ImplicitReceiverValue<*>
                    )
                }
            }
            TowerScopeLevel.Token.Objects -> scope.processClassifiersByName(name) {
                empty = false
                processor.consumeCandidate(
                    it as T, dispatchReceiverValue = null,
                    implicitExtensionReceiverValue = null
                )
            }
            TowerScopeLevel.Token.Constructors -> {
                throw AssertionError("Should not be here")
            }
        }
        return if (empty) ProcessorAction.NONE else ProcessorAction.NEXT
    }
}

class ConstructorScopeTowerLevel(
    session: FirSession,
    val scope: FirScope
) : SessionBasedTowerLevel(session) {
    override fun <T : AbstractFirBasedSymbol<*>> processElementsByName(
        token: TowerScopeLevel.Token<T>,
        name: Name,
        processor: TowerScopeLevel.TowerScopeLevelProcessor<T>
    ): ProcessorAction {
        var empty = true
        when (token) {
            TowerScopeLevel.Token.Constructors -> scope.processDeclaredConstructors { candidate ->
                // NB: here we cannot resolve inner constructors, because they should have dispatch receiver
                if (!candidate.fir.isInner) {
                    empty = false
                    @Suppress("UNCHECKED_CAST")
                    processor.consumeCandidate(
                        candidate as T,
                        dispatchReceiverValue = null,
                        implicitExtensionReceiverValue = null
                    )
                }
            }
            else -> {
                throw AssertionError("Should not be here: token = $token")
            }
        }
        return if (empty) ProcessorAction.NONE else ProcessorAction.NEXT
    }
}

class NotNullableReceiverValue(val value: ReceiverValue) : ReceiverValue {
    override val type: ConeKotlinType
        get() = value.type.withNullability(ConeNullability.NOT_NULL)
    override val receiverExpression: FirExpression
        get() = value.receiverExpression
}

private fun FirCallableSymbol<*>.hasExtensionReceiver(): Boolean {
    return fir.receiverTypeRef != null
}
