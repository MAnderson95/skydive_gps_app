package com.ma.skydivegps.data

object FlightLogger {

    private val points = mutableListOf<FlightPoint>()

    fun addPoint(point: FlightPoint) {
        points.add(point)
    }

    fun getPoints(): List<FlightPoint> {
        return points.toList()
    }

    fun clear() {
        points.clear()
    }
}