package ksl.observers

import ksl.simulation.Model
import ksl.simulation.ModelElement
import java.io.File

class AnimationManager(model: Model, replicationId: Int, autoAttach: Boolean = true) {
    private val myModel: Model = model
    private val myReplicationId: Int = replicationId;
    private val modelObserver: ModelObserver = ModelObserver()
    private var logFile = "";
    private var currentReplication = 0
    private var shouldLog = false

    init {
        if (autoAttach) {
            myModel.attachModelElementObserver(modelObserver)
        }
    }

    fun startObserving() {
        if (!myModel.isModelElementObserverAttached(modelObserver)) {
            myModel.attachModelElementObserver(modelObserver)
        }
    }

    fun stopObserving() {
        if (myModel.isModelElementObserverAttached(modelObserver)) {
            myModel.detachModelElementObserver(modelObserver)
        }
    }

    fun addToLog(line: String) {
        if (shouldLog) {
            logFile += "$line\n"
        }
    }

    fun printToConsole() {
        println(logFile)
    }

    fun printToFile(filename: String) {
        try {
            File(filename).writeText(logFile)
            println("Log successfully written to $filename")
        } catch (e: Exception) {
            println("Error writing to file: ${e.message}")
        }
    }

    private inner class ModelObserver : ModelElementObserver() {
        override fun beforeReplication(modelElement: ModelElement) {
            shouldLog = (currentReplication == myReplicationId)
            currentReplication++
        }
    }
}