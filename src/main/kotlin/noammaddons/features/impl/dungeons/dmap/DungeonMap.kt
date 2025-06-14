@file:Suppress("UNUSED_PARAMETER")

package noammaddons.features.impl.dungeons.dmap

import gg.essential.api.EssentialAPI
import gg.essential.elementa.utils.withAlpha
import net.minecraft.item.ItemMap
import net.minecraft.network.play.server.S34PacketMaps
import net.minecraft.world.storage.MapData
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import noammaddons.events.*
import noammaddons.features.Feature
import noammaddons.features.impl.DevOptions
import noammaddons.features.impl.dungeons.dmap.core.DungeonMapConfig
import noammaddons.features.impl.dungeons.dmap.core.DungeonMapElement
import noammaddons.features.impl.dungeons.dmap.core.map.*
import noammaddons.features.impl.dungeons.dmap.handlers.*
import noammaddons.features.impl.dungeons.dmap.utils.MapUtils
import noammaddons.ui.config.core.annotations.AlwaysActive
import noammaddons.ui.config.core.impl.ButtonSetting
import noammaddons.utils.*
import noammaddons.utils.ChatUtils.removeFormatting
import noammaddons.utils.LocationUtils.inBoss
import noammaddons.utils.LocationUtils.inDungeon
import noammaddons.utils.Utils.equalsOneOf
import noammaddons.utils.Utils.remove

@AlwaysActive
object DungeonMap: Feature(toggled = false) {
    private val openConfigBtn by ButtonSetting("Open Config") {
        GuiUtils.openScreen(DungeonMapConfig.gui())
    }
    
    val debug get() = EssentialAPI.getMinecraftUtil().isDevelopment() || DevOptions.devMode

    @SubscribeEvent
    fun onRenderOverlay(event: RenderOverlay) {
        if (debug) {
            val currentRoom = ScanUtils.currentRoom
            val rot = currentRoom?.rotation
            val h = currentRoom?.highestBlock
            RenderUtils.drawText(
                listOf(
                    "roofHight: $h",
                    "rotation: $rot",
                ).joinToString("\n"),
                300, 300,
            )
        }

        if (! DungeonMapConfig.mapEnabled || ! inDungeon) return
        if (! DungeonMapConfig.dungeonMapCheater && ! DungeonUtils.dungeonStarted) return
        if (DungeonMapConfig.mapHideInBoss && inBoss) return

        DungeonMapElement.draw()
    }

    @SubscribeEvent
    fun onTick(event: Tick) {
        if (! inDungeon || mc.thePlayer == null) return

        if (DungeonUtils.dungeonStarted) {
            if (! MapUtils.calibrated) {
                if (DungeonInfo.dungeonMap == null) {
                    DungeonInfo.dungeonMap = MapUtils.getMapData()
                }
                MapUtils.calibrated = MapUtils.calibrateMap()
            }

            (DungeonInfo.dungeonMap ?: DungeonInfo.guessMapData)?.let {
                MapUpdater.updateRooms(it)
                MapUpdater.updatePlayers(it)
            }
        }

        if (DungeonScanner.shouldScan || DevOptions.devMode) {
            DungeonScanner.scan()
        }
    }

    @SubscribeEvent
    fun onWorldUnload(event: WorldUnloadEvent) {
        DungeonInfo.reset()
        DungeonScanner.hasScanned = false
        MapUtils.calibrated = false
        MapUtils.startCorner = Pair(5, 5)
        MapUtils.mapRoomSize = 16
        MapUtils.coordMultiplier = 0.625
        MapUpdater.playerJobs.clear()
    }

    @SubscribeEvent
    fun onWorldRender(event: RenderWorld) {
        if (! inDungeon || ! DungeonMapConfig.boxWitherDoors || inBoss) return
        DungeonInfo.dungeonList.filterIsInstance<Door>()
            .filter { ! it.type.equalsOneOf(DoorType.ENTRANCE, DoorType.NORMAL) && ! it.opened }
            .filterNot { (DungeonUtils.dungeonStarted || ! DungeonMapConfig.dungeonMapCheater) && it.state == RoomState.UNDISCOVERED }
            .forEach {
                val color = if (DungeonInfo.keys > 0) DungeonMapConfig.witherDoorKeyColor
                else DungeonMapConfig.witherDoorNoKeyColor

                RenderUtils.drawBox(
                    it.x - 1, 69.0, it.z - 1,
                    width = 3, height = 4,
                    color = color.withAlpha(DungeonMapConfig.witherDoorFill),
                    outline = true, fill = true, phase = true,
                    lineWidth = DungeonMapConfig.witherDoorOutlineWidth
                )
            }
    }

    @SubscribeEvent
    fun onPuzzleReset(event: DungeonEvent.PuzzleEvent.Reset) {
        DungeonInfo.uniqueRooms.filter { it.mainRoom.data.type == RoomType.PUZZLE }.find {
            event.pazzle == Puzzle.fromName(it.name)?.tabName
        }?.run { mainRoom.state = RoomState.DISCOVERED }
    }

    @SubscribeEvent
    fun onPacket(event: PacketEvent.Received) {
        if (! inDungeon) return
        if (event.packet !is S34PacketMaps) return
        if (DungeonInfo.dungeonMap != null) return
        if (mc.theWorld == null) return
        val id = event.packet.mapId
        if (id and 1000 != 0) return

        val guess = mc.theWorld.mapStorage.loadData(MapData::class.java, "map_${id}") as MapData? ?: return
        if (guess.mapDecorations.any { it.value.func_176110_a() == 1.toByte() }) {
            DungeonInfo.guessMapData = guess
        }

        if (MapUtils.calibrated) {
            ItemMap.loadMapData(id, mc.theWorld)?.let { mapData ->
                MapUpdater.updateRooms(mapData)
                MapUpdater.updatePlayers(mapData)
            }
        }
    }

    @SubscribeEvent
    fun onMessage(event: MessageSentEvent) {
        var text = event.message.removeFormatting().lowercase()
        val commandName = "/dmap"
        if (! text.startsWith(commandName)) return
        text = text.remove(commandName)
        event.isCanceled = true
        when (text) {
            "", " " -> openConfigBtn.run()
            " setexplored" -> DungeonInfo.dungeonList.forEach { it.state = RoomState.DISCOVERED }
        }
    }

    @SubscribeEvent
    fun onRoomStateChangeEvent(event: DungeonEvent.RoomEvent.onStateChange) {
        ClearInfoUpdater.checkSplits(event.room.data, event.oldState, event.newState, event.roomPlayers)
    }

    @SubscribeEvent
    fun onPlayerDeathEvent(event: DungeonEvent.PlayerDeathEvent) {
        ClearInfoUpdater.updateDeaths(event.name, event.reason)
        if (TablistListener.deathCount == 0) {
            DungeonInfo.firstDeathHadSpirit = ProfileUtils.getSpiritPet(event.name)
        }
    }

    @SubscribeEvent
    fun onRunStartEvent(event: DungeonEvent.RunStatedEvent) {
        ClearInfoUpdater.initStartSecrets()
    }

    @SubscribeEvent
    fun onRunEndEvent(event: DungeonEvent.RunEndedEvent) {
        ClearInfoUpdater.sendClearInfoMessage()
    }
}