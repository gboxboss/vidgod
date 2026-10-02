// Init script used by the deps-mirror workflow: resolves every resolvable
// configuration so that the Gradle cache holds all artifacts the build may need.
allprojects {
    tasks.register("resolveAllDependencies") {
        doLast {
            configurations.filter { it.isCanBeResolved }.forEach { conf ->
                try {
                    conf.incoming.artifactView { lenient(true) }.files.files
                } catch (e: Exception) {
                    println("Could not resolve ${project.path}:${conf.name}: ${e.message}")
                }
            }
        }
    }
}
