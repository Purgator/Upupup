package fr.arichard.upupup

import fr.arichard.upupup.mission.MathMission
import fr.arichard.upupup.mission.MemoryMission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MissionTest {

    @Test
    fun `memory sequence length scales with difficulty`() {
        assertEquals(3, MemoryMission.sequenceLength(1))
        assertEquals(5, MemoryMission.sequenceLength(2))
        assertEquals(7, MemoryMission.sequenceLength(3))
    }

    @Test
    fun `memory sequence has no consecutive repeats and stays in grid`() {
        repeat(50) { seed ->
            val sequence = MemoryMission.generate(3, Random(seed))
            sequence.forEach { assertTrue(it in 0 until MemoryMission.GRID_SIZE) }
            sequence.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
        }
    }

    @Test
    fun `math answers match their problems across difficulties`() {
        repeat(50) { seed ->
            for (difficulty in 1..3) {
                val problem = MathMission.generate(difficulty, Random(seed))
                // Recompute from the rendered text: "a + b" or "a + b × c".
                val numbers = Regex("\\d+").findAll(problem.text).map { it.value.toInt() }.toList()
                val expected = when (numbers.size) {
                    2 -> numbers[0] + numbers[1]
                    else -> numbers[0] + numbers[1] * numbers[2]
                }
                assertEquals(expected, problem.answer)
            }
        }
    }
}
