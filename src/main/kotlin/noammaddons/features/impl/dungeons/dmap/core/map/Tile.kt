package noammaddons.features.impl.dungeons.dmap.core.map

import java.awt.Color

interface Tile {
    val x: Int
    val z: Int
    var state: RoomState
    val color: Color
}
