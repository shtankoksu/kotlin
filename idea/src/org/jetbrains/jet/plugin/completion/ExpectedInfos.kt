package org.jetbrains.jet.plugin.completion

import org.jetbrains.jet.lang.psi.JetExpression
import org.jetbrains.jet.lang.resolve.BindingContext
import org.jetbrains.jet.lang.psi.JetValueArgument
import org.jetbrains.jet.lang.psi.JetValueArgumentList
import org.jetbrains.jet.lang.psi.JetCallExpression
import org.jetbrains.jet.lang.resolve.scopes.receivers.ReceiverValue
import com.intellij.lang.ASTNode
import org.jetbrains.jet.lang.psi.JetQualifiedExpression
import org.jetbrains.jet.lang.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.jet.lang.resolve.calls.util.CallMaker
import org.jetbrains.jet.lang.resolve.calls.context.BasicCallResolutionContext
import org.jetbrains.jet.lang.resolve.DelegatingBindingTrace
import org.jetbrains.jet.lang.types.TypeUtils
import org.jetbrains.jet.lang.resolve.calls.autocasts.DataFlowInfo
import org.jetbrains.jet.lang.resolve.calls.context.ContextDependency
import org.jetbrains.jet.lang.resolve.calls.context.CheckValueArgumentsMode
import org.jetbrains.jet.lang.resolve.calls.CompositeExtension
import org.jetbrains.jet.di.InjectorForMacros
import org.jetbrains.jet.lang.resolve.calls.results.OverloadResolutionResults
import org.jetbrains.jet.lang.descriptors.FunctionDescriptor
import java.util.HashSet
import org.jetbrains.jet.lang.resolve.calls.model.ResolvedCall
import org.jetbrains.jet.lang.descriptors.ModuleDescriptor
import org.jetbrains.jet.lang.types.JetType

enum class Tail {
    COMMA
    PARENTHESIS
}

data class ExpectedInfo(val `type`: JetType, val tail: Tail?)

class ExpectedInfos(val bindingContext: BindingContext, val moduleDescriptor: ModuleDescriptor) {
    public fun calculate(expressionWithType: JetExpression): Collection<ExpectedInfo>? {
        val expectedInfos1 = calculateForArgument(expressionWithType)
        if (expectedInfos1 != null) return expectedInfos1

        val expectedInfos2 = calculateForFunctionLiteralArgument(expressionWithType)
        if (expectedInfos2 != null) return expectedInfos2

        val expectedType = bindingContext[BindingContext.EXPECTED_EXPRESSION_TYPE, expressionWithType] ?: return null
        return listOf(ExpectedInfo(expectedType, null))
    }

    private fun calculateForArgument(expressionWithType: JetExpression): Collection<ExpectedInfo>? {
        val argument = expressionWithType.getParent() as? JetValueArgument ?: return null
        if (argument.isNamed()) return null //TODO - support named arguments (also do not forget to check for presence of named arguments before)
        val argumentList = argument.getParent() as JetValueArgumentList
        val argumentIndex = argumentList.getArguments().indexOf(argument)
        val callExpression = argumentList.getParent() as? JetCallExpression ?: return null
        return calculateForArgument(callExpression, argumentIndex, false)
    }

    private fun calculateForFunctionLiteralArgument(expressionWithType: JetExpression): Collection<ExpectedInfo>? {
        val callExpression = expressionWithType.getParent() as? JetCallExpression
        if (callExpression != null) {
            val arguments = callExpression.getFunctionLiteralArguments()
            if (arguments.firstOrNull() == expressionWithType) {
                return calculateForArgument(callExpression, callExpression.getValueArguments().size, true)
            }
        }
        return null
    }

    private fun calculateForArgument(callExpression: JetCallExpression, argumentIndex: Int, isFunctionLiteralArgument: Boolean): Collection<ExpectedInfo>? {
        val calleeExpression = callExpression.getCalleeExpression()

        val parent = callExpression.getParent()
        val receiver: ReceiverValue
        val callOperationNode: ASTNode?
        if (parent is JetQualifiedExpression && callExpression == parent.getSelectorExpression()) {
            val receiverExpression = parent.getReceiverExpression()
            val expressionType = bindingContext[BindingContext.EXPRESSION_TYPE, receiverExpression] ?: return null
            receiver = ExpressionReceiver(receiverExpression, expressionType)
            callOperationNode = parent.getOperationTokenNode()
        }
        else {
            receiver = ReceiverValue.NO_RECEIVER
            callOperationNode = null
        }
        val call = CallMaker.makeCall(receiver, callOperationNode, callExpression)

        val resolutionScope = bindingContext[BindingContext.RESOLUTION_SCOPE, calleeExpression] ?: return null //TODO: discuss it

        val callResolutionContext = BasicCallResolutionContext.create(
                DelegatingBindingTrace(bindingContext, "Temporary trace for completion"),
                resolutionScope,
                call,
                bindingContext[BindingContext.EXPECTED_EXPRESSION_TYPE, callExpression] ?: TypeUtils.NO_EXPECTED_TYPE,
                bindingContext[BindingContext.EXPRESSION_DATA_FLOW_INFO, callExpression] ?: DataFlowInfo.EMPTY,
                ContextDependency.INDEPENDENT,
                CheckValueArgumentsMode.ENABLED,
                CompositeExtension(listOf()),
                false).replaceCollectAllCandidates(true)
        val callResolver = InjectorForMacros(callExpression.getProject(), moduleDescriptor).getCallResolver()!!
        val results: OverloadResolutionResults<FunctionDescriptor> = callResolver.resolveFunctionCall(callResolutionContext)

        val expectedInfos = HashSet<ExpectedInfo>()
        for (candidate: ResolvedCall<FunctionDescriptor> in results.getAllCandidates()!!) {
            val parameters = candidate.getResultingDescriptor().getValueParameters()
            if (isFunctionLiteralArgument) {
                if (argumentIndex != parameters.size - 1) continue
            }
            else {
                if (parameters.size <= argumentIndex) continue
            }
            val parameterDescriptor = parameters[argumentIndex]
            val tail = if (isFunctionLiteralArgument) null else if (argumentIndex == parameters.size - 1) Tail.PARENTHESIS else Tail.COMMA
            expectedInfos.add(ExpectedInfo(parameterDescriptor.getType(), tail))
        }
        return expectedInfos
    }
}