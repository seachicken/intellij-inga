package inga.intellijinga

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class IngaSettingsTest {
    private lateinit var settings: IngaSettings

    @BeforeEach
    fun setUp() {
        settings = IngaSettings().apply {
            modulePaths = listOf("a", "b", "c")
        }
    }

    @Test
    fun `get caller hint`() {
        settings.apply {
            config = IngaConfig(listOf(Server("a", listOf(Client("b")))))
        }
        assertThat(settings.getCallerHint("a/A.kt")).isEqualTo(listOf(Client("b")))
    }

    @Test
    fun `add a new client`() {
        settings.apply {
            config = IngaConfig(listOf(Server("a", emptyList())))
        }
        val addRequest = AddConnectionPathsRequest(
            "a/",
            listOf("c")
        )
        assertThat(settings.applyRequest(addRequest).config).isEqualTo(
            IngaConfig(listOf(Server("a", listOf(Client("c")))))
        )
    }

    @Test
    fun `update a client`() {
        settings.apply {
            config = IngaConfig(listOf(Server("a", listOf(Client("b")))))
        }
        val addRequest = AddConnectionPathsRequest(
            "a/",
            listOf("c")
        )
        assertThat(settings.applyRequest(addRequest).config).isEqualTo(
            IngaConfig(listOf(Server("a", listOf(Client("c")))))
        )
    }

    @Test
    fun `add a new server`() {
        settings.apply {
            config = IngaConfig(listOf(Server("a", emptyList())))
        }
        val addRequest = AddConnectionPathsRequest(
            "b/",
            emptyList()
        )
        assertThat(settings.applyRequest(addRequest).config).isEqualTo(
            IngaConfig(
                listOf(
                    Server("a", emptyList()),
                    Server("b", emptyList())
                )
            )
        )
    }

    @Test
    fun `remove a server`() {
        settings.apply {
            config = IngaConfig(listOf(Server("a", emptyList())))
        }
        val addRequest = AddConnectionPathsRequest(
            "a/",
            null
        )
        assertThat(settings.applyRequest(addRequest).config).isEqualTo(
            IngaConfig(
                emptyList()
            )
        )
    }
}