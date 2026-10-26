package dev.myct

import androidx.compose.runtime.*
import java.io.EOFException
import java.net.SocketTimeoutException
import java.util.zip.DataFormatException

internal enum class DesktopStep(val label: String) {
    SCREEN("Entrada na tela"), SSH("Verificação da sessão SSH"), TUNNEL("Túnel local SSH"),
    TCP("TCP no endpoint local"), VERSION("Versão RFB"), SECURITY("Segurança VncAuth"),
    AUTH("Autenticação VNC"), FRAMEBUFFER("Inicialização do framebuffer"),
    UPDATE("Primeira atualização recebida"), DECODE("Decodificação da imagem"),
    FRAME_READY("Primeiro framebuffer decodificado"), RENDER("Primeiro quadro desenhado")
}
internal enum class DesktopFailure(val label: String) {
    SSH("SSH indisponível"), TUNNEL("Falha no túnel SSH"), TCP("Falha TCP no túnel"),
    VERSION("Versão RFB inválida"), SECURITY("Segurança RFB incompatível"), AUTH("Autenticação VNC recusada"),
    PROTOCOL("Falha de protocolo RFB"), DECODE("Falha de decodificação"), RENDER("Falha de renderização"),
    DISCONNECTED("Conexão interrompida"), TIMEOUT("Tempo de espera excedido")
}
internal data class DesktopDiagnostic(
    val current: DesktopStep = DesktopStep.SCREEN,
    val completed: Set<DesktopStep> = emptySet(),
    val failure: DesktopFailure? = null
) {
    val lastCompleted get() = completed.maxByOrNull { it.ordinal }
}

/** One trace per attempt: late IO callbacks can only update their abandoned trace. No raw server text. */
internal class DesktopTrace {
    var state by mutableStateOf(DesktopDiagnostic()); private set
    @Synchronized fun step(step: DesktopStep, done: Boolean) {
        if (state.failure != null || step in state.completed) return
        state = state.copy(current = step, completed = if (done) state.completed + step else state.completed)
    }
    @Synchronized fun fail(failure: DesktopFailure) { state = state.copy(failure = failure) }
}
internal class DesktopRenderFailure : Exception("Falha ao apresentar o framebuffer")
internal fun desktopFailure(error: Exception, stage: DesktopStep): DesktopFailure = when {
    error is DesktopRenderFailure -> DesktopFailure.RENDER
    error is SocketTimeoutException -> DesktopFailure.TIMEOUT
    error is RfbException && error.kind != null -> error.kind
    stage == DesktopStep.TUNNEL -> DesktopFailure.TUNNEL
    stage == DesktopStep.TCP -> DesktopFailure.TCP
    error is EOFException -> DesktopFailure.DISCONNECTED
    error is DataFormatException -> DesktopFailure.DECODE
    stage == DesktopStep.AUTH -> DesktopFailure.AUTH
    error is RfbException -> if (stage == DesktopStep.DECODE) DesktopFailure.DECODE else DesktopFailure.PROTOCOL
    else -> DesktopFailure.DISCONNECTED
}

internal enum class DesktopBackAction { CLOSE_KEYBOARD, RESTORE_CONTROLS, EXIT_SECTION }
internal fun desktopBackAction(keyboardVisible: Boolean, immersive: Boolean): DesktopBackAction = when {
    keyboardVisible -> DesktopBackAction.CLOSE_KEYBOARD
    immersive -> DesktopBackAction.RESTORE_CONTROLS
    else -> DesktopBackAction.EXIT_SECTION
}
