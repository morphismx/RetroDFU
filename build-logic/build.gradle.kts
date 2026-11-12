plugins {
    `java-gradle-plugin`
    alias(libs.plugins.spotless)
    alias(libs.plugins.openjavaformat)
}

spotless {
    java {
        target("src/**/*.java")
    }
}

group = "net.morphismmc.retrodfu"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.asm)
    implementation(libs.asm.commons)
    implementation(libs.animalSniffer)
    implementation(libs.classtransform.core)
    implementation(libs.classtransform.additionalclassprovider)
    implementation(libs.gson)
    testImplementation(libs.junit) {
        // junit:junit drags hamcrest-core:1.3 along transitively;
        // hamcrest:3.0 already contains every hamcrest-core class.
        exclude(group = "org.hamcrest", module = "hamcrest-core")
    }
    testImplementation(libs.hamcrest)
}

gradlePlugin {
    plugins {
        create("retrodfu") {
            id = "retrodfu"
            implementationClass = "net.morphismmc.retrodfu.RetroDFUPlugin"
        }
    }
}
