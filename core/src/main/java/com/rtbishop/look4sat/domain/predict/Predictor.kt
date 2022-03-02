/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2022 Arty Bishop (bishop.arty@gmail.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.domain.predict

import com.rtbishop.look4sat.domain.model.SatRadio
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext

class Predictor(private val predictorDispatcher: CoroutineDispatcher) {

    private val _calculatedPasses = MutableSharedFlow<List<SatPass>>(replay = 1)
    val calculatedPasses: SharedFlow<List<SatPass>> = _calculatedPasses

    suspend fun getSatPos(sat: Satellite, pos: GeoPos, time: Long): SatPos {
        return withContext(predictorDispatcher) { sat.getPosition(pos, time) }
    }

    suspend fun getSatTrack(sat: Satellite, pos: GeoPos, start: Long, end: Long): List<SatPos> {
        return withContext(predictorDispatcher) {
            val positions = mutableListOf<SatPos>()
            var currentTime = start
            while (currentTime < end) {
                positions.add(sat.getPosition(pos, currentTime))
                currentTime += 15000
            }
            positions
        }
    }

    suspend fun processRadios(sat: Satellite, pos: GeoPos, radios: List<SatRadio>, time: Long): List<SatRadio> {
        return withContext(predictorDispatcher) {
            val satPos = sat.getPosition(pos, time)
            val copiedList = radios.map { it.copy() }
            copiedList.forEach { transmitter ->
                transmitter.downlink?.let {
                    transmitter.downlink = satPos.getDownlinkFreq(it)
                }
                transmitter.uplink?.let {
                    transmitter.uplink = satPos.getUplinkFreq(it)
                }
            }
            copiedList.map { it.copy() }
        }
    }

    suspend fun processPasses(passList: List<SatPass>, time: Long): List<SatPass> {
        return withContext(predictorDispatcher) {
            passList.forEach { pass ->
                if (!pass.isDeepSpace) {
                    val timeStart = pass.aosTime
                    if (time > timeStart) {
                        val deltaNow = time.minus(timeStart).toFloat()
                        val deltaTotal = pass.losTime.minus(timeStart).toFloat()
                        pass.progress = ((deltaNow / deltaTotal) * 100).toInt()
                    }
                }
            }
            passList.filter { pass -> pass.progress < 100 }.map { it.copy() }
        }
    }

    suspend fun forceCalculation(
        satList: List<Satellite>,
        pos: GeoPos,
        time: Long,
        hoursAhead: Int = 8,
        minElevation: Double = 16.0
    ) {
        if (satList.isEmpty()) {
            _calculatedPasses.emit(emptyList())
        } else {
            withContext(predictorDispatcher) {
                val allPasses = mutableListOf<SatPass>()
                satList.forEach { satellite ->
                    allPasses.addAll(satellite.getPasses(pos, time, hoursAhead))
                }
                _calculatedPasses.emit(allPasses.filter(time, hoursAhead, minElevation))
            }
        }
    }

    private fun Satellite.getPasses(pos: GeoPos, time: Long, hours: Int): List<SatPass> {
        val passes = mutableListOf<SatPass>()
        val endDate = time + hours * 60L * 60L * 1000L
        val quarterOrbitMin = (this.orbitalPeriod / 4.0).toInt()
        var startDate = time
        var shouldRewind = true
        var lastAosDate: Long
        var count = 0
        if (this.willBeSeen(pos)) {
            if (this.data.isDeepspace) {
                passes.add(getGeoPass(this, pos, time))
            } else {
                do {
                    if (count > 0) shouldRewind = false
                    val pass = getLeoPass(this, pos, startDate, shouldRewind)
                    lastAosDate = pass.aosTime
                    passes.add(pass)
                    startDate = pass.losTime + (quarterOrbitMin * 3) * 60L * 1000L
                    count++
                } while (lastAosDate < endDate)
            }
        }
        return passes
    }

    private fun List<SatPass>.filter(time: Long, hoursAhead: Int, minElev: Double): List<SatPass> {
        val timeFuture = time + (hoursAhead * 60L * 60L * 1000L)
        return this.filter { it.losTime > time }
            .filter { it.aosTime < timeFuture }
            .filter { it.maxElevation > minElev }
            .sortedBy { it.aosTime }
    }

    private fun getGeoPass(sat: Satellite, pos: GeoPos, time: Long): SatPass {
        val satPos = sat.getPosition(pos, time)
        val aos = time - 24 * 60L * 60L * 1000L
        val los = time + 24 * 60L * 60L * 1000L
        val tca = (aos + los) / 2
        val az = Math.toDegrees(satPos.azimuth)
        val elev = Math.toDegrees(satPos.elevation)
        val alt = satPos.altitude
        return SatPass(aos, az, los, az, tca, az, alt, elev, sat)
    }

    private fun getLeoPass(sat: Satellite, pos: GeoPos, time: Long, rewind: Boolean): SatPass {
        val quarterOrbitMin = (sat.orbitalPeriod / 4.0).toInt()
        var calendarTimeMillis = time
        var elevation: Double
        var maxElevation = 0.0
        var alt = 0.0
        var tcaAz = 0.0
        // rewind 1/4 of an orbit
        if (rewind) calendarTimeMillis += -quarterOrbitMin * 60L * 1000L

        var satPos = sat.getPosition(pos, calendarTimeMillis)
        if (satPos.elevation > 0.0) {
            // move forward in 30 second intervals until the sat goes below the horizon
            do {
                calendarTimeMillis += 30 * 1000L
                satPos = sat.getPosition(pos, calendarTimeMillis)
            } while (satPos.elevation > 0.0)
            // move forward 3/4 of an orbit
            calendarTimeMillis += quarterOrbitMin * 3 * 60L * 1000L
        }

        // find the next time sat comes above the horizon
        do {
            calendarTimeMillis += 60L * 1000L
            satPos = sat.getPosition(pos, calendarTimeMillis)
            elevation = satPos.elevation
            if (elevation > maxElevation) {
                maxElevation = elevation
                alt = satPos.altitude
                tcaAz = Math.toDegrees(satPos.azimuth)
            }
        } while (satPos.elevation < 0.0)

        // refine to 3 seconds
        calendarTimeMillis += -60L * 1000L
        do {
            calendarTimeMillis += 3L * 1000L
            satPos = sat.getPosition(pos, calendarTimeMillis)
            elevation = satPos.elevation
            if (elevation > maxElevation) {
                maxElevation = elevation
                alt = satPos.altitude
                tcaAz = Math.toDegrees(satPos.azimuth)
            }
        } while (satPos.elevation < 0.0)

        val aos = satPos.time
        val aosAz = Math.toDegrees(satPos.azimuth)

        // find when sat goes below
        do {
            calendarTimeMillis += 30L * 1000L
            satPos = sat.getPosition(pos, calendarTimeMillis)
            elevation = satPos.elevation
            if (elevation > maxElevation) {
                maxElevation = elevation
                alt = satPos.altitude
                tcaAz = Math.toDegrees(satPos.azimuth)
            }
        } while (satPos.elevation > 0.0)

        // refine to 3 seconds
        calendarTimeMillis += -30L * 1000L
        do {
            calendarTimeMillis += 3L * 1000L
            satPos = sat.getPosition(pos, calendarTimeMillis)
            elevation = satPos.elevation
            if (elevation > maxElevation) {
                maxElevation = elevation
                alt = satPos.altitude
                tcaAz = Math.toDegrees(satPos.azimuth)
            }
        } while (satPos.elevation > 0.0)

        val los = satPos.time
        val losAz = Math.toDegrees(satPos.azimuth)
        val tca = (aos + los) / 2
        val elev = Math.toDegrees(maxElevation)
        return SatPass(aos, aosAz, los, losAz, tca, tcaAz, alt, elev, sat)
    }
}
