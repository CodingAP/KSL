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
import ksl.utilities.countLessEqualTo
import ksl.utilities.distributions.fitting.EstimationResult
import ksl.utilities.random.rvariable.RVParametersTypeIfc
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.LognormalRVParameters
import ksl.utilities.statistic.MVBSEstimatorIfc
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc
import ksl.utilities.statistics
import kotlin.math.exp
import kotlin.math.ln

/**
 *   Takes the natural logarithm of the data and then estimates
 *   the mean and variance of the associated normal distribution.
 *   Then the parameters are converted to the mean and variance of
 *   the lognormal distribution.  The supplied data must be strictly
 *   positive and their must be at least 2 observations.
 */
object LognormalMLEParameterEstimator : ParameterEstimatorIfc,
    MVBSEstimatorIfc, IdentityIfc by Identity("LognormalMLEParameterEstimator")  {

    override val rvType: RVParametersTypeIfc
        get() = RVType.Lognormal

    override val checkRange: Boolean = true

    override val names: List<String> = listOf("mean", "variance")

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
            er.parameters.doubleParameter("mean"),
            er.parameters.doubleParameter("variance")
        )
    }

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
        if (data.countLessEqualTo(0.0) > 0) {
            return EstimationResult(
                originalData = data,
                statistics = statistics,
                message = "Cannot fit lognormal distribution when some observations are <= 0.0",
                success = false,
                estimator = this
            )
        }
        // transform to normal on ln scale
        val lnData = DoubleArray(data.size) { ln(data[it]) }
        // estimate parameters of normal distribution
        val s = lnData.statistics()
        val mu = s.average
        val sigma2 = s.variance
        // compute the parameters of the log-normal distribution
        val mean = exp(mu + (sigma2 / 2.0))
        val variance = exp(2.0 * mu + sigma2) * (exp(sigma2) - 1.0)
        val parameters = LognormalRVParameters()
        parameters.changeDoubleParameter("mean", mean)
        parameters.changeDoubleParameter("variance", variance)
        return EstimationResult(
            originalData = data,
            statistics = statistics,
            parameters = parameters,
            message = "The lognormal parameters were estimated successfully using a MLE technique",
            success = true,
            estimator = this
        )
    }
}