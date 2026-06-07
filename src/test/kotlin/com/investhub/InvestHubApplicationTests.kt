package com.investhub

import io.kotest.core.spec.style.DescribeSpec
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.junit.jupiter.SpringExtension

// Kotest 6.x에서는 override fun extensions()가 final이므로
// Spring 컨텍스트 통합은 JUnit 5 네이티브 @ExtendWith를 사용한다.
@SpringBootTest
@ExtendWith(SpringExtension::class)
class InvestHubApplicationTests : DescribeSpec() {
    init {
        describe("Spring Context") {
            it("애플리케이션 컨텍스트가 정상적으로 로드된다") {
                // 컨텍스트가 로드되면 통과
            }
        }
    }
}
