package dev.myct

import android.app.Application

/** One owner per process: returning to the UI attaches to the existing authenticated session. */
class MyCtApplication : Application() {
    val terminal: TerminalModel by lazy { TerminalModel(this) }
}
