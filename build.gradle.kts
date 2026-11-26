plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

/**
 * 把 build 目录挪到 NTFS 盘。
 *
 * ## 为什么
 *
 * 这个仓库在 F: 盘上，而 **F: 是 exFAT**。exFAT 的文件锁语义比 NTFS 弱，
 * Gradle/AGP 的构建在它上面会间歇性失败，报的都是这类与代码无关的错：
 *
 *   - `FileSystemException: manifest-merger-debug-report.txt
 *      被另一进程使用，无法访问该文件`
 *   - `FileNotFoundException: dex-renamer-state.txt (系统找不到指定的路径)`
 *   - `Could not delete .../kotlin-classes/debug/at`
 *
 * CI 上（ext4）从来不会出现这些问题。
 *
 * ## 做法
 *
 * `local.properties` 里配 `vte.buildDir` 指到一个 NTFS 路径；
 * **没配置时完全保持默认行为**（CI 就是这种情况），不改变任何既有流程。
 */
val altBuildDir: String? = run {
    val props = rootProject.file("local.properties")
    if (!props.exists()) return@run null
    val p = java.util.Properties()
    props.inputStream().use { p.load(it) }
    p.getProperty("vte.buildDir")?.takeIf { it.isNotBlank() }
}

if (altBuildDir != null) {
    // 每个子模块的 build 目录也要跟着挪，否则 app 的中间产物仍落在 exFAT 上。
    // 用「模块路径 -> 同名子目录」的映射，避免多个模块共用一个目录。
    allprojects {
        val moduleName = path.trimStart(':').replace(':', '_').ifEmpty { "root" }
        layout.buildDirectory.set(file("$altBuildDir/$moduleName"))
    }
}
