package com.github.valich.jflextokotlin.actions

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.lang.jvm.JvmModifier
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.*
import com.intellij.psi.impl.source.tree.java.PsiLiteralExpressionImpl
import org.jetbrains.kotlin.idea.actions.JavaToKotlinAction
import org.jetbrains.kotlin.idea.util.application.executeWriteCommand
import org.jetbrains.kotlin.psi.KtFile
import kotlin.streams.toList

const val packedLineMaxLength = 5000

class ConvertLexerToKotlin : AnAction() {

    override fun update(e: AnActionEvent) {
        templatePresentation.isEnabledAndVisible = e.isJavaFlexLexerFile()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val javaPsi = e.dataContext.getData(PlatformDataKeys.PSI_FILE) as? PsiJavaFile ?: return

        convertStringLiterals(javaPsi)
        moveAroundPackedStrings(javaPsi)
        val ktFile = invokeConversion(javaPsi)
        doCommonCompatConversions(ktFile)
    }

    private fun invokeConversion(javaPsi: PsiJavaFile): KtFile {
        return JavaToKotlinAction.convertFiles(
            javaFiles = listOf(javaPsi),
            project = javaPsi.project,
            module = ModuleUtilCore.findModuleForFile(javaPsi)!!,
        ).single()
    }

    private fun convertStringLiterals(javaPsi: PsiFile) {
        javaPsi.project.executeWriteCommand("conversion1") {
            javaPsi.accept(object : JavaRecursiveElementVisitor() {
                override fun visitPolyadicExpression(expression: PsiPolyadicExpression) {
                    if (expression.operationTokenType == JavaTokenType.PLUS) {
                        if (expression.operands.all {
                                (it as? PsiLiteralExpressionImpl)?.literalElementType == JavaTokenType.STRING_LITERAL
                            }) {
                            expression.replace(createShorterStringConcat(expression))
                            return
                        }
                    }
                    return super.visitPolyadicExpression(expression)
                }

                override fun visitLiteralExpression(expression: PsiLiteralExpression) {
                    if ((expression as? PsiLiteralExpressionImpl)?.literalElementType == JavaTokenType.STRING_LITERAL
                        && (expression.value as String).any { it.toInt() < 10 }
                    ) {
                        expression.replace(createUnicodeNotationPsiString(expression))
                    } else {
                        super.visitLiteralExpression(expression)
                    }
                }
            })
        }
    }

    private fun moveAroundPackedStrings(javaPsi: PsiJavaFile) {
        javaPsi.project.executeWriteCommand("conversion2") {
            val lexerClass = javaPsi.classes.single {
                @Suppress("UnstableApiUsage")
                it.hasModifier(JvmModifier.PUBLIC)
            }

            lexerClass.fields.forEach { psiField ->
                if (psiField.name.endsWith("_PACKED_0")) {
                    val unpackedField =
                        lexerClass.findFieldByName(psiField.name.substringBeforeLast("_PACKED_0"), false)
                            ?: error("weird. ${psiField.name}")

                    if (psiField.startOffsetInParent > unpackedField.startOffsetInParent) {
                        val unpackedCopy = unpackedField.copy()
                        unpackedField.replace(psiField.copy())
                        psiField.replace(unpackedCopy)
                    }
                }
            }
        }
    }

    private fun doCommonCompatConversions(ktFile: KtFile) {
        val document = PsiDocumentManager.getInstance(ktFile.project).getDocument(ktFile)
            ?: error("can't find document")
        val text = document.text

        val newText = text.replace("Character", "Compat")
            .replace("java.io.", "")
            .replace("java.util.", "")
            .replace("java.lang.", "")
            .replace(": Set", ": HashSet")
            .replace(": List", ": ArrayList")
            .replace("(`in`: Reader)", "")
            .replace("@Throws(IOException::class)", "")
            .replace("] shl", "].toInt() shl")
            .replace("zzForAction@{", "zzForAction@")
            .replace(Regex("}\\s+// store back cached position"), ""
            )
            .replace(
                "init {\n" +
                        "        this.zzReader = `in`\n" +
                        "    }", ""
            )

        ktFile.project.executeWriteCommand("conversion3") {
            document.setText(newText)
        }
    }

    private fun createShorterStringConcat(expression: PsiPolyadicExpression): PsiElement {
        val totalString =
            expression.operands.joinToString(separator = "") { (it as PsiLiteralExpression).value as String }

        val linesNumber = (totalString.length + packedLineMaxLength - 1) / packedLineMaxLength
        val newText = (0 until linesNumber).joinToString(separator = " +\n") { lineNumber ->
            val from = lineNumber * packedLineMaxLength
            val to = ((lineNumber + 1) * packedLineMaxLength).coerceAtMost(totalString.length)
            "\"${totalString.substring(from, to).createUnicodeNotationString()}\""
        }

        return PsiElementFactory.getInstance(expression.project).createExpressionFromText(newText, expression)
    }

    private fun createUnicodeNotationPsiString(expression: PsiLiteralExpression): PsiElement {
        return PsiElementFactory.getInstance(expression.project).createExpressionFromText(
            "\"${(expression.value as String).createUnicodeNotationString()}\"",
            expression
        )
    }
}

internal fun AnActionEvent.isJavaFlexLexerFile(): Boolean {
    val file = dataContext.getData(PlatformDataKeys.VIRTUAL_FILE) ?: return false
    return file.fileType == JavaFileType.INSTANCE && file.name.startsWith("_")
}

internal fun String.createUnicodeNotationString(): String {
    return codePoints().toList().joinToString(separator = "") { cp ->
        when (cp) {
            0x000a -> "\\n"
            0x000d -> "\\r"
            0x0022 -> "\\\""
            else -> "\\u" + String.format("%04x", cp)
        }
    }
}