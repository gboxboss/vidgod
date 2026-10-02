// Local-only: dl.google.com is blocked in this sandbox, so Google Maven artifacts are served
// from a mirror fetched from the repo's deps-mirror branch (see tools/offline-build.md).
val mirrorDir = File("/opt/m2mirror")

fun RepositoryHandler.useGoogleMirror() {
    filterIsInstance<MavenArtifactRepository>()
        .filter { it.url.toString().contains("google.com") }
        .forEach { remove(it) }
    val mirror = maven {
        name = "googleMirror"
        url = mirrorDir.toURI()
    }
    remove(mirror)
    addFirst(mirror)
}

settingsEvaluated {
    pluginManagement.repositories.useGoogleMirror()
    dependencyResolutionManagement.repositories.useGoogleMirror()
}
