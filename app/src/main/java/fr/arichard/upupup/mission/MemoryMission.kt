package fr.arichard.upupup.mission

import kotlin.random.Random

/** Generates the tile sequence for the Simon-style memory mission. */
object MemoryMission {

    const val GRID_SIZE = 9 // 3×3

    /** Sequence length by difficulty 1..3: 3, 5, 7 tiles. */
    fun sequenceLength(difficulty: Int): Int = 1 + 2 * difficulty.coerceIn(1, 3)

    /** Random tile indices in 0 until [GRID_SIZE]; consecutive repeats avoided. */
    fun generate(difficulty: Int, random: Random = Random.Default): List<Int> {
        val sequence = mutableListOf<Int>()
        repeat(sequenceLength(difficulty)) {
            var next: Int
            do {
                next = random.nextInt(GRID_SIZE)
            } while (next == sequence.lastOrNull())
            sequence.add(next)
        }
        return sequence
    }
}
