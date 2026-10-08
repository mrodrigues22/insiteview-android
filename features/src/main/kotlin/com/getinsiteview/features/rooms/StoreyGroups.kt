package com.getinsiteview.features.rooms

import com.getinsiteview.modelkit.RoomEntry

/** Rooms on one storey, in list order. */
data class StoreyGroup(val storeyID: String?, val rooms: List<RoomEntry>)

/** Consecutive rooms on the same storey (the list is sorted by storey). */
fun storeyGroups(rooms: List<RoomEntry>): List<StoreyGroup> {
    val groups = ArrayList<StoreyGroup>()
    for (room in rooms) {
        val last = groups.lastOrNull()
        if (last != null && last.storeyID == room.storeyID) {
            groups[groups.size - 1] = last.copy(rooms = last.rooms + room)
        } else {
            groups.add(StoreyGroup(room.storeyID, listOf(room)))
        }
    }
    return groups
}
