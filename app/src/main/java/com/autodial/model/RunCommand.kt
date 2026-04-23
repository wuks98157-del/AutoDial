package com.autodial.model

sealed class RunCommand {
    data class Start(val params: RunParams, val runId: Long) : RunCommand()
    object Stop : RunCommand()
}
