import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import dev.architectury.pack200.java.Pack200Adapter
import net.fabricmc.loom.task.RemapJarTask

plugins {
    idea
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("dev.architectury.architectury-pack200") version "0.1.3"
    id("gg.essential.loom") version "0.10.0.+"
    id("net.kyori.blossom") version "1.3.1"
    kotlin("jvm") version "2.0.0-Beta1"
    kotlin("plugin.serialization") version "1.5.10"
}

val modName: String by project
val modID: String by project
val modVersion: String by project
version = modVersion
group = modID

blossom {
    replaceToken("@NAME@", modName)
    replaceToken("@MODID@", modID)
    replaceToken("@VER@", version)
}


repositories {
    mavenCentral()
    maven("https://repo.spongepowered.org/repository/maven-public/")
    maven("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1")
    maven("https://repo.essential.gg/repository/maven-public/")
    maven("https://repo.polyfrost.cc/releases")
}

val packageLib by configurations.creating {
    configurations.implementation.get().extendsFrom(this)
}


dependencies {
    minecraft("com.mojang:minecraft:1.8.9")
    mappings("de.oceanlabs.mcp:mcp_stable:22-1.8.9")
    forge("net.minecraftforge:forge:1.8.9-11.15.1.2318-1.8.9")

    annotationProcessor("org.spongepowered:mixin:0.8.5:processor")
    compileOnly("org.spongepowered:mixin:0.8.5")

    packageLib("gg.essential:loader-launchwrapper:1.2.1")
    implementation("gg.essential:essential-1.8.9-forge:12132+g6e2bf4dc5")

    runtimeOnly("me.djtheredstoner:DevAuth-forge-legacy:1.1.0")

    compileOnly("com.github.NotEnoughUpdates:NotEnoughUpdates:2.4.0:all")
}

sourceSets.main {
    output.setResourcesDir(file("${buildDir}/classes/kotlin/main"))
}

sourceSets["main"].java.srcDirs("src/main/kotlin")

loom {
    silentMojangMappingsLicense()
    launchConfigs.getByName("client") {
        property("mixin.debug", "true")
        property("asmhelper.verbose", "true")
        arg("--tweakClass", "gg.essential.loader.stage0.EssentialSetupTweaker")
        arg("--mixin", "mixins.${modID}.json")
    }
    runConfigs {
        getByName("client") {
            isIdeConfigGenerated = true
        }
        remove(getByName("server"))
    }
    forge {
        pack200Provider.set(Pack200Adapter())
        mixinConfig("mixins.${modID}.json")
    }
    mixin.defaultRefmapName.set("mixins.${modID}.refmap.json")
}

tasks {
    processResources {
        inputs.property("modname", modName)
        inputs.property("modid", modID)
        inputs.property("version", project.version)
        inputs.property("mcversion", "1.8.9")

        filesMatching(listOf("mcmod.info", "mixins.${modID}.json")) {
            expand(
                mapOf(
                    "modname" to modName,
                    "modid" to modID,
                    "version" to project.version,
                    "mcversion" to "1.8.9"
                )
            )
        }
        dependsOn(compileJava)
    }
    named<Jar>("jar") {
        manifest.attributes(
            "FMLCorePluginContainsFMLMod" to true,
            "FMLCorePlugin" to "${modID}.forge.FMLLoadingPlugin",
            "ForceLoadAsMod" to true,
            "MixinConfigs" to "mixins.${modID}.json",
            "ModSide" to "CLIENT",
            "TweakClass" to "gg.essential.loader.stage0.EssentialSetupTweaker",
            "TweakOrder" to "0"
        )

        dependsOn(shadowJar)
        enabled = false
    }
    named<RemapJarTask>("remapJar") {
        archiveBaseName.set(modName)
        input.set(shadowJar.get().archiveFile)
    }
    named<ShadowJar>("shadowJar") {
        archiveBaseName.set(modName)
        archiveClassifier.set("dev")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        configurations = listOf(packageLib)
        mergeServiceFiles()
    }
    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(8))
kotlin.jvmToolchain(8)
