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

class AnimationManager(model: Model, replicationId: Int, autoAttach: Boolean = true) {
    private val myModel: Model = model
    private val myReplicationId: Int = replicationId;
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

    private fun addLog(log: String) {
        if (shouldLog) {
            logs.add(log)
        }
    }

    fun addObject(time: Double, objectType: ObjectType, kslObject: ModelElement.QObject) {
        addLog("$time: OBJECT ADD \"${objectType.id}\" AS \"${kslObject.name}\"")
    }

    fun removeObject(time: Double, kslObject: ModelElement.QObject) {
        addLog("$time: OBJECT REMOVE \"${kslObject.name}\"")
    }

    fun <T : ModelElement.QObject> joinQueue(time: Double, queue: Queue<T>, kslObject: ModelElement.QObject) {
        addModelObject(queue.name, "queue")
        addLog("$time: QUEUE \"${queue.name}\" JOIN \"${kslObject.name}\"")
    }

    fun <T : ModelElement.QObject> leaveQueue(time: Double, queue: Queue<T>, kslObject: ModelElement.QObject) {
        addModelObject(queue.name, "queue")
        addLog("$time: QUEUE \"${queue.name}\" LEAVE \"${kslObject.name}\"")
    }

    fun setResourceState(time: Double, resource: SResource, newState: String) {
        addModelObject(resource.name, "resource")
        addLog("$time: RESOURCE \"${resource.name}\" SET STATE \"$newState\"")
    }

    fun setResourceState(time: Double, resource: Resource, newState: String) {
        addModelObject(resource.name, "resource")
        addLog("$time: RESOURCE \"${resource.name}\" SET STATE \"$newState\"")
    }

    fun saveAnimation(filename: String, setupOnly: Boolean = false) {
        val animationData = KSLAnimation(
            objects = animationObjects.values.toList(), // Convert map values to list
            builderSetup = false
        )

        val setupJson = Json.encodeToString(animationData)
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
}