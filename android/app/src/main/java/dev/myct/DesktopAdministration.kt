package dev.myct

internal enum class DesktopAdminAccess { UNKNOWN, MISSING, UNSAFE, INCOMPATIBLE, DENIED, READY, ERROR }
internal data class DesktopAdministration(
    val access: DesktopAdminAccess = DesktopAdminAccess.UNKNOWN,
    val start: Boolean = false,
    val stop: Boolean = false
) {
    val canStart get() = access == DesktopAdminAccess.READY && start
    val canStop get() = access == DesktopAdminAccess.READY && stop
    val message get() = when (access) {
        DesktopAdminAccess.UNKNOWN -> "Gerenciamento não verificado. Atualize o estado; Conectar continua disponível."
        DesktopAdminAccess.MISSING -> "Gerenciamento indisponível: helper não instalado no Fedora. A visualização VNC continua disponível."
        DesktopAdminAccess.UNSAFE -> "Gerenciamento bloqueado: permissões ou caminho do helper inválidos. Revisão manual necessária."
        DesktopAdminAccess.INCOMPATIBLE -> "Gerenciamento desatualizado: atualize o helper e sua autorização pelo procedimento Desktop F2."
        DesktopAdminAccess.DENIED -> "Gerenciamento sem autorização não interativa. O proprietário deve revisar a regra restrita; a visualização continua disponível."
        DesktopAdminAccess.ERROR -> "Não foi possível verificar o gerenciamento. Atualize o estado; a visualização continua disponível."
        DesktopAdminAccess.READY -> when {
            start && stop -> "Iniciar e Encerrar autorizados nesta consulta."
            start -> "Iniciar autorizado; Encerrar sem autorização."
            stop -> "Encerrar autorizado; Iniciar sem autorização."
            else -> "Iniciar e Encerrar sem autorização."
        }
    }
}
internal enum class DesktopAdminOutcome { NONE, RUNNING, SUCCEEDED, UNAVAILABLE, DENIED, FAILED, UNCERTAIN }
internal fun parseDesktopAdministration(reply: SshExecResult): DesktopAdministration {
    if (reply.status != 0) return DesktopAdministration(DesktopAdminAccess.ERROR)
    val match = Regex("MYCT_DESKTOP_ADMIN_V1 (missing|unsafe|incompatible|denied|ready|error) ([01]) ([01])")
        .matchEntire(reply.output.trim()) ?: return DesktopAdministration(DesktopAdminAccess.ERROR)
    val access = DesktopAdminAccess.valueOf(match.groupValues[1].uppercase())
    val start = match.groupValues[2] == "1"; val stop = match.groupValues[3] == "1"
    if (access != DesktopAdminAccess.READY && (start || stop)) return DesktopAdministration(DesktopAdminAccess.ERROR)
    return DesktopAdministration(access, start, stop)
}

/** Fixed, read-only preflight: no start/stop, shell input, password prompt or policy changes. */
internal fun desktopAdministrationCommand(): String = """
    export LC_ALL=C
    h=/usr/local/libexec/myct-desktop-control
    emit() { /usr/bin/printf 'MYCT_DESKTOP_ADMIN_V1 %s %s %s\n' "${'$'}1" "${'$'}2" "${'$'}3"; }
    safe() {
        set -- ${'$'}(/usr/bin/stat -c '%u %g %a' -- "${'$'}1" 2>/dev/null)
        [ "${'$'}#" -eq 3 ] || return 1
        case "${'$'}3" in ''|*[!0-7]*) return 1 ;; esac
        [ "${'$'}1:${'$'}2" = 0:0 ] && [ "${'$'}((0${'$'}3 & 0022))" -eq 0 ]
    }
    if [ ! -e "${'$'}h" ] && [ ! -L "${'$'}h" ]; then emit missing 0 0; exit 0; fi
    if [ ! -f "${'$'}h" ] || [ ! -x "${'$'}h" ] || [ -L "${'$'}h" ] || ! safe "${'$'}h"; then emit unsafe 0 0; exit 0; fi
    mode=${'$'}(/usr/bin/stat -c '%a' -- "${'$'}h")
    if [ "${'$'}((0${'$'}mode & 06000))" -ne 0 ]; then emit unsafe 0 0; exit 0; fi
    for parent in / /usr /usr/local /usr/local/libexec; do
        if [ -L "${'$'}parent" ] || ! safe "${'$'}parent"; then emit unsafe 0 0; exit 0; fi
    done
    state=${'$'}("${'$'}h" status 2>/dev/null)
    rc=${'$'}?
    if [ "${'$'}rc" -eq 64 ]; then emit incompatible 0 0; exit 0; fi
    if [ "${'$'}rc" -ne 0 ]; then emit error 0 0; exit 0; fi
    case "${'$'}state" in active|inactive|failed|activating|deactivating|reloading) ;; *) emit error 0 0; exit 0 ;; esac
    if ! /usr/bin/sudo -n -N -u root -- "${'$'}h" status >/dev/null 2>&1; then emit denied 0 0; exit 0; fi
    start=0; stop=0
    if /usr/bin/sudo -n -N -u root -l -- "${'$'}h" start >/dev/null 2>&1; then start=1; fi
    if /usr/bin/sudo -n -N -u root -l -- "${'$'}h" stop >/dev/null 2>&1; then stop=1; fi
    if [ "${'$'}start${'$'}stop" = 00 ]; then emit denied 0 0; else emit ready "${'$'}start" "${'$'}stop"; fi
""".trimIndent()

internal fun desktopServiceLabel(state: DesktopService): String = when (state) {
    DesktopService.UNKNOWN -> "não consultada"
    DesktopService.OFF -> "desligada"
    DesktopService.STARTING -> "iniciando"
    DesktopService.AVAILABLE -> "ativa"
    DesktopService.STOPPING -> "encerrando"
    DesktopService.ERROR -> "não confirmada (falha na consulta)"
}
