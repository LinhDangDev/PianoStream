// PianoStream — settings (Phase 1a frozen path; edits require a plan amendment).
// Repository content filtering per F14: google() serves only the groups that
// actually live on dl.google.com (androidx.*, com.android.*, com.google.android.*);
// everything else resolves from mavenCentral(). Note com.google.dagger /
// com.google.devtools.ksp / com.google.truth are NOT on google maven — the
// include regex is therefore com.google.android.* rather than the plan's loose
// "com.google.*" shorthand (same intent: each repo serves only its expected groups).
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
        google {
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android\\..*")
                includeGroupByRegex("com\\.google\\.android\\..*")
            }
        }
        mavenCentral {
            content {
                excludeGroupByRegex("androidx\\..*")
                excludeGroupByRegex("com\\.android\\..*")
                excludeGroupByRegex("com\\.google\\.android\\..*")
            }
        }
    }
}

rootProject.name = "PianoStream"

include(":app")
include(":core:designsystem")
include(":core:data")
include(":core:media")
include(":core:local")
include(":core:youtube")
include(":feature:home")
include(":feature:search")
include(":feature:library")
include(":feature:player")
