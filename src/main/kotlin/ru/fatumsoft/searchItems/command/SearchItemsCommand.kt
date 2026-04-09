package ru.fatumsoft.searchItems.command

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Barrel
import org.bukkit.block.BlockState
import org.bukkit.block.Chest
import org.bukkit.block.ShulkerBox
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.concurrent.LinkedBlockingQueue
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor

class SearchItemsCommand(private val plugin: JavaPlugin) : CommandExecutor, TabCompleter {

    private val scanInProgress = AtomicBoolean(false)
    private var activeTask: BukkitTask? = null
    private var activeSession: ScanSession? = null
    private val stateFile: Path by lazy { plugin.dataFolder.toPath().resolve("scan-state.properties") }

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        if (args.size == 1 && args[0].equals("reload", ignoreCase = true)) {
            if (!sender.hasPermission(PERMISSION_RELOAD)) {
                sender.sendMessage("§cУ вас нет прав на перезагрузку конфига.")
                return true
            }
            plugin.reloadConfig()
            sender.sendMessage("§aКонфиг SearchItems успешно перезагружен.")
            return true
        }
        if (args.size == 1 && args[0].equals("stop", ignoreCase = true)) {
            if (!sender.hasPermission(PERMISSION_STOP)) {
                sender.sendMessage("§cУ вас нет прав для остановки сканирования.")
                return true
            }
            if (!scanInProgress.get()) {
                sender.sendMessage("§eАктивного сканирования нет.")
                return true
            }
            stopScan(sender, "§eСканирование остановлено вручную.", persistProgress = true)
            return true
        }

        if (!sender.hasPermission(PERMISSION_USE)) {
            sender.sendMessage("§cУ вас нет прав на эту команду.")
            return true
        }

        if (sender !is Player) {
            sender.sendMessage("§cКоманда доступна только игроку.")
            return true
        }

        if (args.size < 2) {
            sender.sendMessage("§eИспользование: /$label <радиус_блоков> <item1,item2,...> [loaded|generated]")
            return true
        }

        val radiusBlocks = args[0].toIntOrNull()
        if (radiusBlocks == null || radiusBlocks < 0) {
            sender.sendMessage("§cРадиус должен быть неотрицательным числом.")
            return true
        }

        val maxRadius = plugin.config.getInt("search.max-radius-blocks", 10_000)
        if (radiusBlocks > maxRadius) {
            sender.sendMessage("§cМаксимальный радиус: $maxRadius блоков.")
            return true
        }

        val targets = parseMaterials(args[1])
        if (targets.isEmpty()) {
            sender.sendMessage("§cНе найдено валидных материалов в списке: ${args[1]}")
            return true
        }

        val mode = parseScanMode(args.getOrNull(2), plugin.config.getString("search.default-scan-mode", "generated"))
        if (mode == null) {
            sender.sendMessage("§cРежим сканирования должен быть loaded или generated.")
            return true
        }

        if (!scanInProgress.compareAndSet(false, true)) {
            sender.sendMessage("§cСканирование уже выполняется. Дождитесь завершения.")
            return true
        }

        startScan(sender, radiusBlocks, targets, mode)
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        if (!sender.hasPermission(PERMISSION_USE) &&
            !sender.hasPermission(PERMISSION_RELOAD) &&
            !sender.hasPermission(PERMISSION_STOP)
        ) return emptyList()

        if (args.size == 1) {
            val suggestions = ArrayList<String>(6)
            if (sender.hasPermission(PERMISSION_RELOAD)) {
                suggestions.add("reload")
            }
            if (sender.hasPermission(PERMISSION_STOP)) {
                suggestions.add("stop")
            }
            suggestions.addAll(listOf("16", "32", "64", "128", "256"))
            return suggestions.filter { it.startsWith(args[0]) }
        }

        if (args.size == 2) {
            val input = args[1].uppercase(Locale.ROOT)
            val parts = input.split(',')
            val prefix = parts.lastOrNull().orEmpty()
            val selected = parts.dropLast(1).toHashSet()

            val basePrefix = if (parts.size > 1) {
                parts.dropLast(1).joinToString(",") + ","
            } else {
                ""
            }

            return Material.entries
                .asSequence()
                .map { it.name }
                .filter { it !in selected }
                .filter { it.startsWith(prefix) }
                .take(50)
                .map { basePrefix + it }
                .toList()
        }

        if (args.size == 3) {
            val modes = listOf("generated", "loaded")
            return modes.filter { it.startsWith(args[2].lowercase(Locale.ROOT)) }
        }

        return emptyList()
    }

    fun shutdown() {
        stopScan(null, null, persistProgress = true)
    }

    private fun startScan(player: Player, radiusBlocks: Int, targets: Set<Material>, mode: ScanMode) {
        val world = player.world

        val minChunkX = floor((-radiusBlocks) / 16.0).toInt()
        val maxChunkX = floor((radiusBlocks) / 16.0).toInt()
        val minChunkZ = floor((-radiusBlocks) / 16.0).toInt()
        val maxChunkZ = floor((radiusBlocks) / 16.0).toInt()

        val chunkBatch = plugin.config.getInt("search.chunks-per-tick", 6).coerceAtLeast(1)
        val progressEveryTicks = plugin.config.getInt("search.progress-message-every-ticks", 100).coerceAtLeast(20)
        val signature = buildSignature(world.name, radiusBlocks, targets, mode)
        val resumedState = loadState(signature)
        val iterator = if (resumedState != null) {
            SpiralChunkCursor(minChunkX, maxChunkX, minChunkZ, maxChunkZ, resumedState.spiralState)
        } else {
            SpiralChunkCursor(minChunkX, maxChunkX, minChunkZ, maxChunkZ)
        }
        val totalChunks = iterator.total
        val writer = StreamingReportWriter(plugin)
        val reportPath = writer.start(resumedState?.reportPath)
        activeSession = ScanSession(
            writer = writer,
            reportPath = reportPath,
            signature = signature,
            world = world.name,
            radius = radiusBlocks,
            mode = mode,
            cursor = iterator,
            scannedEligible = resumedState?.scannedEligible ?: 0,
            foundStorages = resumedState?.foundStorages ?: 0
        )

        notifyPlayer(player, "§7Запущено сканирование от центра мира (0,0) по спирали: $totalChunks чанков, режим: ${mode.configValue}...")
        if (resumedState != null) {
            notifyPlayer(player, "§eПродолжение с сохраненного прогресса: проверено ${resumedState.spiralState.processed}/${totalChunks}.")
        }

        var ticks = 0
        var scannedEligible = resumedState?.scannedEligible ?: 0
        var foundStorages = resumedState?.foundStorages ?: 0

        activeTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            repeat(chunkBatch) {
                val coordinate = iterator.next() ?: run {
                    finishScan(player, scannedEligible, foundStorages)
                    return@Runnable
                }

                val localHits = ArrayList<StorageHit>(4)
                val scanned = scanChunk(world, coordinate, targets, localHits, mode)
                if (scanned) {
                    scannedEligible++
                }
                if (localHits.isNotEmpty()) {
                    foundStorages += localHits.size
                    activeSession?.writer?.append(localHits.map(::formatEntry))
                }
                activeSession?.scannedEligible = scannedEligible
                activeSession?.foundStorages = foundStorages
            }

            ticks++
            if (ticks % progressEveryTicks == 0) {
                notifyPlayer(player, "§7Прогресс: проверено ${iterator.processed}/${iterator.total} чанков; подходящих: $scannedEligible; найдено хранилищ: $foundStorages")
                persistCurrentSessionState()
            }
        }, 1L, 1L)
    }

    private fun finishScan(player: Player, scannedEligible: Int, foundStorages: Int) {
        val reportPath = activeSession?.reportPath
        stopScan(player, null, persistProgress = false)
        notifyPlayer(player, "§aГотово. Проверено подходящих чанков: $scannedEligible; найдено хранилищ: $foundStorages")
        if (reportPath != null) {
            notifyPlayer(player, "§aОтчёт: ${reportPath.toAbsolutePath()}")
        }
    }

    private fun stopScan(sender: CommandSender?, message: String?, persistProgress: Boolean) {
        if (persistProgress) {
            persistCurrentSessionState()
        } else {
            clearState()
        }
        activeTask?.cancel()
        activeTask = null
        activeSession?.writer?.close()
        activeSession = null
        scanInProgress.set(false)
        if (sender != null && message != null) {
            sender.sendMessage(message)
        }
    }

    private fun notifyPlayer(player: Player, message: String) {
        if (player.isOnline) {
            player.sendMessage(message)
        } else {
            plugin.logger.info("[SearchItems] ${player.name}: $message")
        }
    }

    private fun persistCurrentSessionState() {
        val session = activeSession ?: return
        saveState(
            signature = session.signature,
            world = session.world,
            radius = session.radius,
            mode = session.mode,
            reportPath = session.reportPath,
            cursor = session.cursor,
            scannedEligible = session.scannedEligible,
            foundStorages = session.foundStorages
        )
    }

    private fun scanChunk(
        world: World,
        coordinate: ChunkCoordinate,
        targets: Set<Material>,
        out: MutableList<StorageHit>,
        mode: ScanMode
    ): Boolean {
        if (mode == ScanMode.GENERATED && !world.isChunkGenerated(coordinate.x, coordinate.z)) {
            return false
        }
        if (mode == ScanMode.LOADED && !world.isChunkLoaded(coordinate.x, coordinate.z)) {
            return false
        }

        val wasLoaded = world.isChunkLoaded(coordinate.x, coordinate.z)
        val chunk = when {
            wasLoaded -> world.getChunkAt(coordinate.x, coordinate.z)
            mode == ScanMode.GENERATED -> world.getChunkAt(coordinate.x, coordinate.z, false)
            else -> return false
        }

        val tileEntities = chunk.tileEntities
        if (tileEntities.isEmpty()) {
            unloadIfNeeded(world, coordinate, wasLoaded, mode)
            return true
        }

        val localHits = ArrayList<StorageHit>(4)
        var chunkWeight = 0

        for (state in tileEntities) {
            if (!isSupportedContainer(state)) continue

            val inventory = (state as org.bukkit.inventory.InventoryHolder).inventory
            val count = countMatchesInInventory(inventory, targets)
            if (count <= 0) continue

            chunkWeight += count
            localHits.add(StorageHit(
                world = chunk.world.name,
                x = state.x,
                y = state.y,
                z = state.z,
                itemCount = count,
                chunkX = chunk.x,
                chunkZ = chunk.z,
                chunkWeight = 0
            ))
        }

        if (localHits.isEmpty()) {
            unloadIfNeeded(world, coordinate, wasLoaded, mode)
            return true
        }

        localHits.forEach { it.chunkWeight = chunkWeight }
        out.addAll(localHits)
        unloadIfNeeded(world, coordinate, wasLoaded, mode)
        return true
    }

    private fun unloadIfNeeded(
        world: World,
        coordinate: ChunkCoordinate,
        wasLoaded: Boolean,
        mode: ScanMode
    ) {
        if (!wasLoaded && mode == ScanMode.GENERATED) {
            world.unloadChunkRequest(coordinate.x, coordinate.z)
        }
    }

    private fun countMatchesInInventory(inventory: Inventory, targets: Set<Material>): Int {
        var total = 0
        val contents = inventory.contents
        for (item in contents) {
            if (item == null || item.type == Material.AIR) continue
            total += countItemAndNested(item, targets, 0)
        }
        return total
    }

    private fun countItemAndNested(item: ItemStack, targets: Set<Material>, depth: Int): Int {
        var sum = if (item.type in targets) item.amount else 0
        if (depth >= MAX_SHULKER_DEPTH) return sum

        val meta = item.itemMeta
        if (meta is BlockStateMeta && meta.hasBlockState()) {
            val blockState = meta.blockState
            if (blockState is ShulkerBox) {
                val nested = blockState.inventory.contents
                for (nestedItem in nested) {
                    if (nestedItem == null || nestedItem.type == Material.AIR) continue
                    sum += countItemAndNested(nestedItem, targets, depth + 1)
                }
            }
        }

        return sum
    }

    private fun isSupportedContainer(state: BlockState): Boolean {
        return state is Chest || state is Barrel || state is ShulkerBox
    }

    private fun formatEntry(entry: StorageHit): String {
        val tp = "/minecraft:tp ${entry.x} ${entry.y} ${entry.z}"
        return "chunkWeight=${entry.chunkWeight}; items=${entry.itemCount}; world=${entry.world}; chunk=${entry.chunkX},${entry.chunkZ}; pos=${entry.x},${entry.y},${entry.z}; tp=${tp}"
    }

    private fun parseMaterials(raw: String): Set<Material> {
        return raw.split(',')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { token ->
                val normalized = token.uppercase(Locale.ROOT).removePrefix("MINECRAFT:")
                MATERIAL_BY_NAME[normalized]
            }
            .toCollection(LinkedHashSet())
    }

    private fun parseScanMode(rawArg: String?, configValue: String?): ScanMode? {
        val value = (rawArg ?: configValue ?: "generated").lowercase(Locale.ROOT)
        return when (value) {
            "generated" -> ScanMode.GENERATED
            "loaded" -> ScanMode.LOADED
            else -> null
        }
    }

    private data class ChunkCoordinate(val x: Int, val z: Int)

    private data class SpiralState(
        val currentX: Int,
        val currentZ: Int,
        val legLength: Int,
        val legProgress: Int,
        val legChanges: Int,
        val directionIndex: Int,
        val yielded: Long,
        val processed: Long
    )

    private class SpiralChunkCursor(
        private val minX: Int,
        private val maxX: Int,
        private val minZ: Int,
        private val maxZ: Int,
        state: SpiralState? = null
    ) {
        private var currentX: Int = state?.currentX ?: 0
        private var currentZ: Int = state?.currentZ ?: 0
        private var legLength: Int = state?.legLength ?: 1
        private var legProgress: Int = state?.legProgress ?: 0
        private var legChanges: Int = state?.legChanges ?: 0
        private var directionIndex: Int = state?.directionIndex ?: 0
        private var yielded: Long = state?.yielded ?: 0
        var processed: Long = state?.processed ?: 0
            private set

        val total: Long = (maxX - minX + 1).toLong() * (maxZ - minZ + 1).toLong()

        fun next(): ChunkCoordinate? {
            while (yielded < total) {
                val result = ChunkCoordinate(currentX, currentZ)
                advanceSpiral()
                if (result.x in minX..maxX && result.z in minZ..maxZ) {
                    processed++
                    yielded++
                    return result
                }
            }
            return null
        }

        private fun advanceSpiral() {
            when (directionIndex) {
                0 -> currentX++
                1 -> currentZ++
                2 -> currentX--
                else -> currentZ--
            }
            legProgress++
            if (legProgress >= legLength) {
                legProgress = 0
                directionIndex = (directionIndex + 1) % 4
                legChanges++
                if (legChanges % 2 == 0) {
                    legLength++
                }
            }
        }

        fun snapshot(): SpiralState {
            return SpiralState(
                currentX = currentX,
                currentZ = currentZ,
                legLength = legLength,
                legProgress = legProgress,
                legChanges = legChanges,
                directionIndex = directionIndex,
                yielded = yielded,
                processed = processed
            )
        }
    }

    private data class StorageHit(
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int,
        val itemCount: Int,
        val chunkX: Int,
        val chunkZ: Int,
        var chunkWeight: Int
    )

    private data class ScanSession(
        val writer: StreamingReportWriter,
        val reportPath: Path,
        val signature: String,
        val world: String,
        val radius: Int,
        val mode: ScanMode,
        val cursor: SpiralChunkCursor,
        var scannedEligible: Int,
        var foundStorages: Int
    )

    private class StreamingReportWriter(private val plugin: JavaPlugin) {
        private val queue = LinkedBlockingQueue<String>()
        @Volatile
        private var closed = false
        private var worker: BukkitTask? = null
        private lateinit var path: Path

        fun start(existingPath: Path?): Path {
            if (!plugin.dataFolder.exists()) {
                plugin.dataFolder.mkdirs()
            }
            path = existingPath ?: plugin.dataFolder.toPath().resolve("search-result-${LocalDateTime.now().format(TIME_FORMATTER)}.txt")
            if (existingPath == null || !Files.exists(path)) {
                Files.newBufferedWriter(path).use { writer ->
                    writer.write("# SearchItems streaming report")
                    writer.newLine()
                }
            }

            worker = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, Runnable {
                flushBatch(500)
            }, 1L, 1L)

            return path
        }

        fun append(lines: List<String>) {
            if (closed) return
            lines.forEach(queue::offer)
        }

        fun close() {
            closed = true
            worker?.cancel()
            worker = null
            flushBatch(Int.MAX_VALUE)
        }

        private fun flushBatch(limit: Int) {
            if (!::path.isInitialized) return
            var processed = 0
            Files.newBufferedWriter(path, java.nio.file.StandardOpenOption.APPEND).use { writer ->
                while (processed < limit) {
                    val line = queue.poll() ?: break
                    writer.write(line)
                    writer.newLine()
                    processed++
                }
            }
        }
    }

    companion object {
        private const val PERMISSION_USE = "searchitems.command.search"
        private const val PERMISSION_RELOAD = "searchitems.command.reload"
        private const val PERMISSION_STOP = "searchitems.command.stop"
        private const val MAX_SHULKER_DEPTH = 8
        private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        private val MATERIAL_BY_NAME: Map<String, Material> = Material.entries.associateBy { it.name }
    }

    private fun buildSignature(world: String, radius: Int, targets: Set<Material>, mode: ScanMode): String {
        val joined = targets.map { it.name }.sorted().joinToString(",")
        return "$world|$radius|${mode.configValue}|$joined"
    }

    private fun saveState(
        signature: String,
        world: String,
        radius: Int,
        mode: ScanMode,
        reportPath: Path,
        cursor: SpiralChunkCursor,
        scannedEligible: Int,
        foundStorages: Int
    ) {
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
        val s = cursor.snapshot()
        val props = Properties().apply {
            setProperty("signature", signature)
            setProperty("world", world)
            setProperty("radius", radius.toString())
            setProperty("mode", mode.configValue)
            setProperty("reportPath", reportPath.toString())
            setProperty("scannedEligible", scannedEligible.toString())
            setProperty("foundStorages", foundStorages.toString())
            setProperty("currentX", s.currentX.toString())
            setProperty("currentZ", s.currentZ.toString())
            setProperty("legLength", s.legLength.toString())
            setProperty("legProgress", s.legProgress.toString())
            setProperty("legChanges", s.legChanges.toString())
            setProperty("directionIndex", s.directionIndex.toString())
            setProperty("yielded", s.yielded.toString())
            setProperty("processed", s.processed.toString())
        }
        Files.newOutputStream(stateFile).use { props.store(it, "SearchItems scan state") }
    }

    private fun loadState(signature: String): PersistedState? {
        if (!Files.exists(stateFile)) return null
        return runCatching {
            val props = Properties()
            Files.newInputStream(stateFile).use { props.load(it) }
            if (props.getProperty("signature") != signature) return null
            val spiral = SpiralState(
                currentX = props.getProperty("currentX").toInt(),
                currentZ = props.getProperty("currentZ").toInt(),
                legLength = props.getProperty("legLength").toInt(),
                legProgress = props.getProperty("legProgress").toInt(),
                legChanges = props.getProperty("legChanges").toInt(),
                directionIndex = props.getProperty("directionIndex").toInt(),
                yielded = props.getProperty("yielded").toLong(),
                processed = props.getProperty("processed").toLong()
            )
            PersistedState(
                reportPath = Path.of(props.getProperty("reportPath")),
                scannedEligible = props.getProperty("scannedEligible").toInt(),
                foundStorages = props.getProperty("foundStorages").toInt(),
                spiralState = spiral
            )
        }.getOrNull()
    }

    private fun clearState() {
        if (Files.exists(stateFile)) {
            Files.delete(stateFile)
        }
    }

    private data class PersistedState(
        val reportPath: Path,
        val scannedEligible: Int,
        val foundStorages: Int,
        val spiralState: SpiralState
    )

    private enum class ScanMode(val configValue: String) {
        GENERATED("generated"),
        LOADED("loaded")
    }
}
