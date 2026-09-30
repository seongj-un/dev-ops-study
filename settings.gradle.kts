plugins {
	// 로컬에 JDK 25가 없으면 Gradle이 자동으로 내려받는다 (toolchain auto-provisioning)
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "shortener"
