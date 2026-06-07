package com.investhub.config

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith

class CacheKeyVersionGeneratorTest : DescribeSpec() {
    init {
        describe("versionedName — 캐시 이름 자동 버전화") {
            it("baseName + ':v' + 해시 형식의 이름을 반환한다") {
                val name = CacheKeyVersionGenerator.versionedName("recommendations", SampleDto::class)

                name shouldStartWith "recommendations:v"
                name shouldContain ":"
            }

            it("같은 클래스로 호출하면 항상 같은 이름을 반환한다 (결정론적)") {
                val name1 = CacheKeyVersionGenerator.versionedName("recommendations", SampleDto::class)
                val name2 = CacheKeyVersionGenerator.versionedName("recommendations", SampleDto::class)

                name1 shouldBe name2
            }

            it("다른 baseName은 다른 이름을 반환한다") {
                val name1 = CacheKeyVersionGenerator.versionedName("cache-a", SampleDto::class)
                val name2 = CacheKeyVersionGenerator.versionedName("cache-b", SampleDto::class)

                name1 shouldNotBe name2
            }
        }

        describe("getVersion — 클래스 구조 기반 자동 버전") {
            it("같은 클래스는 항상 같은 버전을 반환한다") {
                val v1 = CacheKeyVersionGenerator.getVersion(SampleDto::class)
                val v2 = CacheKeyVersionGenerator.getVersion(SampleDto::class)

                v1 shouldBe v2
            }

            it("버전은 'v'로 시작하는 4바이트 해시다") {
                val version = CacheKeyVersionGenerator.getVersion(SampleDto::class)

                version shouldStartWith "v"
                version.length shouldBe 9 // "v" + 8자리 hex
            }

            it("다른 클래스는 다른 버전을 반환한다") {
                val v1 = CacheKeyVersionGenerator.getVersion(SampleDto::class)
                val v2 = CacheKeyVersionGenerator.getVersion(DifferentDto::class)

                v1 shouldNotBe v2
            }

            it("프로퍼티 이름이 달라지면 버전이 달라진다 — 필드명 변경 감지") {
                val v1 = CacheKeyVersionGenerator.getVersion(ClassWithName::class)
                val v2 = CacheKeyVersionGenerator.getVersion(ClassWithTitle::class)

                v1 shouldNotBe v2
            }

            it("프로퍼티 타입이 달라지면 버전이 달라진다 — 타입 변경 감지") {
                val v1 = CacheKeyVersionGenerator.getVersion(ClassWithIntId::class)
                val v2 = CacheKeyVersionGenerator.getVersion(ClassWithStringId::class)

                v1 shouldNotBe v2
            }

            it("프로퍼티가 추가되면 버전이 달라진다 — Redis 직렬화 충돌 자동 방지") {
                val vBefore = CacheKeyVersionGenerator.getVersion(DtoBefore::class)
                val vAfter = CacheKeyVersionGenerator.getVersion(DtoAfter::class)

                vBefore shouldNotBe vAfter
            }

            it("프로퍼티가 삭제되면 버전이 달라진다") {
                // DtoAfter(3 fields) → DtoBefore(2 fields) — 역방향도 감지
                val vFull = CacheKeyVersionGenerator.getVersion(DtoAfter::class)
                val vReduced = CacheKeyVersionGenerator.getVersion(DtoBefore::class)

                vFull shouldNotBe vReduced
            }

            it("순환 참조가 있는 클래스도 버전을 생성한다 (무한루프 방지)") {
                val version = CacheKeyVersionGenerator.getVersion(SelfRefDto::class)

                version shouldStartWith "v"
            }

            it("중첩 객체를 가진 클래스도 버전을 생성한다") {
                val version = CacheKeyVersionGenerator.getVersion(ClassWithNested::class)

                version shouldStartWith "v"
            }

            it("중첩 객체의 구조가 달라지면 버전이 달라진다 — 깊은 구조 변경 감지") {
                val v1 = CacheKeyVersionGenerator.getVersion(ClassWithNestedA::class)
                val v2 = CacheKeyVersionGenerator.getVersion(ClassWithNestedB::class)

                v1 shouldNotBe v2
            }

            it("List 제네릭 타입이 달라지면 버전이 달라진다") {
                // List<ClassWithIntId> vs List<ClassWithStringId>
                val v1 = CacheKeyVersionGenerator.getVersion(ListWithIntId::class)
                val v2 = CacheKeyVersionGenerator.getVersion(ListWithStringId::class)

                v1 shouldNotBe v2
            }

            it("같은 클래스를 반복 호출해도 결과가 동일하다 (내부 캐시 동작)") {
                repeat(10) {
                    CacheKeyVersionGenerator.getVersion(SampleDto::class) shouldBe
                        CacheKeyVersionGenerator.getVersion(SampleDto::class)
                }
            }

            it("버전은 8자리 16진수 문자만 포함한다") {
                val version = CacheKeyVersionGenerator.getVersion(SampleDto::class)
                val hexPart = version.removePrefix("v")

                hexPart.all { it in '0'..'9' || it in 'a'..'f' } shouldBe true
                hexPart.length shouldBe 8
            }
        }
    }

    // 테스트용 더미 클래스 —
    // 실제 서비스에서는 InvestmentProduct 같은 캐시 대상 클래스를 사용한다.
    data class SampleDto(
        val id: Int,
        val name: String,
    )

    data class DifferentDto(
        val code: String,
        val value: Int,
    )

    data class ClassWithName(
        val name: String,
    )

    data class ClassWithTitle(
        val title: String,
    )

    data class ClassWithIntId(
        val id: Int,
    )

    data class ClassWithStringId(
        val id: String,
    )

    /** Redis 직렬화 충돌 시나리오: 필드 추가 전 */
    data class DtoBefore(
        val id: Long,
        val name: String,
    )

    /** Redis 직렬화 충돌 시나리오: 필드 추가 후 → 자동으로 다른 버전 → 충돌 없음 */
    data class DtoAfter(
        val id: Long,
        val name: String,
        val category: String,
    )

    data class SelfRefDto(
        val id: Int,
        val parent: SelfRefDto?,
    )

    data class NestedA(
        val value: String,
    )

    data class NestedB(
        val count: Int,
    )

    data class ClassWithNested(
        val nested: NestedA,
    )

    data class ClassWithNestedA(
        val nested: NestedA,
    )

    data class ClassWithNestedB(
        val nested: NestedB,
    )

    data class ListWithIntId(
        val items: List<ClassWithIntId>,
    )

    data class ListWithStringId(
        val items: List<ClassWithStringId>,
    )
}
