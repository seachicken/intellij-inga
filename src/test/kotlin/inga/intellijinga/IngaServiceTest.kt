package inga.intellijinga

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class IngaServiceTest {
    @ParameterizedTest
    @CsvSource(
        "Eclipse Temurin version 21.0.2, 21",
        "java version \"17.0.8\", 17",
        "JetBrains Runtime version 17.0.6, 17",
        "java version \"1.8.0_442\", 8",
    )
    fun `get the Java version from the project's SDK`(sdkVersion: String, expected: Int) {
        assertThat(IngaService.getProjectJavaVersion(sdkVersion)).isEqualTo(expected)
    }
}