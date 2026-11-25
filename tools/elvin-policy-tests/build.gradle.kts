plugins { kotlin("jvm") version "2.4.21" }
repositories { mavenCentral() }
kotlin {
    jvmToolchain(21)
    sourceSets.main {
        kotlin.srcDir("../../core/keys/src/commonMain/kotlin")
        kotlin.include("app/aaps/core/keys/elvin/CommunicationAlarmPolicy.kt")
    }
    sourceSets.test {
        kotlin.srcDir("../../core/keys/src/androidHostTest/kotlin")
        kotlin.include("app/aaps/core/keys/elvin/CommunicationAlarmPolicyTest.kt")
    }
}
dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}
tasks.test { useJUnitPlatform() }
