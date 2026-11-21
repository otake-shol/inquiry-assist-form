plugins {
	java
	id("org.springframework.boot") version "4.2.0"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.inquiryassist"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.flywaydb:flyway-database-postgresql")
	runtimeOnly("org.postgresql:postgresql")
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
	// 実際の Ollama を呼ぶテスト（@Tag("live")）は、通常の検証に含めない。liveTest で実行する
	useJUnitPlatform {
		excludeTags("live")
	}
}

// 手元の Ollama を実際に呼ぶ確認。Ollama とモデルが動いている環境で、手元から実行する
tasks.register<Test>("liveTest") {
	description = "実際の Ollama を呼ぶテスト（@Tag(\"live\")）だけを実行する"
	group = "verification"
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	useJUnitPlatform {
		includeTags("live")
	}
	testLogging {
		events("passed", "failed", "skipped")
		showStandardStreams = true
	}
}

// スキルの評価（docs/evaluation.md）。実際の Ollama を呼ぶため、check からは呼ばれない。
// 例: bash scripts/gradle.sh evalRun -Pskill=restater -Psplit=dev -Plabel=baseline
tasks.register<JavaExec>("evalRun") {
	description = "スキルの評価を実行する（実際の Ollama を呼ぶ）"
	group = "verification"
	classpath = sourceSets.test.get().runtimeClasspath
	mainClass = "dev.inquiryassist.eval.EvalMain"
	// evals/ はリポジトリのルートにある
	workingDir = layout.projectDirectory.dir("..").asFile
	systemProperty("eval.root", workingDir.absolutePath)
	val names = listOf("skill", "split", "cases", "promptVersion", "model", "think", "temperature", "repeat", "label", "compareWith")
	args = names.filter { project.hasProperty(it) }.map { "--$it=${project.property(it)}" }
}
