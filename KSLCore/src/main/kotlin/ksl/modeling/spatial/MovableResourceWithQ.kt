package ksl.modeling.spatial

import ksl.modeling.entity.RequestQ
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.RandomSourceCIfc
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.TWResponse
import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc
import ksl.utilities.observers.ObservableComponent
import ksl.utilities.observers.ObserverIfc
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.toDouble

class MovableResourceWithQ(
    parent: ModelElement,
    initLocation: LocationIfc,
    defaultVelocity: RandomIfc,
    name: String? = null,
    queue: RequestQ? = null,
) : ResourceWithQ(parent, name, 1, queue), SpatialElementIfc, VelocityIfc {
    protected val mySpatialElement = SpatialElement(this, initLocation, name)

    protected val myVelocity = RandomVariable(this, defaultVelocity)
    val velocityRV: RandomSourceCIfc
        get() = myVelocity
    override val velocity: GetValueIfc
        get() = myVelocity

    override var isMoving: Boolean
        get() = mySpatialElement.isMoving
        set(value) {
            mySpatialElement.isMoving = value
            myFracTimeMoving.value = value.toDouble()
        }
    var isTransporting: Boolean = false
        set(value) {
            field = value
            myFracTimeTransporting.value = field.toDouble()
        }
    var isMovingEmpty: Boolean = false
        set(value) {
            field = value
            myFracTimeMovingEmpty.value = field.toDouble()
        }
    private val myFracTimeMoving =
        TWResponse(this, name = "${this.name}:FracTimeMoving", initialValue = mySpatialElement.isMoving.toDouble())
    private val myFracTimeTransporting = TWResponse(this, name = "${this.name}:FracTimeTransporting")
    private val myFracTimeMovingEmpty = TWResponse(this, name = "${this.name}:FracTimeMovingEmpty")
    override val isTracked: Boolean
        get() = mySpatialElement.isTracked
    override val spatialID: Int
        get() = mySpatialElement.spatialID
    override val spatialName: String
        get() = mySpatialElement.spatialName
    override val status: SpatialModel.Status
        get() = mySpatialElement.status
    override var initialLocation: LocationIfc
        get() = mySpatialElement.initialLocation
        set(value) {
            mySpatialElement.initialLocation = value
        }
    override var currentLocation: LocationIfc
        get() = mySpatialElement.currentLocation
        set(value) {
            mySpatialElement.currentLocation = value
        }
    override val previousLocation: LocationIfc
        get() = mySpatialElement.previousLocation
    override val modelElement: ModelElement
        get() = mySpatialElement.modelElement
    override val observableComponent: ObservableComponent<SpatialElementIfc>
        get() = mySpatialElement.observableComponent

    var initialHomeBase: LocationIfc? = null
    var homeBase:LocationIfc? = null

    override fun initialize() {
        super.initialize()
        homeBase = initialHomeBase
        initializeSpatialElement()
    }

    override fun initializeSpatialElement() {
        mySpatialElement.initializeSpatialElement()
    }

    override fun attachObserver(observer: ObserverIfc<SpatialElementIfc>) {
        mySpatialElement.attachObserver(observer)
    }

    override fun detachObserver(observer: ObserverIfc<SpatialElementIfc>) {
        mySpatialElement.detachObserver(observer)
    }

    override fun isAttached(observer: ObserverIfc<SpatialElementIfc>): Boolean {
        return mySpatialElement.isAttached(observer)
    }

    override fun detachAllObservers() {
        mySpatialElement.detachAllObservers()
    }

    override fun countObservers(): Int {
        return mySpatialElement.countObservers()
    }

}