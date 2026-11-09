plugins {
    `java-gradle-plugin`
}

group = "dev.morphismx.retrodfu"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.asm)
    implementation(libs.asm.commons)
    implementation(libs.animalSniffer)
    testImplementation(libs.junit) {
        // junit:junit drags hamcrest-core:1.3 along transitively;
        // hamcrest:3.0 already contains every hamcrest-core class.
        exclude(group = "org.hamcrest", module = "hamcrest-core")
    }
    testImplementation(libs.hamcrest)
}

gradlePlugin {
    plugins {
        create("retroTools") {
            id = "retrodfu"
            implementationClass = "dev.morphismx.retrodfu.RetroDFUPlugin"
        }
    }
}
