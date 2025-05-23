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

package ksl.utilities.distributions.fitting.estimators

import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.distributions.fitting.EstimationResult
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.isAllEqual
import ksl.utilities.random.rvariable.RVParametersTypeIfc
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.UniformRVParameters
import ksl.utilities.statistic.MVBSEstimatorIfc
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc

/**
 *   Uses the minimum unbiased estimators based on the order statistics.
 *   See: 1. Castillo E, Hadi AS. A method for estimating parameters and quantiles of
 *   distributions of continuous random variables. Computational Statistics & Data Analysis.
 *   1995 Oct;20(4):421–39.
 *   There must be at least two observations and the observations cannot all be the same.
 *
 */
object UniformParameterEstimator : ParameterEstimatorIfc,
    MVBSEstimatorIfc, IdentityIfc by Identity("UniformParameterEstimator") {

    override val rvType: RVParametersTypeIfc
        get() = RVType.Uniform

    override val checkRange: Boolean = false

    override val names: List<String> = listOf("min", "max")

    /**
     *  If the estimation process is not successful, then an
     *  empty array is returned.
     */
    override fun estimate(data: DoubleArray): DoubleArray {
        val er = estimateParameters(data, Statistic(data))
        if (!er.success || er.parameters == null) {
            return doubleArrayOf()
        }
        return doubleArrayOf(
            er.parameters.doubleParameter("min"),
            er.parameters.doubleParameter("max")
        )
    }

    //TODO consider restricting to positive values
    override fun estimateParameters(data: DoubleArray, statistics: StatisticIfc): EstimationResult {
        if (data.size < 2){
            return EstimationResult(
                originalData = data,
                statistics = statistics,
                message = "There must be at least two observations",
                success = false,
                estimator = this
            )
        }
        if (data.isAllEqual()){
            return EstimationResult(
                originalData = data,
                statistics = statistics,
                message = "The observations were all equal.",
                success = false,
                estimator = this
            )
        }
        val interval = PDFModeler.rangeEstimate(statistics.min, statistics.max, data.size)
        val parameters = UniformRVParameters()
        parameters.changeDoubleParameter("min", interval.lowerLimit)
        parameters.changeDoubleParameter("max", interval.upperLimit)
        return EstimationResult(
            originalData = data,
            statistics = statistics,
            parameters = parameters,
            message = "The uniform parameters were estimated successfully.",
            success = true,
            estimator = this
        )
    }
}