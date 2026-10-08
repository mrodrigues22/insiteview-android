package com.getinsiteview.features.rooms

import com.getinsiteview.modelkit.Manifest
import com.getinsiteview.modelkit.RoomEntry
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class StoreyGroupsTest {
    private fun room(id: String, storey: String?) = RoomEntry(Manifest.Space(id = id, storeyId = storey), storey, 0, emptySet())

    @Test
    fun `consecutive rooms on one storey form a group`() {
        val groups = storeyGroups(listOf(room("a", "s1"), room("b", "s1"), room("c", "s2"), room("d", null)))
        assertEquals(listOf("s1", "s2", null), groups.map { it.storeyID })
        assertEquals(listOf(listOf("a", "b"), listOf("c"), listOf("d")), groups.map { group -> group.rooms.map { it.id } })
    }

    @Test
    fun `no rooms, no groups`() {
        assertEquals(emptyList<StoreyGroup>(), storeyGroups(emptyList()))
    }
}
