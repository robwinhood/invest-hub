package com.investhub.adapter.`in`.lifecycle

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.doubles.shouldBeNaN
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

class StartupReadyTrackerTest : DescribeSpec() {
    init {
        describe("StartupReadyTracker") {
            it("초기 상태에서 메트릭은 NaN이다 (Datadog 0 오염 방지)") {
                val registry = SimpleMeterRegistry()
                StartupReadyTracker(registry)
                val gauge = registry.find(StartupReadyTracker.METRIC_NAME).gauge()
                gauge shouldNotBe null
                gauge!!.value().shouldBeNaN()
            }
            it("웜업 완료 전에 probe가 도착하면 gap을 기록하지 않는다") {
                val registry = SimpleMeterRegistry()
                val tracker = StartupReadyTracker(registry)
                tracker.recordFirstProbe()
                val gauge = registry.find(StartupReadyTracker.METRIC_NAME).gauge()
                gauge!!.value().shouldBeNaN()
                tracker.isFirstProbeRecorded() shouldBe false
            }
            it("웜업 완료 후 probe가 도착하면 gap을 기록한다") {
                val registry = SimpleMeterRegistry()
                val tracker = StartupReadyTracker(registry)
                tracker.onWarmupCompleted()
                Thread.sleep(10)
                tracker.recordFirstProbe()
                val gauge = registry.find(StartupReadyTracker.METRIC_NAME).gauge()
                val gapMs = gauge!!.value()
                gapMs shouldBe gapMs.coerceAtLeast(0.0)
                tracker.isFirstProbeRecorded() shouldBe true
            }
            it("probe가 두 번 도착해도 gap은 첫 번째만 기록된다") {
                val registry = SimpleMeterRegistry()
                val tracker = StartupReadyTracker(registry)
                tracker.onWarmupCompleted()
                Thread.sleep(10)
                tracker.recordFirstProbe()
                val firstGap = registry.find(StartupReadyTracker.METRIC_NAME).gauge()!!.value()
                Thread.sleep(50)
                tracker.recordFirstProbe()
                val secondGap = registry.find(StartupReadyTracker.METRIC_NAME).gauge()!!.value()
                firstGap shouldBe secondGap
            }
            it("isFirstProbeRecorded는 probe 기록 전 false, 후 true다") {
                val registry = SimpleMeterRegistry()
                val tracker = StartupReadyTracker(registry)
                tracker.isFirstProbeRecorded() shouldBe false
                tracker.onWarmupCompleted()
                tracker.recordFirstProbe()
                tracker.isFirstProbeRecorded() shouldBe true
            }
        }
    }
}
