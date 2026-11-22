package io.github.yamichi77.aurawalkinfo.session

fun interface Clock {
    fun elapsedRealtimeMillis(): Long
}
