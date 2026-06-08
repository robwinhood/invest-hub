plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jmailen.kotlinter") version "5.0.1"       // Ktlint: 포맷 강제 (main + test, GA)
    // [정적 분석 도구 선택 — GA 안전 원칙]
    // Detekt를 의도적으로 채택하지 않는다.
    //   - Detekt GA(1.23.8)는 Kotlin 2.0.x 컴파일 → Kotlin 2.3.21과 바이너리 비호환
    //   - Kotlin 2.3.21 지원 버전(2.0.0-alpha.x)은 alpha → 프로덕션 안전 원칙 위배
    // 대안: Ktlint(GA, 포맷·스타일) + ArchUnit(GA, 아키텍처·코루틴 금지)로 품질 게이트 유지.
    // Detekt 2.x가 GA로 출시되면 재검토한다.
}

group = "com.investhub"
version = "0.4.9"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.spring.io/milestone") }
}

val resilience4jVersion = "2.4.0"
val kotestVersion = "6.1.11"
val mockkVersion = "1.14.11"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("com.github.ben-manes.caffeine:caffeine")
    // Jackson 3(tools.jackson) Kotlin 모듈 — L2(Mock Redis) 캐시 + 웹(Spring Boot 4 자동설정) JSON 직렬화용.
    // Spring Boot 4.0이 관리하는 jackson-bom 3.1.2(GA)에 포함되므로 버전 생략.
    // (Jackson 2 jackson-module-kotlin은 미사용 — Spring Boot 4는 Jackson 3을 쓰고, 코드에 Jackson 2 ObjectMapper가 없다.
    //  `@JsonInclude` 등 annotation은 jackson-annotations(com.fasterxml 패키지 유지)에서 전이로 제공된다.)
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Resilience4j — Spring Boot 4.x부터 resilience4j-spring-boot4 사용
    implementation("io.github.resilience4j:resilience4j-spring-boot4:$resilience4jVersion")
    implementation("io.github.resilience4j:resilience4j-kotlin:$resilience4jVersion")

    // Kotlin Logging
    implementation("io.github.oshai:kotlin-logging-jvm:8.0.4")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
    // kotest-extensions-spring 1.3.0은 Kotest 6.x와 바이너리 비호환.
    // Spring 컨텍스트 테스트는 @ExtendWith(SpringExtension::class) 사용.
    testImplementation("io.mockk:mockk:$mockkVersion")

    // Architecture tests
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

kotlinter {
    ktlintVersion = "1.5.0"
    reporters = arrayOf("checkstyle", "plain")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// 로컬 구동(./gradlew bootRun)은 'local' 프로파일로 띄운다.
// → LocalCacheSeeder가 데모 사용자 추천 캐시를 사전 적재해, 기동 직후
//   GET /admin/cache가 실제 evict 가능한 키를 보여준다.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    args("--spring.profiles.active=local")
}

// ──────────────────────────────────────────────────────────────
// 코드 품질 태스크 (다중 개발자/AI 환경, 전부 GA 도구)
//
// 품질 검사 범위:
//   lintKotlinMain — 메인 코드 Ktlint (포맷·스타일)
//   lintKotlinTest — 테스트 코드 Ktlint (포맷·스타일)
//   test           — 단위 테스트 + ArchUnit(아키텍처 경계·코루틴 금지·CB 누락 방지)
// ──────────────────────────────────────────────────────────────

// 포맷 자동 수정 (로컬 개발 중 파일 변경 허용)
tasks.register("fix-all") {
    group = "verification"
    description = "formatKotlin + test — 포맷 자동 수정 후 테스트 (파일 수정됨)"
    dependsOn("formatKotlin", "test")
}

// 전체 검증 (PR 전 필수, 파일 수정 없음)
tasks.register("check-all") {
    group = "verification"
    description = "lintKotlin + test — PR 전 필수 (파일 수정 없음)"
    dependsOn("lintKotlin", "test")
}
