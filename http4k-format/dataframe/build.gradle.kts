description = "Http4k KotlinX DataFrame support"

plugins {
    id("org.jetbrains.kotlinx.dataframe")
    id("org.http4k.module")
}

dependencies {
    api(project(":http4k-format-core"))
    api(KotlinX.dataframe)
    testImplementation(project(":http4k-core"))
    testImplementation(testFixtures(project(":http4k-core")))
    testImplementation(testFixtures(project(":http4k-format-core")))
}

dataframes {
    sourceSet = "test"
    packageName = "org.http4k.format.dataframe"
}
