package fr.arichard.upupup.mission

import kotlin.random.Random

/** Generates the arithmetic problems for the math wake-up mission. */
object MathMission {

    const val PROBLEM_COUNT = 3

    data class Problem(val text: String, val answer: Int)

    /** [difficulty] is 1 (easy), 2 (medium) or 3 (hard). */
    fun generate(difficulty: Int, random: Random = Random.Default): Problem = when (difficulty) {
        1 -> {
            val a = random.nextInt(2, 30)
            val b = random.nextInt(2, 30)
            Problem("$a + $b", a + b)
        }
        2 -> {
            val a = random.nextInt(10, 100)
            val b = random.nextInt(10, 100)
            val c = random.nextInt(2, 10)
            Problem("$a + $b × $c", a + b * c)
        }
        else -> {
            val a = random.nextInt(100, 1000)
            val b = random.nextInt(10, 100)
            val c = random.nextInt(11, 30)
            Problem("$a + $b × $c", a + b * c)
        }
    }
}
