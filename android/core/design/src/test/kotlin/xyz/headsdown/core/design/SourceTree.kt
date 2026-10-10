package xyz.headsdown.core.design

import java.io.File

/**
 * The checkout, as the file-scan tests see it. Gradle runs unit tests in the module directory
 * (android/core/design), as PlannerLogTest in :feature:shift relies on too.
 */
internal object SourceTree {
    val module: File = File(checkNotNull(System.getProperty("user.dir")))
    val android: File = checkNotNull(module.parentFile?.parentFile) { "not inside android/: $module" }

    init {
        check(module.name == "design" && File(android, "settings.gradle.kts").isFile) { "unexpected working directory: $module" }
    }

    /** The modules that draw something and are not this one: the app, the reveal, every surface. */
    val drawingModules: List<File> by lazy {
        val surfaces = checkNotNull(File(android, "surface").listFiles()).filter { File(it, "src").isDirectory }.sortedBy { it.name }
        check(surfaces.size >= 4) { "expected the tile, the notification, the widget and the haptics: $surfaces" }
        listOf(File(android, "app"), File(android, "feature/reveal")) + surfaces
    }

    /**
     * Every Kotlin and XML file a module ships: all of its source sets (main, debug, release,
     * localdev, devtools) except the test ones.
     */
    fun shippedFiles(moduleDir: File): List<File> {
        val src = File(moduleDir, "src")
        check(src.isDirectory) { "no src in $moduleDir" }
        return checkNotNull(src.listFiles())
            .filter { it.isDirectory && !it.name.startsWith("test") && !it.name.startsWith("androidTest") }
            .flatMap { set -> set.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") } }
            .sortedBy { it.path }
    }

    /** Only `src/main` Kotlin. */
    fun mainKotlin(moduleDir: File): List<File> =
        File(moduleDir, "src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()

    fun relative(file: File): String = file.relativeTo(android).invariantSeparatorsPath
}
