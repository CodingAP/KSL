/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.general.models

import ksl.examples.book.chapter6.DriveThroughPharmacy
import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

class TestDelay(parent: ModelElement) : ProcessModel(parent, null) {

   // val resource: ResourceWithQ = ResourceWithQ(this, "test resource")

    private inner class Customer: Entity() {
        val aDelay : KSLProcess = process("test") {
            println("time = $time before the first delay in ${this@Customer}")
            delay(10.0, suspensionName = "Delay-for-10")
            println("time = $time after the first delay in ${this@Customer}")
            println("time = $time before the second delay in ${this@Customer}")
            delay(20.0, suspensionName = "Delay-for-20")
            println("time = $time after the second delay in ${this@Customer}")
        }

        val seizeTest: KSLProcess = process {
       //     val a  = seize(resource)
            delay(10.0)
       //     release(a)
        }
    }

    private var customer: Customer? = null

    override fun initialize() {
        val e = Customer()
        customer = e
        activate(e.aDelay)
    }

}

fun main(){
    val m = Model()
    val dtp = TestDelay(m)

    m.numberOfReplications = 2
    m.lengthOfReplication = 50.0
    m.simulate()
    m.print()
}