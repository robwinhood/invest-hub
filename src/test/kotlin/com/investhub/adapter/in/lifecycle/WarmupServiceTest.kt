package com.investhub.adapter.`in`.lifecycle

import com.investhub.application.service.Warmer
import com.investhub.application.service.WarmupService
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class WarmupServiceTest : DescribeSpec() {
    init {
        describe("WarmupService") {
            it("warmUp() 호출 시 등록된 모든 Warmer를 실행한다") {
                val warmer1 = mockk<Warmer>(relaxed = true)
                val warmer2 = mockk<Warmer>(relaxed = true)
                every { warmer1.name() } returns "Warmer1"
                every { warmer2.name() } returns "Warmer2"
                val service = WarmupService(listOf(warmer1, warmer2))
                service.warmUp()
                verify(exactly = 1) { warmer1.warm() }
                verify(exactly = 1) { warmer2.warm() }
            }
            it("warmUp() 완료 후 isCompleted가 true가 된다") {
                val service = WarmupService(emptyList())
                service.isCompleted shouldBe false
                service.warmUp()
                service.isCompleted shouldBe true
            }
            it("warmUp()을 두 번 호출해도 Warmer는 한 번만 실행된다") {
                val warmer = mockk<Warmer>(relaxed = true)
                every { warmer.name() } returns "TestWarmer"
                val service = WarmupService(listOf(warmer))
                service.warmUp()
                service.warmUp()
                verify(exactly = 1) { warmer.warm() }
            }
            it("Warmer 하나가 예외를 던져도 나머지 Warmer는 계속 실행된다") {
                val failingWarmer = mockk<Warmer>()
                val successWarmer = mockk<Warmer>(relaxed = true)
                every { failingWarmer.name() } returns "FailingWarmer"
                every { failingWarmer.warm() } throws RuntimeException("웜업 실패")
                every { successWarmer.name() } returns "SuccessWarmer"
                val service = WarmupService(listOf(failingWarmer, successWarmer))
                shouldNotThrow<Exception> { service.warmUp() }
                verify(exactly = 1) { successWarmer.warm() }
            }
            it("모든 Warmer가 실패해도 isCompleted가 true가 된다") {
                val failingWarmer = mockk<Warmer>()
                every { failingWarmer.name() } returns "FailingWarmer"
                every { failingWarmer.warm() } throws RuntimeException("전부 실패")
                val service = WarmupService(listOf(failingWarmer))
                service.warmUp()
                service.isCompleted shouldBe true
            }
            it("warmUp() 완료 후 isRunning이 false다") {
                val service = WarmupService(emptyList())
                service.warmUp()
                service.isRunning shouldBe false
            }
            it("Warmer가 없어도 정상 완료된다") {
                val service = WarmupService(emptyList())
                service.warmUp()
                service.isCompleted shouldBe true
            }
        }
    }
}
