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

package org.jetbrains.jet.plugin.debugger.evaluate

import org.jetbrains.jet.plugin.debugger.evaluate.*
import com.intellij.psi.PsiElement
import com.intellij.debugger.SourcePosition
import com.intellij.debugger.engine.evaluation.*
import com.intellij.debugger.engine.evaluation.expression.*
import org.jetbrains.jet.lang.resolve.AnalyzingUtils
import org.jetbrains.jet.codegen.state.GenerationState
import org.jetbrains.jet.codegen.ClassBuilderFactories
import java.util.Collections
import org.jetbrains.jet.codegen.KotlinCodegenFacade
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.jet.plugin.JetLanguage
import org.jetbrains.jet.lang.psi.JetFile
import com.intellij.psi.impl.PsiFileFactoryImpl
import com.intellij.psi.PsiFileFactory
import com.intellij.openapi.vfs.CharsetToolkit
import org.jetbrains.org.objectweb.asm.tree.MethodNode
import org.jetbrains.org.objectweb.asm.Opcodes.ASM5
import org.jetbrains.org.objectweb.asm.*
import com.intellij.openapi.util.Computable
import org.jetbrains.eval4j.*
import org.jetbrains.eval4j.jdi.JDIEval
import org.jetbrains.eval4j.jdi.asJdiValue
import org.jetbrains.eval4j.jdi.makeInitialFrame
import org.jetbrains.jet.lang.resolve.java.PackageClassUtils
import org.jetbrains.jet.lang.resolve.name.FqName
import org.jetbrains.jet.plugin.project.AnalyzerFacadeWithCache
import org.jetbrains.jet.plugin.debugger.KotlinEditorTextProvider
import org.jetbrains.jet.lang.psi.JetPsiFactory
import org.jetbrains.eval4j.jdi.asValue
import org.jetbrains.jet.plugin.refactoring.createTempCopy
import org.jetbrains.jet.plugin.refactoring.extractFunction.ExtractionData
import org.jetbrains.jet.plugin.refactoring.extractFunction.performAnalysis
import org.jetbrains.jet.plugin.util.MaybeError
import org.jetbrains.jet.plugin.util.MaybeValue
import org.jetbrains.jet.plugin.refactoring.extractFunction.validate
import org.jetbrains.jet.plugin.refactoring.checkConflictsInteractively
import org.jetbrains.jet.plugin.refactoring.extractFunction.generateFunction
import com.intellij.util.text.CharArrayUtil
import org.jetbrains.jet.lang.psi.JetNamedFunction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import org.jetbrains.jet.codegen.ClassFileFactory
import org.jetbrains.jet.plugin.codeInsight.CodeInsightUtils
import org.jetbrains.jet.OutputFileCollection
import org.jetbrains.jet.lang.psi.JetExpressionCodeFragment
import org.jetbrains.jet.lang.psi.JetExpressionCodeFragmentImpl
import org.jetbrains.jet.plugin.caches.resolve.getAnalysisResults

object KotlinEvaluationBuilder: EvaluatorBuilder {
    override fun build(codeFragment: PsiElement, position: SourcePosition?): ExpressionEvaluator {
        if (codeFragment !is JetExpressionCodeFragment || position == null) {
            return EvaluatorBuilderImpl.getInstance()!!.build(codeFragment, position)
        }

        val elementAt = position.getElementAt()
        if (elementAt != null) {
            codeFragment.addImportsFromString(KotlinEditorTextProvider.getImports(elementAt))

            val packageName = (elementAt.getContainingFile() as JetFile).getPackageDirective()?.getFqName()?.asString()
            if (packageName != null) {
                codeFragment.addImportsFromString("import $packageName.*")
            }
        }
        return ExpressionEvaluatorImpl(KotlinEvaluator(codeFragment, position))
    }
}

class KotlinEvaluator(val codeFragment: PsiElement,
                      val sourcePosition: SourcePosition
) : Evaluator {
    override fun evaluate(context: EvaluationContextImpl): Any? {
        val extractedFunction = getFunctionForExtractedFragment(codeFragment, sourcePosition.getFile(), sourcePosition.getLine())
        if (extractedFunction == null) {
            exception("This code fragment cannot be extracted to function")
        }

        val classFileFactory = createClassFileFactory(extractedFunction)

        // KT-4509
        val outputFiles = (classFileFactory : OutputFileCollection).asList().filter { it.relativePath != "$packageInternalName.class" }
        if (outputFiles.size() != 1) exception("More than one class file found. Note that lambdas, classes and objects are unsupported yet.\n${outputFiles.makeString("\n")}")

        val virtualMachine = context.getDebugProcess().getVirtualMachineProxy().getVirtualMachine()

        var resultValue: Value? = null
        ClassReader(outputFiles.first().asByteArray()).accept(object : ClassVisitor(ASM5) {
            override fun visitMethod(access: Int, name: String, desc: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                if (name == extractedFunction.getName()) {
                    return object : MethodNode(Opcodes.ASM5, access, name, desc, signature, exceptions) {
                        override fun visitEnd() {
                            val value = interpreterLoop(
                                    this,
                                    makeInitialFrame(this, context.getArgumentsByNames(extractedFunction.getParameterNamesForDebugger())),
                                    JDIEval(virtualMachine,
                                            context.getClassLoader()!!,
                                            context.getSuspendContext().getThread()?.getThreadReference()!!)
                            )

                            resultValue = when (value) {
                                is ValueReturned -> value.result
                                is ExceptionThrown -> exception(value.exception.toString())
                                is AbnormalTermination -> exception(value.message)
                                else -> throw IllegalStateException("Unknown result value produced by eval4j")
                            }
                        }
                    }
                }
                return super.visitMethod(access, name, desc, signature, exceptions)
            }
        }, 0)

        if (resultValue == null) exception("Cannot evaluate expression")

        return resultValue!!.asJdiValue(virtualMachine, resultValue!!.asmType)
    }

    override fun getModifier(): Modifier? {
        return null
    }

    private fun JetNamedFunction.getParameterNamesForDebugger(): List<String> {
        val result = arrayListOf<String>()
        for (param in getValueParameters()) {
            result.add(param.getName()!!)
        }
        if (getReceiverTypeRef() != null) {
            result.add("this")
        }
        return result
    }

    private fun EvaluationContextImpl.getArgumentsByNames(parameterNames: List<String>): List<Value> {
        val frames = getFrameProxy()?.getStackFrame()
        if (frames != null) {
            try {
                return parameterNames.map {
                    name ->
                    if (name == "this") {
                        frames.thisObject().asValue()
                    }
                    else {
                        frames.getValue(frames.visibleVariableByName(name)).asValue()
                    }
                }
            }
            catch(e: Throwable) {
                throw IllegalArgumentException(
                        "Cannot get parameter values from VirtualMachine: ${e.javaClass}\nFunction parameters:\n${parameterNames.makeString("\n")}\nVisible variables:\n${frames.visibleVariables().makeString("\n")}")
            }
        }
        return Collections.emptyList()
    }

    private fun createClassFileFactory(extractedFunction: JetNamedFunction): ClassFileFactory {
        return ApplicationManager.getApplication()?.runReadAction(object: Computable<ClassFileFactory> {
            override fun compute(): ClassFileFactory? {
                val file = createFileForDebugger(codeFragment as JetExpressionCodeFragment, extractedFunction)

                val analyzeExhaust = file.getAnalysisResults()
                val bindingContext = analyzeExhaust.getBindingContext()
                try {
                    analyzeExhaust.throwIfError()
                    AnalyzingUtils.throwExceptionOnErrors(bindingContext)
                }
                catch (e: IllegalStateException) {
                    exception(e.getMessage() ?: "Exception from kotlin compiler")
                }

                val state = GenerationState(
                        file.getProject(),
                        ClassBuilderFactories.BINARIES,
                        bindingContext,
                        Collections.singletonList(file),
                        true)

                KotlinCodegenFacade.compileCorrectFiles(state) {
                    e, msg ->
                    exception("$msg\n${e?.getMessage() ?: ""}")
                }

                return state.getFactory()
            }
        })!!

    }

    private fun exception(msg: String) = throw EvaluateExceptionUtil.createEvaluateException(msg)
}

private val template = """
package packageForDebugger

!IMPORT_LIST!

!FUNCTION!
"""

private val packageInternalName = PackageClassUtils.getPackageClassFqName(FqName("packageForDebugger")).asString().replace(".", "/")

private fun createFileForDebugger(codeFragment: JetExpressionCodeFragment,
                                  extractedFunction: JetNamedFunction
): JetFile {
    var fileText = template.replace("!IMPORT_LIST!",
                                    codeFragment.importsToString()
                                            .split(JetExpressionCodeFragmentImpl.IMPORT_SEPARATOR)
                                            .makeString("\n"))

    fileText = fileText.replace("!FUNCTION!", extractedFunction.getText())

    val virtualFile = LightVirtualFile("debugFile.kt", JetLanguage.INSTANCE, fileText)
    virtualFile.setCharset(CharsetToolkit.UTF8_CHARSET)
    return (PsiFileFactory.getInstance(codeFragment.getProject()) as PsiFileFactoryImpl)
            .trySetupPsiForFile(virtualFile, JetLanguage.INSTANCE, true, false) as JetFile
}

private fun getFunctionForExtractedFragment(
        codeFragment: PsiElement,
        breakpointFile: PsiFile,
        breakpointLine: Int
): JetNamedFunction? {
    return ApplicationManager.getApplication()?.runReadAction(object: Computable<JetNamedFunction> {
        override fun compute(): JetNamedFunction? {
            val project = codeFragment.getProject()

            val originalFile = breakpointFile as JetFile

            val lineStart = CodeInsightUtils.getStartLineOffset(originalFile, breakpointLine)
            if (lineStart == null) return null

            val tmpFile = originalFile.createTempCopy { it }
            val elementAtOffset = tmpFile.findElementAt(lineStart)
            if (elementAtOffset == null) return null

            val element: PsiElement = CodeInsightUtils.getTopmostElementAtOffset(elementAtOffset, lineStart) ?: elementAtOffset

            val debugExpression = JetPsiFactory.createExpression(project, codeFragment.getText())

            val parent = element.getParent()
            if (parent == null) return null

            parent.addBefore(JetPsiFactory.createNewLine(project), element)
            val newDebugExpression = parent.addBefore(debugExpression, element)
            if (newDebugExpression == null) return null

            parent.addBefore(JetPsiFactory.createNewLine(project), element)

            val nextSibling = tmpFile.getDeclarations().firstOrNull()
            if (nextSibling == null) return null

            val analysisResult = ExtractionData(tmpFile, Collections.singletonList(newDebugExpression), nextSibling).performAnalysis()
            if (analysisResult is MaybeError) {
                throw EvaluateExceptionUtil.createEvaluateException(analysisResult.error)
            }

            val validationResult = (analysisResult as MaybeValue).value.validate()
            if (!validationResult.conflicts.isEmpty()) {
                throw EvaluateExceptionUtil.createEvaluateException("Some declarations are unavailable")
            }

            return validationResult.descriptor.generateFunction(true)
        }
    })
}

