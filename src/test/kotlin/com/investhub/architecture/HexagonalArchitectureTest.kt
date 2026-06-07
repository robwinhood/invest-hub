package com.investhub.architecture

import com.investhub.adapter.out.ResilientAdapter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices

/**
 * 헥사고날 아키텍처 경계와 내결함성 적용 여부를 강제하는 테스트.
 *
 * 개발자가 실수로 아키텍처 경계를 위반하거나,
 * 외부 호출 어댑터에 Circuit Breaker를 빠뜨리는 경우
 * 이 테스트가 즉시 실패하여 조기에 차단한다.
 */
@AnalyzeClasses(
    packages = ["com.investhub"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
class HexagonalArchitectureTest {
    /**
     * 도메인은 순수 Kotlin/Java 코드여야 한다.
     * Spring, Resilience4j, 어댑터 등 외부 프레임워크에 의존해서는 안 된다.
     */
    @ArchTest
    val domainMustNotDependOnFrameworks: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "io.github.resilience4j..",
                "com.investhub.adapter..",
                "com.investhub.config..",
            ).because("도메인 레이어는 외부 프레임워크·어댑터·설정에 의존해서는 안 된다")

    /**
     * 애플리케이션(서비스/포트)은 어댑터 구현체를 직접 참조해서는 안 된다.
     */
    @ArchTest
    val applicationMustNotDependOnAdapters: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("..application..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..adapter..")
            .because("애플리케이션 레이어는 어댑터 구현체에 의존해서는 안 된다")

    /**
     * 포트는 반드시 인터페이스여야 한다.
     */
    @ArchTest
    val portsMustBeInterfaces: ArchRule =
        classes()
            .that()
            .resideInAPackage("..application.port..")
            .should()
            .beInterfaces()
            .because("포트는 인터페이스여야 한다 — 구현체는 adapter 패키지에 위치해야 한다")

    /**
     * 패키지 간 순환 의존성 금지.
     */
    @ArchTest
    val noCyclicDependencies: ArchRule =
        slices()
            .matching("com.investhub.(*)..")
            .should()
            .beFreeOfCycles()
            .because("패키지 간 순환 의존은 아키텍처를 점진적으로 붕괴시킨다")

    /**
     * Circuit Breaker 누락 방지 규칙.
     *
     * 외부 데이터 소스 어댑터(adapter.out.internal, partner, recommendation)는
     * 반드시 [ResilientAdapter] 또는 [CachingResilientAdapter]를 상속해야 한다.
     *
     * 예외: adapter.out.cache — 캐시 이벤트 발행자 등 보조 인프라로, CB 패턴 불필요.
     */
    @ArchTest
    val outAdaptersMustExtendResilientAdapter: ArchRule =
        classes()
            .that()
            .resideInAnyPackage(
                "..adapter.out.internal..",
                "..adapter.out.partner..",
                "..adapter.out.recommendation..",
            ).and()
            .areAnnotatedWith(org.springframework.stereotype.Component::class.java)
            .should()
            .beAssignableTo(ResilientAdapter::class.java)
            .because(
                "외부 데이터 소스 어댑터(internal/partner/recommendation)는 " +
                    "ResilientAdapter 또는 CachingResilientAdapter를 상속해야 한다.",
            )

    /**
     * 코루틴 사용 금지 규칙 (ADR-002).
     *
     * 이 프로젝트는 Java 25 Virtual Thread 기반 설계를 채택했다.
     * kotlinx.coroutines에 의존하는 코드는 빌드에서 차단한다.
     * (이전에는 Detekt가 강제했으나, GA 안전 원칙에 따라 Detekt를 제거하고
     *  GA 도구인 ArchUnit으로 이관했다.)
     */
    @ArchTest
    val noCoroutineUsage: ArchRule =
        noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("kotlinx.coroutines..")
            .because("이 프로젝트는 Virtual Thread 기반 설계로 코루틴을 사용하지 않는다 (ADR-002)")
}
