pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://devrepo.kakaomobility.com/repository/kakao-mobility-android-knsdk-public/") }
        maven { url = uri("https://www.jitpack.io") }
    }
}

rootProject.name = "DriveAlert"
include(":app")
