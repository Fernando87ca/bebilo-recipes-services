pluginManagement {
    fun versionFromCatalog(name: String): String {
        val versionCatalog = settingsDir.resolve("gradle/libs.versions.toml").readText()
        val version = Regex("""(?m)^\s*${Regex.escape(name)}\s*=\s*"([^"]+)"""")
            .find(versionCatalog)
            ?.groupValues
            ?.get(1)

        return version ?: error("Version '$name' not found in gradle/libs.versions.toml")
    }

    plugins {
        id("org.gradle.toolchains.foojay-resolver-convention") version versionFromCatalog("foojay-resolver-convention")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention")
}
rootProject.name = "recipe-services"
