/*
 * Copyright 2010-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.plugin.codeInsight.surroundWith.expression

import com.intellij.codeInsight.CodeInsightBundle
import com.intellij.debugger.DebuggerBundle
import com.intellij.debugger.DebuggerInvocationUtil
import com.intellij.debugger.DebuggerManagerEx
import com.intellij.debugger.impl.DebuggerContextImpl
import com.intellij.openapi.application.Result
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.util.ProgressWindowWithNotification
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import org.jetbrains.jet.lang.psi.JetExpression
import org.jetbrains.jet.lang.psi.JetCodeFragment
import org.jetbrains.jet.plugin.debugger.evaluate.KotlinRuntimeTypeEvaluator
import org.jetbrains.jet.lang.psi.JetPsiFactory
import org.jetbrains.jet.lang.psi.JetBinaryExpressionWithTypeRHS
import org.jetbrains.jet.plugin.codeInsight.ShortenReferences
import org.jetbrains.jet.lang.psi.JetParenthesizedExpression
import org.jetbrains.jet.lang.types.JetType
import org.jetbrains.jet.lang.resolve.DescriptorUtils
import org.jetbrains.jet.plugin.caches.resolve.analyzeFully
import org.jetbrains.jet.lang.resolve.BindingContext
import org.jetbrains.jet.lang.types.TypeUtils
import org.jetbrains.jet.lang.types.checker.JetTypeChecker
import org.jetbrains.jet.plugin.JetBundle

public class KotlinRuntimeTypeCastSurrounder: KotlinExpressionSurrounder() {

    override fun isApplicable(expression: JetExpression): Boolean {
        if (!expression.isPhysical()) return false
        val file = expression.getContainingFile()
        if (file !is JetCodeFragment) return false

        val context = file.analyzeFully()
        val type = context[BindingContext.EXPRESSION_TYPE, expression]
        if (type == null) return false

        return TypeUtils.canHaveSubtypes(JetTypeChecker.DEFAULT, type)
    }

    override fun surroundExpression(project: Project, editor: Editor, expression: JetExpression): TextRange? {
        val debuggerContext = DebuggerManagerEx.getInstanceEx(project).getContext()
        val debuggerSession = debuggerContext.getDebuggerSession()
        if (debuggerSession != null) {
            val progressWindow = ProgressWindowWithNotification(true, expression.getProject())
            val worker = SurroundWithCastWorker(editor, expression, debuggerContext, progressWindow)
            progressWindow.setTitle(DebuggerBundle.message("title.evaluating"))
            debuggerContext.getDebugProcess()?.getManagerThread()?.startProgress(worker, progressWindow)
        }
        return null
    }

    override fun getTemplateDescription(): String {
        return JetBundle.message("surround.with.runtime.type.cast.template")
    }

    private inner class SurroundWithCastWorker(
            private val myEditor: Editor,
            expression: JetExpression,
            context: DebuggerContextImpl,
            indicator: ProgressIndicator
    ): KotlinRuntimeTypeEvaluator(myEditor, expression, context, indicator) {

        override fun typeCalculationFinished(type: JetType?) {
            if (type == null) return

            hold()

            val project = myEditor.getProject()
            DebuggerInvocationUtil.invokeLater(project, Runnable {
                    object : WriteCommandAction<Any>(project, CodeInsightBundle.message("command.name.surround.with.runtime.cast")) {
                        override fun run(result: Result<Any>) {
                            try {
                                val factory = JetPsiFactory(myElement.getProject())

                                val fqName = DescriptorUtils.getFqName(type.getConstructor().getDeclarationDescriptor())
                                val parentCast = factory.createExpression("(expr as " + fqName.asString() + ")") as JetParenthesizedExpression
                                val cast = parentCast.getExpression() as JetBinaryExpressionWithTypeRHS
                                cast.getLeft().replace(myElement)
                                val expr = myElement.replace(parentCast) as JetExpression

                                ShortenReferences.process(expr)

                                val range = expr.getTextRange()
                                myEditor.getSelectionModel().setSelection(range.getStartOffset(), range.getEndOffset())
                                myEditor.getCaretModel().moveToOffset(range.getEndOffset())
                                myEditor.getScrollingModel().scrollToCaret(ScrollType.RELATIVE)
                            }
                            finally {
                                release()
                            }
                        }
                    }.execute()
            }, myProgressIndicator.getModalityState())
        }

    }
}
