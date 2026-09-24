//
//  AvailabilityMappingTest.kt
//  OnDeviceLlm — Android
//
//  The FeatureStatus mapping (DECISIONS.md D34) and the missing-dependency
//  reason (D33).
//

package expo.modules.ondevicellmandroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvailabilityMappingTest {
  @Test
  fun `AVAILABLE is available with no reason`() {
    val availability = AvailabilityMapping.fromFeatureStatus(FeatureStatusCode.AVAILABLE, 3)

    assertTrue(availability.available)
    assertNull(availability.reason)
    // The wire shape is a discriminated union on `available`; an available
    // answer carries no reason key at all.
    assertEquals(setOf("available"), availability.toMap().keys)
  }

  @Test
  fun `DOWNLOADING and DOWNLOADABLE are both modelNotReady`() {
    assertEquals(
      "modelNotReady",
      AvailabilityMapping.fromFeatureStatus(FeatureStatusCode.DOWNLOADING, 2).reason,
    )
    assertEquals(
      "modelNotReady",
      AvailabilityMapping.fromFeatureStatus(FeatureStatusCode.DOWNLOADABLE, 1).reason,
    )
  }

  @Test
  fun `DOWNLOADABLE says in its detail that it did not start a download`() {
    // D34: availability() is a question, not a command. The router calls it on
    // every route decision and useAvailability calls it on mount; starting a
    // large, possibly metered download as a side effect of asking is the
    // surprise a library must never spring. The detail is the only place a
    // developer can learn that, so it is asserted.
    val detail = AvailabilityMapping.fromFeatureStatus(FeatureStatusCode.DOWNLOADABLE, 1).detail

    assertTrue(detail!!.contains("does not start the download"))
  }

  @Test
  fun `UNAVAILABLE is deviceNotEligible`() {
    val availability = AvailabilityMapping.fromFeatureStatus(FeatureStatusCode.UNAVAILABLE, 0)

    assertFalse(availability.available)
    assertEquals("deviceNotEligible", availability.reason)
  }

  @Test
  fun `an unrecognised status degrades to modelNotReady, not to available`() {
    // checkStatus() returns a bare Int, and a fifth state in a future beta is
    // entirely plausible. modelNotReady is the only recoverable reason, so a
    // caller re-checks instead of permanently writing the device off.
    val availability = AvailabilityMapping.fromFeatureStatus(null, 7)

    assertFalse(availability.available)
    assertEquals("modelNotReady", availability.reason)
    assertTrue(availability.detail!!.contains("7"))
  }

  @Test
  fun `notEnabled is never produced`() {
    // It exists because Apple has a user-facing toggle. AICore has no
    // equivalent opt-in, so this provider never returns it — deliberately, and
    // nobody should "fix" the apparent omission.
    val everyState = listOf(
      AvailabilityMapping.fromFeatureStatus(FeatureStatusCode.AVAILABLE, 3),
      AvailabilityMapping.fromFeatureStatus(FeatureStatusCode.DOWNLOADING, 2),
      AvailabilityMapping.fromFeatureStatus(FeatureStatusCode.DOWNLOADABLE, 1),
      AvailabilityMapping.fromFeatureStatus(FeatureStatusCode.UNAVAILABLE, 0),
      AvailabilityMapping.fromFeatureStatus(null, 99),
      AvailabilityMapping.missingDependency(),
      AvailabilityMapping.belowSdkFloor(21)!!,
    )

    everyState.forEach { assertTrue(it.reason != "notEnabled") }
  }

  @Test
  fun `below the SDK floor is unsupportedPlatform, at or above it is nothing`() {
    assertNull(AvailabilityMapping.belowSdkFloor(26))
    assertNull(AvailabilityMapping.belowSdkFloor(36))

    val below = AvailabilityMapping.belowSdkFloor(25)!!
    assertEquals("unsupportedPlatform", below.reason)
    assertTrue(below.detail!!.contains("25"))
  }

  @Test
  fun `a missing dependency is unsupportedPlatform and names the remedy`() {
    // D33. `notEnabled` would tell a user to change a setting that does not
    // exist for a problem only a developer can fix; `unsupportedPlatform` is
    // the taxonomy's own words for "the framework is not there at all", which
    // with a compileOnly dependency is literally true. The detail carries the
    // specificity the shared code loses.
    val availability = AvailabilityMapping.missingDependency()

    assertFalse(availability.available)
    assertEquals("unsupportedPlatform", availability.reason)
    assertTrue(availability.detail!!.contains(AvailabilityMapping.DEPENDENCY_COORDINATE))
    assertTrue(availability.detail!!.contains("build.gradle"))
  }

  @Test
  fun `a failed checkStatus reuses the error table's reason when it has one`() {
    val incompatible = ErrorMapping.mapGenAiError(
      GenAiErrorCode.AICORE_INCOMPATIBLE, rawCode = -101, message = "no", retryDelayMs = null,
    )
    assertEquals("deviceNotEligible", AvailabilityMapping.fromError(incompatible).reason)

    val update = ErrorMapping.mapGenAiError(
      GenAiErrorCode.NEEDS_SYSTEM_UPDATE, rawCode = 604, message = "no", retryDelayMs = null,
    )
    assertEquals("unsupportedPlatform", AvailabilityMapping.fromError(update).reason)
  }

  @Test
  fun `a failed checkStatus that is not an unavailable becomes modelNotReady`() {
    // A failure to *ask* is not an answer, and it must never report available.
    val busy = ErrorMapping.mapGenAiError(
      GenAiErrorCode.BUSY, rawCode = 9, message = "busy", retryDelayMs = 1000L,
    )
    val availability = AvailabilityMapping.fromError(busy)

    assertFalse(availability.available)
    assertEquals("modelNotReady", availability.reason)
  }
}
