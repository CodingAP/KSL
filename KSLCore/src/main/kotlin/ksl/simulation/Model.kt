/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.simulation

import kotlinx.datetime.Instant
import ksl.calendar.CalendarIfc
import ksl.calendar.PriorityQueueEventCalendar
import ksl.controls.Controls
import ksl.modeling.elements.RandomElementIfc
import ksl.modeling.spatial.Euclidean2DPlane
import ksl.modeling.spatial.SpatialModel
import ksl.modeling.variable.*
import ksl.utilities.io.KSL
import ksl.utilities.io.LogPrintWriter
import ksl.utilities.io.OutputDirectory
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rvariable.parameters.RVParameterSetter
import ksl.utilities.random.rvariable.UniformRV
import ksl.utilities.statistic.StatisticIfc
import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.observers.textfile.CSVExperimentReport
import ksl.observers.textfile.CSVReplicationReport
import ksl.utilities.io.toDataFrame
import ksl.utilities.random.rvariable.parameters.RVParameterSetter.Companion.rvParamConCatChar
import org.jetbrains.kotlinx.dataframe.api.remove
import java.nio.file.Path
import kotlin.time.Duration

private var simCounter: Int = 0

class Model(
    val simulationName: String = "Simulation${++simCounter}",
    pathToOutputDirectory: Path = KSL.createSubDirectory(simulationName.replace(" ", "_") + "_OutputDir"),
    var autoCSVReports: Boolean = false,
    eventCalendar: CalendarIfc = PriorityQueueEventCalendar(),
) : ModelElement(simulationName.replace(" ", "_")), ExperimentIfc {
//TODO what are the public methods/properties of ModelElement and are they all appropriate for Model
    /**
     *
     * @return the defined OutputDirectory for the simulation
     */
    var outputDirectory: OutputDirectory = OutputDirectory(pathToOutputDirectory, "kslOutput.txt")

    private var myCSVRepReport: CSVReplicationReport? = null
    private var myCSVExpReport: CSVExperimentReport? = null

    var autoPrintSummaryReport: Boolean = false

    /**
     *
     * @return the pre-defined default text output file for the simulation
     */
    val out: LogPrintWriter
        get() = outputDirectory.out

    internal val myExecutive: Executive = Executive(eventCalendar)
    internal val myExperiment: Experiment = Experiment()

    private val myStreams: MutableSet<RNStreamIfc> = mutableSetOf()

    /** A flag to control whether a warning is issued if the user does not
     * set the replication run length
     */
    var repLengthWarningMessageOption = true

    /**
     *  The base time units for the simulation model. By default, this is 1.0.
     */
    var baseTimeUnit: TimeUnit = TimeUnit.MILLISECOND

    private var myResponses: MutableList<Response> = ArrayList()
    val responses: List<Response>
        get() = myResponses

    /**
     * A list of all the Counters within the model
     */
    private var myCounters: MutableList<Counter> = ArrayList()
    val counters: List<Counter>
        get() = myCounters

    /**
     * A list of all the HistogramResponses within the model
     */
    private var myHistograms: MutableList<HistogramResponse> = ArrayList()
    val histograms: List<HistogramResponse>
        get() = myHistograms

    /**
     * A list of all the IntegerFrequencyResponses within the model
     */
    private var myFrequencies: MutableList<IntegerFrequencyResponse> = ArrayList()
    val frequencies: List<IntegerFrequencyResponse>
        get() = myFrequencies

    /**
     *  The names of all the response variables and counters in the model
     */
    val responseNames: List<String>
        get() {
            val list = mutableListOf<String>()
            val r = myResponses.map { it.name }
            val c = myCounters.map { it.name }
            list.addAll(r)
            list.addAll(c)
            return list
        }

    /**
     * A list of all the Variables within the model
     */
    private var myVariables: MutableList<Variable> = ArrayList()
    val variables: List<Variable>
        get() = myVariables

    /**
     * A list of all random elements within the model
     */
    private var myRandomElements: MutableList<RandomElementIfc> = ArrayList()

    /**
     * A Map that holds all the model elements in the order in which they are
     * created
     */
    private val myModelElementMap: MutableMap<String, ModelElement> = LinkedHashMap()

    /** to hold the controls if used
     *
     */
    private lateinit var myControls: Controls

    internal var batchingElement: StatisticalBatchingElement? = null

    /**
     * A StatisticalBatchingElement is used to control statistical batching for
     * single replication simulations. This method creates and attaches a
     * StatisticalBatchingElement to the model. For convenience, it also returns
     * the created element
     * @param batchingInterval the discretizing interval for TWResponse variables
     * @param name the name of the model element
     * @return the StatisticalBatchingElement
     */
    fun statisticalBatching(batchingInterval: Double = 0.0, name: String? = null): StatisticalBatchingElement {
        if (batchingElement == null) {
            batchingElement = StatisticalBatchingElement(this, batchingInterval, name)
        }
        return batchingElement!!
    }

    /**
     * to hold the parameters of the random variables if used
     */
    private var myRVParameterSetter: RVParameterSetter? = null

    /**
     *  Checks if the model's rvParameterSetter property has been accessed
     *  and thus that an RVParameterSetter was requested for the model
     */
    fun hasParameterSetter(): Boolean {
        return myRVParameterSetter != null
    }

    /**
     * If the model parameters change then the user is responsible for
     * calling extractParameters(model) on the returned RVParameterSetter
     *
     * @returns a RVParameterSetter configured with the parameters of the model at
     * the time of accessing the property.
     */
    val rvParameterSetter: RVParameterSetter
        get() {
            if (myRVParameterSetter == null) {
                myRVParameterSetter = RVParameterSetter(this)
            }
            return myRVParameterSetter!!
        }

    /**
     * Controls the execution of replications
     */
    private val myReplicationProcess: ReplicationProcess = ReplicationProcess("Model: Replication Process")

    init {
        myModel = this
        myParentModelElement = null
        addToModelElementMap(this)
        addDefaultElements()
    }

    //TODO default stream?
    internal val myDefaultUniformRV = RandomVariable(this, UniformRV(), "${this.name}:DefaultUniformRV")

    val simulationReporter: SimulationReporter = SimulationReporter(this)

    /**
     * @param stream the stream that the model will manage
     */
    fun addStream(stream: RNStreamIfc) {
        val b = myStreams.add(stream)
        if (b) {
            RNStreamProvider.logger.info { "Stream $stream added to model stream control" }
        }
    }

    /**
     * @param stream the stream that the model will no longer manage
     */
    fun removeStream(stream: RNStreamIfc) {
        myStreams.remove(stream)
    }

    /**
     * Attaches the CSVReplicationReport to the model if not attached.
     * If you turn on the reporting, you need to do it before running the simulation.
     *
     */
    fun turnOnReplicationCSVStatisticReporting() {
        if (myCSVRepReport == null){
            myCSVRepReport = CSVReplicationReport(model)
        }
        if (!isModelElementObserverAttached(myCSVRepReport!!)){
            attachModelElementObserver(myCSVRepReport!!)
        }
    }

    /**
     * Detaches the CSVReplicationReport from the model.
     *
     */
    fun turnOffReplicationCSVStatisticReporting() {
        if (myCSVRepReport == null){
            return
        }
        detachModelElementObserver(myCSVRepReport!!)
        myCSVRepReport = null
    }

    /**
     * Attaches the CSVExperimentReport to the model if not attached.
     *
     */
    fun turnOnAcrossReplicationStatisticReporting(){
        if (myCSVExpReport == null){
            myCSVExpReport = CSVExperimentReport(model)
        }
        if (!isModelElementObserverAttached(myCSVExpReport!!)){
            attachModelElementObserver(myCSVExpReport!!)
        }
    }

    /**
     * Detaches the CSVExperimentReport from the model.
     *
     */
    fun turnOffAcrossReplicationStatisticReporting() {
        if (myCSVExpReport == null){
            return
        }
        detachModelElementObserver(myCSVExpReport!!)
        myCSVExpReport = null
    }

    /**
     * Tells the model to capture statistical output for within replication
     * and across replication responses to comma separated value files. If you do not
     * want both turned on, or you want to control the reporting more directly then use the
     * individual functions for this purpose.  Turning on the reporting only effects the next simulation run. So,
     * turn on the reporting before you simulate.  If you want the reporting all the time, then
     * just supply the autoCSVReports option as true when you create the model.
     */
    fun turnOnCSVStatisticalReports() {
        turnOnReplicationCSVStatisticReporting()
        turnOnAcrossReplicationStatisticReporting()
    }

    /**
     *  Tells the model to stop collecting and reporting within and across replication
     *  statistics as comma separated value (CSV) files.
     */
    fun turnOffCSVStatisticalReports(){
        turnOffReplicationCSVStatisticReporting()
        turnOffAcrossReplicationStatisticReporting()
    }

    /**
     * A flag to indicate whether the simulation is done A simulation can be done if:
     * 1) it ran all of its replications 2) it was ended by a
     * client prior to completing all of its replications 3) it ended because it
     * exceeded its maximum allowable execution time before completing all of
     * its replications. 4) its end condition was satisfied
     *
     */
    val isDone: Boolean
        get() = myReplicationProcess.isDone

    /**
     * Returns if the elapsed execution time exceeds the maximum time allowed.
     * Only true if the maximum was set and elapsed time is greater than or
     * equal to maximumAllowedExecutionTime
     */
    val isExecutionTimeExceeded: Boolean
        get() = myReplicationProcess.isExecutionTimeExceeded

    /**
     * Returns system time in nanoseconds that the simulation started
     */
    val beginExecutionTime: Instant
        get() = myReplicationProcess.beginExecutionTime

    /**
     * Gets the clock time in nanoseconds since the simulation was
     * initialized
     */
    val elapsedExecutionTime: Duration
        get() = myReplicationProcess.elapsedExecutionTime

    /**
     * Returns system time in nanoseconds that the simulation ended
     */
    val endExecutionTime: Instant
        get() = myReplicationProcess.endExecutionTime

    /**
     * The maximum allotted (suggested) execution (real) clock for the
     * entire iterative process in nanoseconds. This is a suggested time because the execution
     * time requirement is only checked after the completion of an individual
     * step After it is discovered that cumulative time for executing the step
     * has exceeded the maximum time, then the iterative process will be ended
     * (perhaps) not completing other steps.
     */
    var maximumAllowedExecutionTime: Duration
        get() = myReplicationProcess.maximumAllowedExecutionTime
        set(value) {
            myReplicationProcess.maximumAllowedExecutionTime = value
        }

    /**
     * Returns the replications completed since the simulation was
     * last initialized
     *
     * @return the number of replications completed
     */
    val numberReplicationsCompleted: Int
        get() = myReplicationProcess.numberStepsCompleted

    /**
     * Checks if the simulation is in the created state. If the
     * simulation is in the created execution state this method will return true
     *
     * @return true if in the created state
     */
    val isCreated: Boolean
        get() = myReplicationProcess.isCreated

    /**
     * Checks if the simulation is in the initialized state After the
     * simulation has been initialized this method will return true
     *
     * @return true if initialized
     */
    val isInitialized: Boolean
        get() = myReplicationProcess.isInitialized

    /**
     * A model is running if it has been told to simulate (i.e.
     * simulate() or runNextReplication()) but has not yet been told to end().
     *
     */
    val isRunning: Boolean
        get() = myReplicationProcess.isRunning

    /**
     * The complement of isRunning
     */
    val isNotRunning: Boolean
        get() = !isRunning

    /**
     * Checks if the simulation is in the completed step state After the
     * simulation has successfully completed a replication this property will be true
     */
    val isReplicationCompleted: Boolean
        get() = myReplicationProcess.isStepCompleted

    /**
     * Checks if the simulation is in the ended state. After the simulation has been ended this property will return true
     */
    val isEnded: Boolean
        get() = myReplicationProcess.isEnded

    /**
     * The simulation may end by a variety of means, this  checks
     * if the simulation ended because it ran all of its replications, true if all completed
     */
    val allReplicationsCompleted: Boolean
        get() = myReplicationProcess.allStepsCompleted

    /**
     * The simulation may end by a variety of means, this method checks
     * if the simulation ended because it was stopped, true if it was stopped via stop()
     */
    val stoppedByCondition: Boolean
        get() = myReplicationProcess.stoppedByCondition

    /**
     * The simulation may end by a variety of means, this method checks
     * if the simulation ended but was unfinished, not all replications were completed.
     */
    val isUnfinished: Boolean
        get() = myReplicationProcess.isUnfinished

    private fun addDefaultElements() {

    }

    override var mySpatialModel: SpatialModel? = Euclidean2DPlane()

    /**
     * This method can be used to ensure that all model elements within the
     * model use the same spatial model.
     *
     * @param model the spatial model
     */
    fun setSpatialModelForAllElements(model: SpatialModel) {
        //set the model's spatial model
        spatialModel = model
        // iterate through all elements and set their spatial model
        for (m in myModelElementMap.values) {
            m.spatialModel = model
        }
    }

    /**
     *  Causes the specified model element to be removed from the model regardless of
     *  its location within the model hierarchy. Any model elements attached to the
     *  supplied model element will also be removed.
     *
     * Recursively removes the model element and the children of the model
     * element and all their children, etc. The children will no longer have a
     * parent and will no longer have a model.  This can only be done when
     * the simulation that contains the model is not running.
     *
     * This method has very serious side effects. After invoking this method:
     *
     * 1) All children of the model element will have been removed from the
     * model.
     * 2) The model element will be removed from its parent's model,
     * element list and from the model. The getParentModelElement() method will
     * return null. In other words, the model element will no longer be connected
     * to a parent model element.
     * 3) The model element and all its children will no longer be
     * connected. In other words, there is no longer a parent/child relationship
     * between the model element and its former children.
     * 4) This model element and all of its children will no longer belong to a model.
     * 5) The removed elements are no longer part of their former model's model element map
     * 6) Warm up and timed update listeners are set to null
     * 7) Any reference to a spatial model is set to null
     * 8) All observers of the model element are detached
     * 9) All child model elements are removed. It will no longer have any children.
     *
     * Since it has been removed from the model, it and its children will no
     * longer participate in any of the standard model element actions, e.g.
     * initialize(), afterReplication(), etc.
     *
     *
     * Notes: 1) This method removes from the list of model elements. Thus, if a
     * client attempts to use this method, via code that is iterating the list a
     * concurrent modification exception will occur.
     * 2) The user is responsible for ensuring that other references to the model
     * element are correctly handled.  If references to the model element exist within
     * other data structures/collections then the user is responsible for appropriately
     * addressing those references. This is especially important for any observers
     * of the removed model element.  The observers will be notified that the model
     * element is being removed. It is up to the observer to correctly react to
     * the removal. If the observer is a subclass of ModelElementObserver then
     * implementing the removedFromModel() method can be used. If the observer is a
     * general Observer, then use REMOVED_FROM_MODEL to check if the element is being removed.
     */
    fun removeFromModel(element: ModelElement) {
        element.removeFromModel()
    }

    /**
     * @param option true means that streams will have their antithetic property set to true
     */
    fun antitheticOption(option: Boolean) {
        for (rs in myStreams) {
            rs.antithetic = option
        }
    }

    /**
     * Advances the streams, held by the model, n times. If n &lt;= 0, no
     * advancing occurs
     *
     * @param n the number of times to advance
     */
    fun advanceSubStreams(n: Int) {
        if (n <= 0) {
            return
        }
        for (i in 1..n) {
            advanceToNextSubStream()
        }
    }

    /**
     * Causes random number streams that have been added to the model to immediately
     * advance their random number sequence to the next sub-stream if the stream
     * permits advancement via the advanceToNextSubStreamOption.
     */
    fun advanceToNextSubStream() {
        for (rs in myStreams) {
            if (rs.advanceToNextSubStreamOption) {
                rs.advanceToNextSubStream()
            }
        }
    }

    /**
     * Causes random number streams that have been added to the model to immediately
     * reset their random number sequence to the beginning of their starting stream
     * if the stream permits resetting via the resetStartStreamOption
     */
    fun resetStartStream() {
        for (rs in myStreams) {
            if (rs.resetStartStreamOption) {
                rs.resetStartStream()
            }
        }
    }

    /**
     * Causes random number streams that have been added to the model to immediately
     * reset their random number sequence to the beginning of their current sub-stream.
     */
    fun resetStartSubStream() {
        for (rs in myStreams) {
            rs.resetStartSubStream()
        }
    }

    /**
     *
     * @return the controls for the model
     */
    fun controls(): Controls {
        if (!::myControls.isInitialized) {
            myControls = Controls(this)
        }
        return myControls
    }

    /**
     * Returns the response  associated with the name or null if named
     * element is not in the model. Note that this will also return ANY
     * instances of subclasses of Response (i.e. including TWResponse)
     *
     * @param name The name of the Response model element
     *
     * @return the associated Response, may be null if provided name
     * does not exist in the model
     */
    fun response(name: String): Response? {
        if (!myModelElementMap.containsKey(name)) {
            return null
        }
        val v = myModelElementMap[name]
        return if (v is Response) {
            v
        } else {
            null
        }
    }

    /**
     * Returns the TWResponse associated with the name or null if named
     * element is not in the model. Note that this will also return ANY
     * instances of subclasses of TWResponse
     *
     * @param name The name of the TWResponse model element
     *
     * @return the associated TWResponse, may be null if provided name does not exist in the model
     */
    fun timeWeightedResponse(name: String): TWResponse? {
        val r = response(name)
        if (r == null) {
            return null
        } else {
            return if (r is TWResponse) {
                r
            } else {
                null
            }
        }
    }

    /**
     * Returns the counter associated with the name or null if named
     * element is not in the model.
     *
     * @param name The name of the Counter model element
     *
     * @return the associated Counter, may be null if provided name
     * does not exist in the model
     */
    fun counter(name: String): Counter? {
        if (!myModelElementMap.containsKey(name)) {
            return null
        }
        val v = myModelElementMap[name]
        return if (v is Counter) {
            v
        } else {
            null
        }
    }

    /**
     * Removes the given model element from the Model's model element map. Any
     * child model elements of the supplied model element are also removed from
     * the map, until all elements below the given model element are removed.
     *
     * @param modelElement
     */
    internal fun removeFromModelElementMap(modelElement: ModelElement) {

        if (myModelElementMap.containsKey(modelElement.name)) {
            //	remove the associated model element from the map, if there
            myModelElementMap.remove(modelElement.name)
            if (modelElement is Response) {
                myResponses.remove(modelElement)
            }
            if (modelElement is Counter) {
                myCounters.remove(modelElement)
            }
            if (modelElement is RandomElementIfc) {
                myRandomElements.remove(modelElement as RandomElementIfc)
            }
            if (modelElement is Variable) {
                if (Variable::class == modelElement::class) {//TODO not 100% sure if only super type is removed
                    myVariables.remove(modelElement)
                }
            }
            modelElement.currentStatus = Status.MODEL_ELEMENT_REMOVED
            logger.trace { "Model: Removed model element ${modelElement.name} from the model with parent ${modelElement.myParentModelElement?.name}" }
            // remove any of the modelElement's children and so forth from the map
            val i: Iterator<ModelElement> = modelElement.getChildModelElementIterator()
            var m: ModelElement
            while (i.hasNext()) {
                m = i.next()
                removeFromModelElementMap(m)
            }
        }
    }

    internal fun addToModelElementMap(modelElement: ModelElement) {

        if (isRunning) {
            val sb = StringBuilder()
            sb.append("Attempted to add a ${modelElement::class.simpleName} : name = ")
            sb.append(modelElement.name)
            sb.append(", while the simulation was running.")
            logger.error { sb.toString() }
            throw IllegalStateException(sb.toString())
        }

        if (myModelElementMap.containsKey(modelElement.name)) {
            val sb = StringBuilder()
            sb.append("A ModelElement with the name: ")
            sb.append(modelElement.name)
            sb.append(" has already been added to the Model.")
            sb.appendLine()
            sb.append("Every model element must have a unique name!")
            logger.error { sb.toString() }
            throw IllegalArgumentException(sb.toString())
        }

        myModelElementMap[modelElement.name] = modelElement
        logger.trace { "Added model element ${modelElement.name} to the model with parent ${modelElement.myParentModelElement?.name}" }

        if (modelElement is Response) {
            myResponses.add(modelElement)
        }

        if (modelElement is Counter) {
            myCounters.add(modelElement)
        }

        if (modelElement is HistogramResponse){
            myHistograms.add(modelElement)
        }

        if (modelElement is IntegerFrequencyResponse){
            myFrequencies.add(modelElement)
        }

        if (modelElement is RandomElementIfc) {
            //TODO can't add stream control here. why? causes NullPointerException when trying to access the stream
            myRandomElements.add(modelElement as RandomElementIfc)
        }

        if (modelElement is Variable) {
            if (Variable::class == modelElement::class) {//TODO not 100% sure if only super type is removed
                myVariables.add(modelElement)
            }
        }

        modelElement.currentStatus = Status.MODEL_ELEMENT_ADDED
    }

    /**
     * Checks to see if the model element has been registered with the Model
     * using it's uniquely assigned name.
     *
     * @param modelElementName the name of the model element to check
     * @return true if contained
     */
    fun containsModelElement(modelElementName: String): Boolean {
        return myModelElementMap.containsKey(modelElementName)
    }

    /**
     * Returns the random variable associated with the name or null if named
     * element is not in the model. Note that this will also return ANY
     * instances of subclasses of RandomVariable
     *
     * @param name The name of the RandomVariable model element
     * @return the associated random variable, may be null if provided name does
     * not exist in the model
     */
    fun randomVariable(name: String): RandomVariable? {
        if (!myModelElementMap.containsKey(name)) {
            return null
        }
        val v: ModelElement? = myModelElementMap[name]
        return if (v is RandomVariable) {
            v
        } else {
            null
        }
    }

    /**
     *
     * @return a list containing all RandomVariables within the model
     */
    fun randomVariables(): List<RandomVariable> {
        val list: MutableList<RandomVariable> = mutableListOf()
        for (re in myRandomElements) {
            if (re is RandomVariable) {
                list.add(re)
            }
        }
        return list
    }

    /**
     * Returns the model element associated with the name. In some sense, this
     * model violates encapsulation for ModelElements, but since the client
     * knows the unique name of the ModelElement, we assume that the client
     * knows what they are doing.
     *
     * @param name The name of the model element, the name must not be null
     * @return The named ModelElement if it exists in the model by the supplied
     * name, null otherwise
     */
    fun getModelElement(name: String): ModelElement? {
        return if (!myModelElementMap.containsKey(name)) {
            null
        } else myModelElementMap[name]
    }

    /**
     * Returns the model element that has the provided unique id or null if not
     * found
     *
     * @param id the identifier getId() of the desired ModelElement
     * @return the ModelElement
     */
    fun getModelElement(id: Int): ModelElement? {
        for (entry in myModelElementMap.entries.iterator()) {
            if (entry.value.id == id) {
                return entry.value
            }
        }
        return null
    }

    /** The list is ordered parent-child, in the order of the model element hierarchy
     *
     * @return all the model elements in the model as a List
     */
    internal fun getModelElements(): List<ModelElement> {
        val list = mutableListOf<ModelElement>()
        getAllModelElements(list)
        return list
    }

    /**
     * Schedules the ending of the executive at the provided time
     *
     * @param time the time of the ending event, must be &gt; 0
     */
    internal fun scheduleEndOfReplicationEvent(time: Double) {
        require(time > 0.0) { "The time must be > 0.0" }
        if (executive.isEndEventScheduled()) {
            logger.info { "Already scheduled end of replication event for time = ${executive.endEvent!!.time} is being cancelled" }
            // already scheduled end event, cancel it
            executive.endEvent!!.cancel = true
        }
        // schedule the new time
        if (time.isFinite()) {
            logger.info { "Scheduling end of replication at time: $time" }
            executive.endEvent = EndEventAction().schedule(time)
        } else {
            logger.info { "Did not schedule end of replication event because time was $time" }
        }

    }

    private inner class EndEventAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            executive.stop("Scheduled end event occurred at time $time")
        }

        fun schedule(time: Double): KSLEvent<Nothing> {
            return schedule(time, null, KSLEvent.DEFAULT_END_REPLICATION_EVENT_PRIORITY, name = "End Replication")
        }
    }

    /** Counts the number of pre-order traversals of the model element tree and
     * labels each model element with the appropriate left and right traversal
     * count.  Called from Simulation in ReplicationExecutionProcess.initializeIterations()
     *
     * @return the number of traversals in the model element hierarchy
     */
    private fun markPreOrderTraversalModelElementHierarchy() {
        markPreOrderTraversalTree(0)
    }

    private fun setUpReplication() {

        // remove any marked model elements were added during previous replication
//        removeMarkedModelElements();

        // setup warm up period for the model
        individualElementWarmUpLength = lengthOfReplicationWarmUp

        // control streams for antithetic option
        handleAntitheticReplications()

        // do all model element beforeReplication() actions
        logger.info { "Executing before replication actions for model elements" }
        beforeReplicationActions()

        // schedule the end of the replication
        scheduleEndOfReplicationEvent(lengthOfReplication)

        // if necessary, initialize the model elements
        if (replicationInitializationOption) {
            // initialize the model and all model elements with initialize option on
            logger.info { "Executing initialize() actions for model elements" }
            initializeActions()
        }

        // allow model elements to register conditional actions
        logger.info { "Registering conditional actions for model elements" }
        registerConditionalActionsWithExecutive()

        // if monte carlo option is on, call the model element's monteCarlo() methods
        if (monteCarloOption) {
            // since monte carlo option was turned on, assume everyone wants to listen
            setMonteCarloOptionForModelElements(true)
            logger.info { "Executing monteCarloActions() actions for model elements" }
            monteCarloActions()
        }
    }

    private fun handleAntitheticReplications() {
        // handle antithetic replications
        if (antitheticOption) {
            logger.info { "Executing handleAntitheticReplications() setup" }
            if (currentReplicationNumber % 2 == 0) {
                // even number replication
                // return to beginning of sub-stream
                resetStartSubStream()
                // turn on antithetic sampling
                antitheticOption(true)
            } else  // odd number replication
                if (currentReplicationNumber > 1) {
                    // turn off antithetic sampling
                    antitheticOption(false)
                    // advance to next sub-stream
                    advanceToNextSubStream()
                }
        }
    }

    /**
     * Sets the reset start stream option for all RandomElementIfc in the model
     * to the supplied value, true is the default behavior. This method is used
     * by an experiment prior to beforeExperimentActions() being called Thus, any
     * RandomElementIfc must already have been created and attached to the model
     * elements
     *
     * @param option The option, true means to reset prior to each experiment
     */
    private fun setAllResetStartStreamOptions(option: Boolean) {
        for (rs in myStreams) {
            rs.resetStartStreamOption = option
        }
    }

    /**
     * Sets the advance to next sub stream option for all RandomElementIfc in the
     * model to the supplied value, true is the default behavior. True implies
     * that the sub-streams will be advanced at the end of the replication. This
     * method is used by an experiment prior to beforeExperimentActions() being called
     * Thus, any RandomElementIfc must already have been created and attached to
     * the model elements
     *
     * @param option The option, true means to reset prior to each replication
     */
    private fun setAllAdvanceToNextSubStreamOptions(option: Boolean) {
        for (rs in myStreams) {
            rs.advanceToNextSubStreamOption = option
        }
    }

    //called from simulation, so internal
    internal fun setUpExperiment() {
        logger.info { "Setting up experiment $experimentName for the simulation." }
        ModelElement.logger.info { "Setting up experiment $experimentName for the simulation." }
        executive.initializeCalendar()
        logger.info { "The executive was initialized prior to any experiments. Current time = $time" }
        executive.terminationWarningMsgOption = false
        markPreOrderTraversalModelElementHierarchy()
        // already should have reference to simulation
        advanceSubStreams(numberOfStreamAdvancesPriorToRunning)

        if (antitheticOption) {
            // make sure the streams are not reset after all replications are run
            setAllResetStartStreamOptions(false)
            // make sure that streams are not advanced to next sub-streams after each replication
            // antithetic option will control this (every other replication)
            setAllAdvanceToNextSubStreamOptions(false)
        } else {
            // tell the model to use the specifications from the experiment
            setAllResetStartStreamOptions(resetStartStreamOption)
            setAllAdvanceToNextSubStreamOptions(advanceNextSubStreamOption)
        }

        //TODO need to apply generic control types here someday
        if (hasExperimentalControls()) {
            val cMap: Map<String, Double> = experimentalControls
            // extract controls and apply them
            val k: Int = controls().setControlsFromMap(cMap)
            logger.info { "$k out of ${cMap.size} controls were applied to Model $name to setup the experiment." }
        }

        // if the user has asked for the parameters, then they may have changed
        // thus apply the possibly new parameters to set up the model
        if (myRVParameterSetter != null) {
            logger.info { "Parameters may have changed. Apply the parameters to the model." }
            myRVParameterSetter!!.applyParameterChanges(this)
            logger.info { "The parameter setter applied the parameter changes." }
        }

        // do all model element beforeExperiment() actions
        if (resetStartStreamOption) {
            logger.info { "Resetting random number streams to the beginning of their starting stream." }
            resetStartStream()
        }
        beforeExperimentActions()
    }

    internal fun runReplication() {
        if (maximumAllowedExecutionTimePerReplication > Duration.ZERO) {
            executive.maximumAllowedExecutionTime = maximumAllowedExecutionTimePerReplication
        }
        logger.info { "Initializing the executive" }
        ModelElement.logger.info { "Initializing the executive" }
        executive.initialize()
        logger.info { "The executive was initialized prior to the replication. Current time = $time" }
        logger.info { "Setting up the replications for model elements" }
        ModelElement.logger.info { "Setting up the replications for model elements" }
        setUpReplication()
        logger.info { "Executing the events" }
        ModelElement.logger.info { "Executing the events" }
        executive.executeAllEvents()
        logger.info { "The executive finished executing events. Current time = $time" }
        logger.info { "Performing end of replication actions for model elements" }
        ModelElement.logger.info { "Performing end of replication actions for model elements" }
        replicationEndedActions()
        if (advanceNextSubStreamOption) {
            logger.info { "Advancing random number streams to the next sub-stream" }
            advanceToNextSubStream()
        }
        logger.info { "Performing after replication actions for model elements" }
        ModelElement.logger.info { "Performing after replication actions for model elements" }
        afterReplicationActions()
    }

    internal fun endExperiment() {
        logger.info { "Performing after experiment actions for model elements" }
        ModelElement.logger.info { "Performing after experiment actions for model elements" }
        afterExperimentActions()
    }

    private inner class ReplicationProcess(name: String?) : IterativeProcess<ReplicationProcess>(name) {

        override fun initializeIterations() {
            super.initializeIterations()
            logger.info { "Starting the simulation for $simulationName" }
            logger.info { "$name simulating all $numberOfReplications replications of length $lengthOfReplication with warm up $lengthOfReplicationWarmUp ..." }
            logger.info { "$name Initializing the replications ..." }
            myExperiment.resetCurrentReplicationNumber()
            setUpExperiment()
            if (repLengthWarningMessageOption) {
                if (lengthOfReplication.isInfinite()) {
                    if (maximumAllowedExecutionTimePerReplication == Duration.ZERO) {
                        val sb = StringBuilder()
                        sb.append("Model: Preparing to run replications for model = ${model.simulationName}, experiment ${model.experimentName}:")
                        sb.appendLine()
                        sb.append("The experiment has an infinite horizon for each replication.")
                        sb.appendLine()
                        sb.append("There was no maximum real-clock execution time specified.")
                        sb.appendLine()
                        sb.append("The user is responsible for ensuring that the replications are stopped.")
                        sb.appendLine()
                        logger.warn { sb.toString() }
                        println(sb.toString())
                        System.out.flush()
                    }
                }
            }
        }

        override fun endIterations() {
            endExperiment()
            logger.info { "$name completed $numberOfReplications replications out of $numberOfReplications." }
            logger.info { "Ended the simulation for $simulationName" }
            super.endIterations()
        }

        override fun hasNextStep(): Boolean {
            return hasMoreReplications()
        }

        override fun nextStep(): ReplicationProcess? {
            return if (!hasNextStep()) {
                null
            } else this
        }

        override fun runStep() {
            myCurrentStep = nextStep()
            myExperiment.incrementCurrentReplicationNumber()
            logger.info { "Running replication $currentReplicationNumber of $numberOfReplications replications" }
            ModelElement.logger.info { "Running replication $currentReplicationNumber of $numberOfReplications replications" }
            model.runReplication()
            logger.info { "Ended replication $currentReplicationNumber of $numberOfReplications replications" }
            ModelElement.logger.info { "Ended replication $currentReplicationNumber of $numberOfReplications replications" }
            if (garbageCollectAfterReplicationFlag) {
                System.gc()
            }
        }

    }

    override val numChunks: Int
        get() = myExperiment.numChunks

    override val runName: String
        get() = myExperiment.runName

    override val repIdRange: IntRange
        get() = myExperiment.repIdRange

    override var runErrorMsg: String
        get() = myExperiment.runErrorMsg
        set(value) {
            myExperiment.runErrorMsg = value
        }

    override val experimentId: Int
        get() = myExperiment.experimentId

    override var experimentName: String
        get() = myExperiment.experimentName
        set(value) {
            myExperiment.experimentName = value
        }

    override var startingRepId: Int
        get() = myExperiment.startingRepId
        set(value) {
            myExperiment.startingRepId = value
        }

    override val currentReplicationId: Int
        get() = myExperiment.currentReplicationId

    override var numberOfReplications: Int
        get() = myExperiment.numberOfReplications
        set(value) {
            myExperiment.numberOfReplications = value
        }

    override fun numberOfReplications(numReps: Int, antitheticOption: Boolean) {
        myExperiment.numberOfReplications(numReps, antitheticOption)
    }

    override var lengthOfReplication: Double
        get() = myExperiment.lengthOfReplication
        set(value) {
            myExperiment.lengthOfReplication = value
        }

    override var lengthOfReplicationWarmUp: Double
        get() = myExperiment.lengthOfReplicationWarmUp
        set(value) {
            myExperiment.lengthOfReplicationWarmUp = value
        }

    override var replicationInitializationOption: Boolean
        get() = myExperiment.replicationInitializationOption
        set(value) {
            myExperiment.replicationInitializationOption = value
        }

    override var maximumAllowedExecutionTimePerReplication: Duration
        get() = myExperiment.maximumAllowedExecutionTimePerReplication
        set(value) {
            myExperiment.maximumAllowedExecutionTimePerReplication = value
        }

    override var resetStartStreamOption: Boolean
        get() = myExperiment.resetStartStreamOption
        set(value) {
            myExperiment.resetStartStreamOption = value
        }

    override var advanceNextSubStreamOption: Boolean
        get() = myExperiment.advanceNextSubStreamOption
        set(value) {
            myExperiment.advanceNextSubStreamOption = value
        }

    override val antitheticOption: Boolean
        get() = myExperiment.antitheticOption

    override var numberOfStreamAdvancesPriorToRunning: Int
        get() = myExperiment.numberOfStreamAdvancesPriorToRunning
        set(value) {
            myExperiment.numberOfStreamAdvancesPriorToRunning = value
        }

    override var garbageCollectAfterReplicationFlag: Boolean
        get() = myExperiment.garbageCollectAfterReplicationFlag
        set(value) {
            myExperiment.garbageCollectAfterReplicationFlag = value
        }

    override var experimentalControls: Map<String, Double>
        get() = myExperiment.experimentalControls
        set(value) {
            myExperiment.experimentalControls = value
        }

    override val currentReplicationNumber
        get() = myExperiment.currentReplicationNumber

    override fun hasExperimentalControls() = myExperiment.hasExperimentalControls()

    override fun hasMoreReplications() = myExperiment.hasMoreReplications()

    override fun changeRunParameters(runParameters: ExperimentRunParametersIfc) {
        myExperiment.changeRunParameters(runParameters)
    }

    override fun experimentInstance(): Experiment = myExperiment.experimentInstance()

    /**
     * Returns true if additional replications need to be run
     *
     * @return true if additional replications need to be run
     */
    fun hasNextReplication(): Boolean {
        return myReplicationProcess.hasNextStep()
    }

    /**
     * Initializes the simulation in preparation for running
     */
    fun initializeReplications() {
        myReplicationProcess.initialize()
    }

    /**
     * Runs the next replication if there is one
     */
    fun runNextReplication() {
        myReplicationProcess.runNext()
    }

    /** A convenience method for running a simulation
     *
     * @param expName the name of the experiment
     * @param numReps the number of replications
     * @param runLength the length of the simulation replication
     * @param warmUp the length of the warmup period
     */
    fun simulate(numReps: Int = 1, runLength: Double, warmUp: Double = 0.0, expName: String? = null) {
        if (expName != null) {
            experimentName = expName
        }
        numberOfReplications = numReps
        lengthOfReplication = runLength
        lengthOfReplicationWarmUp = warmUp
        simulate()
    }

    /**
     * Runs all remaining replications based on the current settings
     */
    fun simulate() {
        if (autoCSVReports){
            turnOnCSVStatisticalReports()
        } else {
            turnOffCSVStatisticalReports()
        }
        myReplicationProcess.run()
        if (autoPrintSummaryReport) {
            print()
        }
    }

    /**
     *  Prints basic text results to the console.
     *  @param histAndFreq If true, any histogram and integer frequency results are also printed.
     *  The default is true.
     */
    fun print(histAndFreq: Boolean = true) {
        println()
        println(this)
        println()
        simulationReporter.printHalfWidthSummaryReport()
        println()
        if (histAndFreq){
            println(simulationReporter.histogramTextResults())
            println(simulationReporter.frequencyTextResults())
        }
    }

    /**
     * Causes the simulation to end after the current replication is completed
     *
     * @param msg A message to indicate why the simulation was stopped
     */
    fun endSimulation(msg: String? = null) {
        myReplicationProcess.end(msg)
    }

//    /**
//     * Causes the simulation to stop the current replication and not complete any additional replications
//     *
//     * @param msg A message to indicate why the simulation was stopped
//     */
//    private fun stopSimulation(msg: String?) {
//        myReplicationProcess.stop(msg)
//    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("Name: ")
        sb.append(simulationName)
        sb.appendLine()
        sb.append("Last Executive stopping message: ")
        sb.appendLine()
        sb.append("\t")
        sb.append(executive.stoppingMessage)
        sb.appendLine()
        sb.append("Replication Process Information:")
        sb.appendLine()
        sb.append("-------------------------------")
        sb.appendLine()
        sb.append(myReplicationProcess)
        sb.appendLine()
        sb.append("-------------------------------")
        sb.appendLine()
        sb.append(myExperiment.toString())
        sb.appendLine()
        sb.append("-------------------------------")
//        sb.appendLine()
//        sb.append(myExecutive)
        return sb.toString()
    }

    /**
     * The responses as a list of StatisticIfc
     *
     * @return a list of response variables and counters
     */
    val listOfAcrossReplicationStatistics: List<StatisticIfc>
        get() {
            val stats: MutableList<StatisticIfc> = ArrayList()
            for (r in myResponses) {
                val stat = r.acrossReplicationStatistic
                if (r.defaultReportingOption) {
                    stats.add(stat)
                }
            }
            for (c in myCounters) {
                val stat = c.acrossReplicationStatistic
                if (c.defaultReportingOption) {
                    stats.add(stat)
                }
            }
            return stats
        }

    /**
     *   Checks if the supplied set of strings are valid inputs (controls or random variable
     *   parameters).
     *
     *  For controls, by default, the key to associate with the value is the model element's name
     *  concatenated with the property that was annotated with the control.  For example, if
     *  the resource had name Worker and annotated property initialCapacity, then the key
     *  will be "Worker.initialCapacity". Note the use of the "." character to separate
     *  the model element name and the property name.  Since, the KSL model element naming
     *  convention require unique names for each model element, the key will be unique for the control.
     *  However, the model element name may be a very long string depending on your approach
     *  to naming the model elements. The name associated with each control can be inspected by
     *  asking the model for its controls via model.controls() and then using the methods on the Controls
     *  class for the names. The controlsMapAsJsonString() or asMap() functions are especially helpful
     *  for this purpose.
     *
     *  For the parameters associated with random variables, the naming convention is different.
     *  Again, the model element name is used as part of the identifier, then the value of
     *  rvParamConCatString from the companion object is concatenated between the name of the
     *  model element and the name of its parameter.  For example, suppose there is a
     *  random variable that has been named ServiceTimeRV that is exponentially distributed.
     *  Also assume that rvParamConCatString is ".", which is its default value. Then,
     *  to access the mean of the service time random variable, we use "ServiceTimeRV.mean".
     *  Thus, it is important to note the name of the random variable within the model and the
     *  default names used by the KSL for the random variable parameters.  When random variables are
     *  not explicitly named by the modeler, the KSL will automatically provide a default
     *  unique name. Thus, if you plan to control a specific random variable's parameters, you
     *  should strongly consider providing an explicit name. To get the names (and current values)
     *  of the random variable parameters, you can print out the toString() method of the
     *  RVParameterSetter class after obtaining it from the model via the model's rvParameterSetter
     *  property.
     *
     *  @param inputKeys the set of keys to check
     *  @param conCatString the character used to concatenate random variables with their parameters.
     *  By default, this is "."
     *  @return true if all provided input keys are valid
     */
    fun validateInputKeys(inputKeys: Set<String>, conCatString: Char = rvParamConCatChar): Boolean {
        val rvs = RVParameterSetter(this)
        val controls = Controls(this)
        for (key in inputKeys) {
            if (controls.hasControl(key)) {
                continue
            } else {
                // not a control, check for parameter
                val rvKeys = RVParameterSetter.splitFlattenedRVKey(key, conCatString)
//                println(rvKeys.joinToString())
                if (rvs.containsParameter(rvKeys[0], rvKeys[1])) {
                    // it is a parameter
                    continue
                } else {
                    // not a control and not a parameter
                    logger.info { "The input key '$key' was not a valid control or rv parameter" }
                    return false
                }
            }
        }
        // if we get here, all have validated
        return true
    }

    companion object {
        /**
         * Used to assign unique enum constants
         */
        private var myEnumCounter_ = 0

        val nextEnumConstant: Int
            get() = ++myEnumCounter_

        /**
         *
         * @return a comparator that compares based on getId()
         */
        val modelElementComparator: Comparator<ModelElement>
            get() = ModelElementComparator()

        /**
         * A global logger for logging
         */
        val logger = KotlinLogging.logger {}
    }
}

