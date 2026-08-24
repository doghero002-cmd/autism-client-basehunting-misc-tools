plugins {
    alias(libs.plugins.fabric.loom)
}

base {
    archivesName = properties["archives_base_name"] as String
    version = libs.versions.mod.version.get()
    group = properties["maven_group"] as String
}

loom {
    accessWidenerPath.set(file("src/main/resources/seedcrackerx.aw"))
}

repositories {
    // AUTISM Client is consumed from your local Maven repo. In the AUTISM project run:
    //     ./gradlew publishToMavenLocal
    mavenLocal()
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    mavenCentral()
    // SeedcrackerX dependency repositories
    maven("https://maven.shedaniel.me/") { name = "Shedaniel (cloth-config)" }
    maven("https://maven.latticg.com/") { name = "LattiCG" }
    maven("https://maven.seedfinding.com/") { name = "Seedfinding" }
    maven("https://maven-snapshots.seedfinding.com/") { name = "Seedfinding Snapshots" }
    maven("https://jitpack.io") { name = "Jitpack" }
}

dependencies {
    // Mirrors the AUTISM Client build: official Mojang mappings are implicit for the configured version, so there is no
    // explicit `mappings(...)` line and dependencies use plain `implementation`.
    minecraft(libs.minecraft)
    implementation(libs.fabric.loader)
    implementation(libs.fabric.api)

    // The AUTISM Client API (published to mavenLocal from the AUTISM project).
    implementation(libs.autism)

    // ---- SeedcrackerX dependencies (bundled jar-in-jar so the addon is self-contained) ----
    // Each library is both an `implementation` (compile classpath) and `include` (jar-in-jar).

    // cloth-config (config GUI). Exclude transitive fabric-api to avoid duplicates.
    val clothConfig = "me.shedaniel.cloth:cloth-config-fabric:${property("cloth_config_version")}"
    implementation(clothConfig) { exclude(group = "net.fabricmc.fabric-api") }
    include(clothConfig) { exclude(group = "net.fabricmc.fabric-api") }

    // seedfinding libraries (non-transitive, matching the original SeedcrackerX build)
    val seedfinding = listOf(
        "com.seedfinding:mc_math:${property("seedfinding_math_version")}",
        "com.seedfinding:mc_seed:${property("seedfinding_seed_version")}",
        "com.seedfinding:mc_core:${property("seedfinding_core_version")}",
        "com.seedfinding:mc_noise:${property("seedfinding_noise_version")}",
        "com.seedfinding:mc_biome:${property("seedfinding_biome_version")}",
        "com.seedfinding:mc_terrain:${property("seedfinding_terrain_version")}",
        "com.seedfinding:mc_feature:${property("seedfinding_feature_version")}",
        "com.seedfinding:mc_reversal:${property("seedfinding_reversal_version")}"
    )
    seedfinding.forEach { dep ->
        implementation(dep) { isTransitive = false }
        include(dep) { isTransitive = false }
    }

    // latticg (Java Random reversal)
    val latticg = "com.seedfinding:latticg:${property("latticg_version")}"
    implementation(latticg)
    include(latticg)
}

// Turns an exact Minecraft version (e.g. "26.2") into a compatible range ("~26.2") so the addon
// keeps loading across patch releases instead of pinning one exact build.
fun toMinecraftCompat(version: String): String {
    val m = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?$""").matchEntire(version)
        ?: return version
    val (year, drop, _) = m.destructured
    return "~$year.$drop"
}

tasks {
    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "mc_version" to libs.versions.minecraft.get(),
            "mc_compat" to toMinecraftCompat(libs.versions.minecraft.get()),
            "fabric_api_version" to libs.versions.fabric.api.get(),
            // The runtime constraint is intentionally not pinned to a specific client version:
            // a bare "*" tells Fabric "any AUTISM Client version", and AUTISM's own apiVersion()
            // handshake gates real compatibility, so the addon loads against any client build
            // (release or dev) rather than requiring the exact version it was compiled against.
            "autism_api_version" to "*"
        )
        inputs.properties(propertyMap)
        filteringCharset = "UTF-8"
        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(25)
    }
}
