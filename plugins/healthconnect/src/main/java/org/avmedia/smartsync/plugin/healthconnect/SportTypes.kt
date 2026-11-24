package org.avmedia.smartsync.plugin.healthconnect

import androidx.health.connect.client.records.ExerciseSessionRecord as E

/** The watch's sport name to a Health Connect exercise type; anything not known is "other workout". */
object SportTypes {
    private val rules: List<Pair<List<String>, Int>> = listOf(
        listOf("treadmill") to E.EXERCISE_TYPE_RUNNING_TREADMILL,
        listOf("run", "jog") to E.EXERCISE_TYPE_RUNNING,
        listOf("hik", "trek", "mountain") to E.EXERCISE_TYPE_HIKING,
        listOf("walk") to E.EXERCISE_TYPE_WALKING,
        listOf("indoor bike", "indoor cycl", "stationary", "spin") to E.EXERCISE_TYPE_BIKING_STATIONARY,
        listOf("bike", "cycl", "biking") to E.EXERCISE_TYPE_BIKING,
        listOf("open water") to E.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
        listOf("swim", "pool") to E.EXERCISE_TYPE_SWIMMING_POOL,
        listOf("rowing machine", "indoor row") to E.EXERCISE_TYPE_ROWING_MACHINE,
        listOf("row") to E.EXERCISE_TYPE_ROWING,
        listOf("yoga") to E.EXERCISE_TYPE_YOGA,
        listOf("hiit", "interval") to E.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING,
        listOf("strength", "weight", "muscle") to E.EXERCISE_TYPE_STRENGTH_TRAINING,
        listOf("elliptical", "cross trainer") to E.EXERCISE_TYPE_ELLIPTICAL,
        listOf("stair", "step machine") to E.EXERCISE_TYPE_STAIR_CLIMBING,
    )

    fun exerciseType(sportName: String): Int {
        val name = sportName.lowercase()
        return rules.firstOrNull { (words, _) -> words.any { it in name } }?.second ?: E.EXERCISE_TYPE_OTHER_WORKOUT
    }
}
