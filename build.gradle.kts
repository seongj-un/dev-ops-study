plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.3.21"
}

group = "com.devopsstudy"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

springBoot {
	// /actuator/info에 버전을 노출한다. 지금 떠 있는 게 어떤 빌드인지 배포 후 확인할 때 쓴다.
	buildInfo {
		// 빌드 시각을 빼야 코드가 같으면 결과물도 같다 (Gradle 캐시가 잘 먹는다)
		excludes = setOf("time")
		properties {
			// CI가 GIT_COMMIT 환경변수로 커밋 SHA를 넘기면 /actuator/info의 build.commit으로 나와, 떠 있는 앱이 어느 커밋인지 알 수 있다. 환경변수가 없으면(로컬 빌드) "local".
			// Provider를 그대로 넘겨서 환경변수를 구성 단계가 아니라 태스크 입력을 계산할 때 읽는다. 그래서 커밋이 바뀌어도 구성 캐시는 그대로 재사용되고, 입력이 바뀐 bootBuildInfo만 다시 실행된다.
			additional.put("commit", providers.environmentVariable("GIT_COMMIT").orElse("local"))
		}
	}
}

// Mockito(인라인 목 메이커)가 테스트 도중 스스로 에이전트를 붙이면 JDK 21+가 경고를 찍는다.
// 문서대로 JVM 시작 때 -javaagent로 미리 붙여 경고를 없애고, 미래 JDK에서 자동 부착이 막혀도 테스트가 깨지지 않게 한다.
val mockitoAgent = configurations.create("mockitoAgent")

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	runtimeOnly("io.micrometer:micrometer-registry-prometheus")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-redis-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	// 버전은 Spring BOM이 정한다. 에이전트 jar만 필요하므로 전이 의존성은 받지 않는다
	mockitoAgent("org.mockito:mockito-core") { isTransitive = false }
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

// configureEach: 테스트 태스크를 실제로 실행할 때만 설정한다. 그래야 bootJar 같은 빌드(Dockerfile)는 mockitoAgent를 내려받지 않는다
tasks.withType<Test>().configureEach {
	useJUnitPlatform()
	jvmArgs("-javaagent:${mockitoAgent.asPath}")
	// Mockito 인라인 목 메이커는 첫 목을 만들 때 부트스트랩 클래스패스에 jar를 덧붙인다. 그러면 JVM은 CDS(클래스 공유)를 부트 로더 클래스에만
	// 적용하고 그 밖의 클래스(플랫폼·앱 로더)는 공유하지 못하는데, 이때 "Sharing is only supported for boot loader classes" 경고를 찍는다.
	// 테스트 JVM에서는 CDS를 통째로 꺼서 경고를 없앤다.
	jvmArgs("-Xshare:off")
}

tasks.bootJar {
	// 버전이 바뀌어도 Dockerfile이 같은 경로를 쓸 수 있게 이름을 고정한다
	archiveFileName = "app.jar"
}
