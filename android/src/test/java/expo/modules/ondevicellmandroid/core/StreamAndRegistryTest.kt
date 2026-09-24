//
//  StreamAndRegistryTest.kt
//  OnDeviceLlm — Android
//
//  The delta tripwire and the cancellation registry.
//
//  Neither can be verified end to end without hardware — whether cancelling a
//  Job stops AICore inference is exactly the question the wave-2 spike exists
//  to answer — but both have real device-independent behaviour worth pinning.
//

package expo.modules.ondevicellmandroid.core

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamAccumulatorTest {
  @Test
  fun `true deltas are never flagged and concatenate to the whole text`() {
    val accumulator = StreamAccumulator()

    assertFalse(accumulator.accept("Hello"))
    assertFalse(accumulator.accept(", "))
    assertFalse(accumulator.accept("world"))

    assertFalse(accumulator.suspectedSnapshotStream)
    assertEquals("Hello, world", accumulator.joined)
    assertEquals(3, accumulator.chunkCount)
  }

  @Test
  fun `a cumulative stream trips the wire on the first repeated prefix`() {
    // The signature of Apple's snapshot behaviour arriving where the bytecode
    // says deltas should be. Forwarded untouched — the flag is a report, not a
    // repair — so the anomaly reaches the consumer as `reset` instead of
    // becoming a mysterious duplicated-text bug report.
    val accumulator = StreamAccumulator()

    assertFalse(accumulator.accept("Hello"))
    assertTrue(accumulator.accept("Hello, world"))
    assertTrue(accumulator.suspectedSnapshotStream)
  }

  @Test
  fun `a model repeating itself exactly is not mistaken for a snapshot`() {
    // A chunk equal to what came before is not longer than it, so it does not
    // trip. Only a strict extension of everything so far is suspicious.
    val accumulator = StreamAccumulator()

    accumulator.accept("ab")
    assertFalse(accumulator.accept("ab"))
    assertFalse(accumulator.suspectedSnapshotStream)
  }

  @Test
  fun `the first chunk can never trip the wire`() {
    val accumulator = StreamAccumulator()
    assertFalse(accumulator.accept("anything at all"))
  }

  @Test
  fun `an empty chunk is counted and changes nothing`() {
    val accumulator = StreamAccumulator()

    accumulator.accept("a")
    assertFalse(accumulator.accept(""))
    assertEquals("a", accumulator.joined)
    assertEquals(2, accumulator.chunkCount)
  }
}

class RequestRegistryTest {
  @Test
  fun `cancel cancels the registered job and forgets it`() {
    val registry = RequestRegistry()
    val job = Job()

    registry.register("r1", job)
    assertEquals(1, registry.activeCount)

    assertTrue(registry.cancel("r1"))
    assertTrue(job.isCancelled)
    assertEquals(0, registry.activeCount)
  }

  @Test
  fun `cancelling an unknown id is false, not an error`() {
    // The normal outcome of a cancel racing a natural completion. JavaScript
    // cannot know the request already finished.
    assertFalse(RequestRegistry().cancel("never-registered"))
  }

  @Test
  fun `re-registering an id cancels the displaced request`() {
    // Leaking a running generation would keep the NPU busy with work nobody is
    // listening to — and on a device with a per-app battery quota that is a
    // real cost, not just waste.
    val registry = RequestRegistry()
    val first = Job()
    val second = Job()

    registry.register("r1", first)
    registry.register("r1", second)

    assertTrue(first.isCancelled)
    assertFalse(second.isCancelled)
    assertEquals(1, registry.activeCount)
  }

  @Test
  fun `finish drops a request without cancelling it`() {
    val registry = RequestRegistry()
    val job = Job()

    registry.register("r1", job)
    registry.finish("r1")

    assertFalse(job.isCancelled)
    assertEquals(0, registry.activeCount)
  }

  @Test
  fun `cancelAll stops every request`() {
    // Module teardown: a JS reload leaves generations running otherwise, with
    // nothing listening for their events.
    val registry = RequestRegistry()
    val jobs = (1..5).map { Job().also { job -> registry.register("r$it", job) } }

    registry.cancelAll()

    assertTrue(jobs.all { it.isCancelled })
    assertEquals(0, registry.activeCount)
  }

  @Test
  fun `concurrent registration and cancellation stays consistent`() {
    // Concurrent streams, their cancellations and their completions all mutate
    // this map from arbitrary dispatcher threads.
    val registry = RequestRegistry()
    val jobs = (0 until 200).map { Job() }
    val threads = jobs.mapIndexed { index, job ->
      Thread {
        registry.register("r$index", job)
        if (index % 2 == 0) registry.cancel("r$index") else registry.finish("r$index")
      }
    }

    threads.forEach { it.start() }
    threads.forEach { it.join() }

    assertEquals(0, registry.activeCount)
    jobs.forEachIndexed { index, job ->
      assertEquals("job $index", index % 2 == 0, job.isCancelled)
    }
  }
}
