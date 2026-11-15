// Same mirror policy as the root settings: Aliyun accelerates local (China) builds but is
// unreliable from GitHub Actions' overseas runners, so CI resolves from the origin repos.
val useAliyunMirror = System.getenv("CI") != "true"

dependencyResolutionManagement {
    repositories {
        if (useAliyunMirror) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
include(":convention")
