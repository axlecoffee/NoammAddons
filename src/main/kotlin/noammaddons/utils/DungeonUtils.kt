package noammaddons.utils

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.block.BlockSkull
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.init.Blocks
import net.minecraft.network.play.server.S38PacketPlayerListItem
import net.minecraft.network.play.server.S47PacketPlayerListHeaderFooter
import net.minecraft.tileentity.TileEntitySkull
import net.minecraft.util.BlockPos
import net.minecraft.util.ResourceLocation
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import noammaddons.NoammAddons.Companion.mayorData
import noammaddons.NoammAddons.Companion.mc
import noammaddons.NoammAddons.Companion.scope
import noammaddons.events.*
import noammaddons.events.EventDispatcher.postAndCatch
import noammaddons.features.impl.DevOptions
import noammaddons.features.impl.dungeons.dmap.core.ClearInfo
import noammaddons.features.impl.dungeons.dmap.core.DungeonMapPlayer
import noammaddons.features.impl.dungeons.dmap.core.map.*
import noammaddons.features.impl.dungeons.dmap.handlers.DungeonInfo
import noammaddons.utils.ActionBarParser.maxSecrets
import noammaddons.utils.ActionBarParser.secrets
import noammaddons.utils.BlockUtils.getBlockAt
import noammaddons.utils.ChatUtils.removeFormatting
import noammaddons.utils.ItemUtils.skyblockID
import noammaddons.utils.LocationUtils.inDungeon
import noammaddons.utils.NumbersUtils.romanToDecimal
import noammaddons.utils.TablistUtils.getTabList
import noammaddons.utils.Utils.equalsOneOf
import java.awt.Color


object DungeonUtils {
    private val tablistRegex = Regex("^\\[(\\d+)] (?:\\[\\w+] )*(\\w+) .*?\\((\\w+)(?: (\\w+))*\\)$") // https://regex101.com/r/gv7bOe/1
    private val puzzleRegex = Regex(" (.+): \\[[✦✔✖].+")
    private val deathRegex = Regex("^ ☠ (?:You were|(?<username>\\w+)) (?<reason>.+?)(?: and became a ghost)?\\.\$") // https://regex101.com/r/Yc3HhV/4
    private val keyPickupRegex = Regex("§r§e§lRIGHT CLICK §r§7on §r§7.+?§r§7 to open it\\. This key can only be used to open §r§a(?<num>\\d+)§r§7 door!§r")
    private val witherDoorOpenedRegex = Regex("^(?:\\[.+?] )?(?<name>\\w+) opened a WITHER door!$")
    private const val bloodOpenedString = "§r§cThe §r§c§lBLOOD DOOR§r§c has been opened!§r"
    private val watcherMessageRegex = Regex("^\\[BOSS\\] The Watcher: .+$")
    private val runEndRegex = Regex("^\\s*(Master Mode)? ?(?:The)? Catacombs - (Floor (.{1,3})|Entrance)\$") // https://regex101.com/r/W4UjWQ/3

    const val WITHER_ESSENCE_ID = "e0f3e929-869e-3dca-9504-54c666ee6f23"
    const val REDSTONE_KEY_ID = "fed95410-aba1-39df-9b95-1d4f361eb66e"

    val runPlayersNames = mutableMapOf<String, ResourceLocation>()
    var dungeonTeammates = mutableListOf<DungeonPlayer>()
    var dungeonTeammatesNoSelf = listOf<DungeonPlayer>()
    var leapTeammates = listOf<DungeonPlayer>()
    var thePlayer: DungeonPlayer? = null

    data class Puzzle(val name: String, val state: RoomState)

    var puzzles = mutableListOf<Puzzle>()

    val dungeonStarted get() = dungeonTeammates.isNotEmpty()
    var dungeonStartTime: Long? = null
    var dungeonEnded = false

    var bloodOpenTime: Long? = null
    var watcherClearTime: Long? = null
    var watcherSpawnTime: Long? = null
    var bossEntryTime: Long? = null
    var dungeonEndTime: Long? = null

    var lastDoorOpenner: DungeonPlayer? = null

    val dungeonItemDrops = listOf(
        "Health Potion VIII Splash Potion", "Healing Potion 8 Splash Potion",
        "Healing Potion VIII Splash Potion", "Healing VIII Splash Potion",
        "Healing 8 Splash Potion", "Decoy", "Inflatable Jerry", "Spirit Leap",
        "Trap", "Training Weights", "Defuse Kit", "Dungeon Chest Key",
        "Treasure Talisman", "Revive Stone", "Architect's First Draft"
    )

    enum class Classes(val color: Color) {
        Empty(Color(0, 0, 0)),
        Archer(Color(125, 0, 0)),
        Berserk(Color(205, 106, 0)),
        Healer(Color(123, 0, 123)),
        Mage(Color(0, 185, 185)),
        Tank(Color(0, 125, 0));

        companion object {
            fun getByName(name: String) = entries.first { it.name.lowercase() == name.lowercase() }
            fun getColorCode(clazz: Classes): String {
                return when (clazz) {
                    Archer -> "§4"
                    Berserk -> "§6"
                    Healer -> "§5"
                    Mage -> "§3"
                    Tank -> "§2"
                    else -> "§7"
                }
            }
        }
    }

    data class DungeonPlayer(
        var name: String,
        var clazz: Classes,
        var clazzLvl: Int,
        var skin: ResourceLocation = mc.thePlayer.locationSkin,
        var entity: EntityPlayer? = null,
        var isDead: Boolean = false,
    ) {
        val mapIcon = DungeonMapPlayer(this, skin)
        val clearInfo = ClearInfo()
    }

    enum class Blessing(
        var regex: Regex,
        val displayString: String,
        var current: Int = 0
    ) {
        POWER(Regex("Blessing of Power (X{0,3}(IX|IV|V?I{0,3}))"), "Power"),
        LIFE(Regex("Blessing of Life (X{0,3}(IX|IV|V?I{0,3}))"), "Life"),
        WISDOM(Regex("Blessing of Wisdom (X{0,3}(IX|IV|V?I{0,3}))"), "Wisdom"),
        STONE(Regex("Blessing of Stone (X{0,3}(IX|IV|V?I{0,3}))"), "Stone"),
        TIME(Regex("Blessing of Time (V)"), "Time");

        companion object {
            fun reset() = entries.forEach { it.current = 0 }
        }
    }

    @JvmStatic
    fun isSecret(pos: BlockPos): Boolean {
        val block = getBlockAt(pos)

        return when {
            block is BlockSkull -> (mc.theWorld?.getTileEntity(pos) as? TileEntitySkull)?.playerProfile?.id.toString().equalsOneOf(WITHER_ESSENCE_ID, REDSTONE_KEY_ID)
            block.equalsOneOf(Blocks.chest, Blocks.trapped_chest, Blocks.lever) -> true
            else -> false
        }
    }

    fun isPaul(): Boolean {
        val mayorPerks = mutableListOf<DataClasses.ApiMayor.Perk>()
        mayorData?.mayor?.perks?.let { mayorPerks.addAll(it) }
        mayorData?.mayor?.minister?.perks?.let { mayorPerks.addAll(it) }
        return mayorPerks.any { it.name.contains("EZPZ") }
    }

    private fun updateDungeonTeammates() {
        if (DevOptions.devMode) {
            listOf(
                DungeonPlayer("Noamm", Classes.Mage, 50, isDead = false),
                DungeonPlayer("Noamm9", Classes.Archer, 50, isDead = false),
                DungeonPlayer("NoammALT", Classes.Healer, 50, isDead = true, entity = mc.theWorld.getPlayerEntityByName("NoammALT")),
                DungeonPlayer("NoamIsSad", Classes.Tank, 50, isDead = false),
            ).let { list ->
                dungeonTeammates.clear()
                dungeonTeammates.addAll(list)

                thePlayer = dungeonTeammates.find { it.entity == mc.thePlayer }
                thePlayer?.isDead = mc.thePlayer.inventory.getStackInSlot(0)?.skyblockID == "HAUNT_ABILITY"
                dungeonTeammatesNoSelf = dungeonTeammates.filterNot { it == thePlayer }
                leapTeammates = dungeonTeammatesNoSelf.sortedBy { it.clazz }
                return
            }
        }

        val tabList = getTabList.takeIf { it.size >= 18 || it[0].second.contains("§r§b§lParty §r§f(") } ?: return
        for ((networkPlayerInfo, line) in tabList) {
            val (sbLvl, name, clazz, clazzLevel) = tablistRegex.find(line.removeFormatting())?.destructured ?: continue
            runPlayersNames[name] = networkPlayerInfo.locationSkin
            if (clazz == "EMPTY") continue

            val currentTeammate = dungeonTeammates.find { it.name == name }

            if (currentTeammate != null) {
                currentTeammate.clazz = if (clazz != "DEAD") Classes.getByName(clazz) else currentTeammate.clazz
                currentTeammate.clazzLvl = clazzLevel.romanToDecimal()
                currentTeammate.skin = networkPlayerInfo.locationSkin
                currentTeammate.entity = mc.theWorld.getPlayerEntityByName(name)
                currentTeammate.isDead = clazz == "DEAD"
            }
            else dungeonTeammates.add(
                DungeonPlayer(
                    name,
                    Classes.getByName(clazz),
                    clazzLevel.romanToDecimal(),
                    networkPlayerInfo.locationSkin,
                    mc.theWorld.getPlayerEntityByName(name),
                    clazz == "DEAD",
                )
            )
        }

        thePlayer = dungeonTeammates.find { it.entity == mc.thePlayer }
        thePlayer?.isDead = mc.thePlayer.inventory.getStackInSlot(0)?.skyblockID == "HAUNT_ABILITY"
        dungeonTeammatesNoSelf = dungeonTeammates.filter { it != thePlayer }
        leapTeammates = dungeonTeammatesNoSelf.sortedBy { it.clazz }
        val aliveTeammates = dungeonTeammatesNoSelf.filterNot { it.isDead }

        aliveTeammates.forEachIndexed { i, teammate ->
            teammate.mapIcon.icon = "icon-$i"
        }

        thePlayer?.takeIf { ! it.isDead }?.mapIcon?.apply {
            val last = dungeonTeammates.filterNot { it.isDead }.lastIndex
            icon = "icon-$last"
        }
    }

    fun updatePuzzles() {
        val tabList = getTabList.map { it.second.removeFormatting() }
        if (tabList.size < 60) return

        val oldInfoByName = puzzles.associateBy { it.name }
        val newPuzzleInfo = tabList.slice(48 .. 52).mapNotNull { line ->
            val name = puzzleRegex.find(line)?.destructured?.component1() ?: return@mapNotNull null
            val state = when {
                "✔" in line -> RoomState.GREEN
                "✖" in line -> RoomState.FAILED
                "✦" in line -> RoomState.DISCOVERED
                else -> RoomState.UNOPENED
            }
            Puzzle(name, state)
        }

        newPuzzleInfo.forEach { new ->
            val old = oldInfoByName[new.name] ?: run {
                postAndCatch(DungeonEvent.PuzzleEvent.Discovered(new.name))
                return@forEach
            }

            if (old.state == new.state) return@forEach

            postAndCatch(
                when {
                    old.state == RoomState.DISCOVERED && new.state == RoomState.GREEN -> DungeonEvent.PuzzleEvent.Completed(new.name)
                    old.state != RoomState.DISCOVERED && new.state == RoomState.DISCOVERED -> DungeonEvent.PuzzleEvent.Reset(new.name)
                    old.state == RoomState.DISCOVERED && new.state == RoomState.FAILED -> DungeonEvent.PuzzleEvent.Failed(new.name)
                    else -> return@forEach
                }
            )
        }

        puzzles.clear()
        puzzles.addAll(newPuzzleInfo)
    }

    @SubscribeEvent
    fun onPacket(event: PostPacketEvent.Received) {
        if (! inDungeon) return

        when (val packet = event.packet) {
            is S38PacketPlayerListItem -> {
                if (! packet.action.equalsOneOf(S38PacketPlayerListItem.Action.UPDATE_DISPLAY_NAME, S38PacketPlayerListItem.Action.ADD_PLAYER)) return
                ThreadUtils.scheduledTask(1, ::updateDungeonTeammates)
                ThreadUtils.scheduledTask(1, ::updatePuzzles)
            }

            is S47PacketPlayerListHeaderFooter -> Blessing.entries.forEach { blessing ->
                blessing.regex.find(packet.footer?.unformattedText?.removeFormatting() ?: return@forEach)?.let {
                    blessing.current = it.groupValues[1].romanToDecimal()
                }
            }
        }
    }

    @SubscribeEvent
    fun onRoomStateChange(event: DungeonEvent.RoomEvent.onStateChange) {
        if (lastDoorOpenner == null) return
        if (event.room.data.type != RoomType.BLOOD) return
        if (! event.newState.equalsOneOf(RoomState.DISCOVERED, RoomState.CLEARED, RoomState.GREEN)) return
        lastDoorOpenner = null
    }

    @SubscribeEvent
    fun onChat(event: Chat) {
        if (! inDungeon) return
        val text = event.component.formattedText
        val unformatted = text.removeFormatting()

        when {
            unformatted.matches(runEndRegex) -> {
                dungeonEnded = true
                dungeonEndTime = System.currentTimeMillis()
                scope.launch { postAndCatch(DungeonEvent.RunEndedEvent()) }
            }

            text == bloodOpenedString -> DungeonInfo.keys --

            "§r§c ☠" in text && "reconnected" !in unformatted -> {
                val match = deathRegex.find(unformatted) ?: return
                val username = match.groups["username"]?.value ?: mc.session.username
                val reason = match.groups["reason"]?.value ?: ""
                scope.launch {
                    while (thePlayer == null) delay(1)
                    postAndCatch(DungeonEvent.PlayerDeathEvent(username, reason))
                }
            }

            unformatted == "[BOSS] The Watcher: You have proven yourself. You may pass." -> {
                DungeonInfo.dungeonList.find { it is Room && it.data.type == RoomType.BLOOD }?.state = RoomState.GREEN
                watcherClearTime = System.currentTimeMillis()
            }

            unformatted == "[BOSS] The Watcher: That will be enough for now." -> {
                DungeonInfo.dungeonList.find { it is Room && it.data.type == RoomType.BLOOD }?.state = RoomState.CLEARED
                watcherSpawnTime = System.currentTimeMillis()
            }

            watcherMessageRegex.matches(unformatted) && bloodOpenTime == null -> {
                bloodOpenTime = System.currentTimeMillis()
            }

            unformatted == "[NPC] Mort: Here, I found this map when I first entered the dungeon." -> scope.launch {
                dungeonStartTime = System.currentTimeMillis()
                while (thePlayer == null) delay(1)
                postAndCatch(DungeonEvent.RunStatedEvent())
            }

            else -> {
                witherDoorOpenedRegex.find(unformatted)?.destructured?.let { (name) ->
                    lastDoorOpenner = dungeonTeammates.find { it.name == name }
                    DungeonInfo.keys --
                    return
                }

                keyPickupRegex.find(text)?.destructured?.let { (num) ->
                    DungeonInfo.keys += num.toInt()
                    return
                }
            }
        }
    }


    @SubscribeEvent
    fun reset(event: WorldUnloadEvent) {
        dungeonTeammates.clear()
        dungeonTeammatesNoSelf = emptyList()
        leapTeammates = emptyList()
        thePlayer = null
        maxSecrets = null
        secrets = null
        puzzles.clear()
        dungeonEnded = false
        runPlayersNames.clear()
        dungeonStartTime = null
        bloodOpenTime = null
        watcherClearTime = null
        watcherSpawnTime = null
        bossEntryTime = null
        dungeonEndTime = null
        Blessing.reset()
    }
}
