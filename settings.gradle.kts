pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven {
            name = "googleMavenCentralMirror"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
            content { includeGroup("com.qualcomm.qti") }
        }
        mavenCentral()
    }
}
rootProject.name = "NextVRera"
include(":app", ":runtime-client-probe", ":endvr-client", ":hardware-probe")
