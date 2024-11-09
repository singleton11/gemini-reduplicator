import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipInputStream
import kotlin.io.path.Path
import kotlin.io.path.div

fun main(args: Array<String>) {
    val (outputDirectory, runtimeClasspath) = args
    val classpath = runtimeClasspath.split(":").map { Path(it) }
    val archivePath = Path(outputDirectory) / "uber-jar.jar"
    uberJar(classpath, archivePath)
}

fun uberJar(jarDirectory: List<Path>, outputUberJar: Path) {
    // Create output stream for the Uber JAR
    JarOutputStream(FileOutputStream(outputUberJar.toFile())).use { jos ->
        // Set to keep track of added entries to avoid duplicates
        val addedEntries = mutableSetOf<String>()

        // Iterate through each JAR in the directory
        jarDirectory.map { it.toFile() }.filter { file -> file.extension == "jar" }.forEach { jarFile ->
            FileInputStream(jarFile).use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val entryName = entry.name
                        // Skip duplicate entries and metadata files
                        if (entryName !in addedEntries && !entry.isDirectory && !entryName.startsWith("META-INF")) {
                            // Add the entry to the Uber JAR
                            jos.putNextEntry(JarEntry(entryName))
                            zis.copyTo(jos)
                            jos.closeEntry()
                            addedEntries.add(entryName)
                        }
                        entry = zis.nextEntry
                    }
                }
            }
        }

        // Optionally, specify the main class for the Uber JAR
        val manifestEntry = JarEntry("META-INF/MANIFEST.MF")
        jos.putNextEntry(manifestEntry)
        jos.write("Main-Class: MainKt\n".toByteArray()) // Replace with your main class
        jos.closeEntry()
    }
    println("Uber JAR created at: ${outputUberJar.toAbsolutePath()}")
}
