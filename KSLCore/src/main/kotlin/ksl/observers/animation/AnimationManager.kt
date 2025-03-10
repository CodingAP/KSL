package ksl.observers.animation

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import ksl.modeling.entity.Resource
import ksl.modeling.queue.Queue
import ksl.modeling.station.SResource
import ksl.observers.ModelElementObserver
import ksl.simulation.Model
import ksl.simulation.ModelElement
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AnimationManager(private val myModel: Model, private val myReplicationId: Int = 0, private val startTime: Double = 0.0, private val endTime: Double = 1000000.0, autoAttach: Boolean = true) {
    private val modelObserver: ModelObserver = ModelObserver()
    private var currentReplication = 0
    private var shouldLog = false
    private val logs = mutableListOf<String>()

    companion object {
        private val animationObjects = mutableMapOf<String, KSLAnimationObject>()

        fun addModelObject(id: String, type: String) {
            animationObjects.putIfAbsent(id, KSLAnimationObject(type, id))
        }
    }

    init {
        if (autoAttach) {
            startObserving()
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

    private fun addLog(log: String, time: Double) {
        if (shouldLog && time in startTime..endTime) {
            logs.add("$time: $log")
        }
    }

    fun addObject(time: Double, objectType: ObjectType, kslObject: ModelElement.QObject) {
        addLog("OBJECT ADD \"${objectType.id}\" AS \"${kslObject.name}\"", time)
    }

    fun removeObject(time: Double, kslObject: ModelElement.QObject) {
        addLog("OBJECT REMOVE \"${kslObject.name}\"", time)
    }

    fun <T : ModelElement.QObject> joinQueue(time: Double, queue: Queue<T>, kslObject: ModelElement.QObject) {
        addModelObject(queue.name, "queue")
        addLog("QUEUE \"${queue.name}\" JOIN \"${kslObject.name}\"", time)
    }

    fun <T : ModelElement.QObject> leaveQueue(time: Double, queue: Queue<T>, kslObject: ModelElement.QObject) {
        addModelObject(queue.name, "queue")
        addLog("QUEUE \"${queue.name}\" LEAVE \"${kslObject.name}\"", time)
    }

    fun setResourceState(time: Double, resource: SResource, newState: String) {
        addModelObject(resource.name, "resource")
        addLog("RESOURCE \"${resource.name}\" SET STATE \"$newState\"", time)
    }

    fun setResourceState(time: Double, resource: Resource, newState: String) {
        addModelObject(resource.name, "resource")
        addLog("RESOURCE \"${resource.name}\" SET STATE \"$newState\"", time)
    }

    fun moveObject(time: Double, kslObject: ModelElement.QObject, startStation: Station, endStation: Station, movementFunction: String = "LINEAR") {
        addLog("MOVE \"${kslObject.name}\" FROM \"${startStation.id}\" TO \"${endStation.id}\" AS $movementFunction", time)
    }

    fun saveAnimation(filename: String, setupOnly: Boolean = false) {
        val animationData = KSLAnimation(
            objects = animationObjects.values.toList(), // Convert map values to list
            builderSetup = false
        )

        val setupJson = Json.encodeToString(animationData)

        logs.add(0, "$startTime: START")
        logs.add(logs.size - 1, "$endTime: STOP")
        val logData = logs.joinToString("\n")

        FileOutputStream(filename).use { fos ->
            ZipOutputStream(fos).use { zipOut ->
                // Add setup.json to ZIP
                zipOut.putNextEntry(ZipEntry("setup.json"))
                zipOut.write(setupJson.toByteArray())
                zipOut.closeEntry()

                if (!setupOnly) {
                    // Add sim.log to ZIP
                    zipOut.putNextEntry(ZipEntry("sim.log"))
                    zipOut.write(logData.toByteArray())
                    zipOut.closeEntry()
                }
            }
        }
    }

    private inner class ModelObserver : ModelElementObserver() {
        override fun beforeReplication(modelElement: ModelElement) {
            shouldLog = (currentReplication == myReplicationId)
            currentReplication++
        }
    }

    @Serializable
    data class KSLAnimationObject(
        val type: String,
        val id: String
    )

    @Serializable
    data class KSLAnimation(
        val objects: List<KSLAnimationObject>,
        val builderSetup: Boolean
    )

    data class ObjectType(val id: String) {
        init {
            addModelObject(id, "object_type")
        }
    }

    data class Station(val id: String) {
        init {
            addModelObject(id, "station")
        }
    }
}