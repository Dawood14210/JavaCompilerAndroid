package dev.javapad.compiler

/** Message contract shared by the UI process and the killable :runner process. */
internal object RunnerProtocol {
    const val RUN = 1
    const val STOP = 2
    const val STDIN = 3

    const val OUTPUT = 10
    const val PROBLEMS = 11
    const val FINISHED = 12
    const val TIMED_OUT = 13
    const val STOPPED = 14

    const val KEY_SOURCES = "sources"
    const val KEY_ENTRY_POINT = "entry_point"
    const val KEY_PROJECT_ID = "project_id"
    const val KEY_TIMEOUT = "timeout"
    const val KEY_TEXT = "text"
    const val KEY_STDERR = "stderr"
    const val KEY_PROBLEMS = "problems"
    const val KEY_SUMMARY = "summary"
}
