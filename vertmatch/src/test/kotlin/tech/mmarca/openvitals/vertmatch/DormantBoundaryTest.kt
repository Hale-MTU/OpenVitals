package tech.mmarca.openvitals.vertmatch

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * OpenVitals is offline by design (AGENTS.md, "No INTERNET permission") and this module needs a
 * socket. So it reaches the app only in the opt-in build (`-Popenvitals.vertmatch=true`), whose
 * sources and manifest live apart in `app/src/vertmatch`. These checks keep the default build as
 * it was: if one fails, the default build would ship network code or the INTERNET permission.
 */
class DormantBoundaryTest {
    // Gradle runs a module's tests with that module's directory as the working directory.
    private val repoRoot = File("..").canonicalFile
    private val appBuild = File(repoRoot, "app/build.gradle.kts").readText()

    @Test
    fun `the switch is off unless the build turns it on`() {
        assertWithMessage("openvitals.vertmatch must default to false")
            .that(
                Regex("""gradleProperty\("openvitals\.vertmatch"\)\s*\.map \{ it\.toBoolean\(\) \}\s*\.getOrElse\(false\)""")
                    .containsMatchIn(appBuild),
            )
            .isTrue()
    }

    @Test
    fun `the app depends on this module only behind the switch`() {
        val lines = appBuild.lines()
        val uses = lines.indices.filter { ":vertmatch" in lines[it] }
        assertWithMessage("app/build.gradle.kts should name :vertmatch exactly once").that(uses).hasSize(1)
        assertWithMessage("the :vertmatch dependency must sit inside `if (vertMatchEnabled)`")
            .that(lines[uses.single() - 1].trim())
            .isEqualTo("if (vertMatchEnabled) {")
        assertWithMessage("domain must not depend on :vertmatch")
            .that(File(repoRoot, "domain/build.gradle.kts").readText())
            .doesNotContain(":vertmatch")
    }

    @Test
    fun `the main sources never reach the integration`() {
        val offenders = File(repoRoot, "app/src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val text = file.readText()
                "import tech.mmarca.openvitals.vertmatch." in text || "features.vertmatch" in text
            }
            .map { it.relativeTo(repoRoot).path }
            .toList()
        assertWithMessage("main sources that import the opt-in integration").that(offenders).isEmpty()
    }

    @Test
    fun `the main manifest still strips the network permissions`() {
        val manifest = File(repoRoot, "app/src/main/AndroidManifest.xml").readText()
        assertWithMessage("INTERNET must stay removed from the default build's merged manifest")
            .that(Regex("""android:name="android\.permission\.INTERNET"\s+tools:node="remove"""").containsMatchIn(manifest))
            .isTrue()
    }

    @Test
    fun `only the opt-in manifest asks for INTERNET`() {
        val manifest = File(repoRoot, "app/src/vertmatch/AndroidManifest.xml").readText()
        assertWithMessage("the opt-in manifest should be the one that asks for INTERNET")
            .that(manifest)
            .contains("android.permission.INTERNET")
    }
}
