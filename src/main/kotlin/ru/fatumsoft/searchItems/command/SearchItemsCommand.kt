package ru.fatumsoft.searchItems.command

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Material
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
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor

class SearchItemsCommand(private val plugin: JavaPlugin) : CommandExecutor, TabCompleter {

    private val scanInProgress = AtomicBoolean(false)
    private var activeTask: BukkitTask? = null

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        if (!sender.hasPermission(PERMISSION_USE)) {
            sender.sendMessage("§cУ вас нет прав на эту команду.")
            return true
        }

        if (sender !is Player) {
            sender.sendMessage("§cКоманда доступна только игроку.")
            return true
        }

        if (args.size < 2) {
            sender.sendMessage("§eИспользование: /$label <радиус_блоков> <item1,item2,...>")
            return true
        }

        val radiusBlocks = args[0].toIntOrNull()
        if (radiusBlocks == null || radiusBlocks < 0) {
            sender.sendMessage("§cРадиус должен быть неотрицательным числом.")
            return true
        }

        val maxRadius = plugin.config.getInt("search.max-radius-blocks", 512)
        if (radiusBlocks > maxRadius) {
            sender.sendMessage("§cМаксимальный радиус: $maxRadius блоков.")
            return true
        }

        val targets = parseMaterials(args[1])
        if (targets.isEmpty()) {
            sender.sendMessage("§cНе найдено валидных материалов в списке: ${args[1]}")
            return true
        }

        if (!scanInProgress.compareAndSet(false, true)) {
            sender.sendMessage("§cСканирование уже выполняется. Дождитесь завершения.")
            return true
        }

        startScan(sender, radiusBlocks, targets)
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        if (!sender.hasPermission(PERMISSION_USE)) return emptyList()

        if (args.size == 1) {
            val suggestions = listOf("16", "32", "64", "128", "256")
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

        return emptyList()
    }

    fun shutdown() {
        activeTask?.cancel()
        activeTask = null
        scanInProgress.set(false)
    }

    private fun startScan(player: Player, radiusBlocks: Int, targets: Set<Material>) {
        val location = player.location
        val world = location.world
        if (world == null) {
            scanInProgress.set(false)
            player.sendMessage("§cНе удалось определить мир игрока.")
            return
        }

        val minChunkX = floor((location.x - radiusBlocks) / 16.0).toInt()
        val maxChunkX = floor((location.x + radiusBlocks) / 16.0).toInt()
        val minChunkZ = floor((location.z - radiusBlocks) / 16.0).toInt()
        val maxChunkZ = floor((location.z + radiusBlocks) / 16.0).toInt()

        val chunksToScan = ArrayList<Chunk>((maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1))
        for (cx in minChunkX..maxChunkX) {
            for (cz in minChunkZ..maxChunkZ) {
                if (world.isChunkLoaded(cx, cz)) {
                    chunksToScan.add(world.getChunkAt(cx, cz))
                }
            }
        }

        if (chunksToScan.isEmpty()) {
            scanInProgress.set(false)
            player.sendMessage("§eВ указанном радиусе нет загруженных чанков для проверки.")
            return
        }

        val chunkBatch = plugin.config.getInt("search.chunks-per-tick", 6).coerceAtLeast(1)
        val storages = ArrayList<StorageHit>()
        var index = 0

        player.sendMessage("§7Запущено сканирование: ${chunksToScan.size} загруженных чанков...")

        activeTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (!player.isOnline) {
                finishScan(player, storages)
                return@Runnable
            }

            repeat(chunkBatch) {
                if (index >= chunksToScan.size) {
                    finishScan(player, storages)
                    return@Runnable
                }

                val chunk = chunksToScan[index++]
                scanChunk(chunk, targets, storages)
            }
        }, 1L, 1L)
    }

    private fun finishScan(player: Player, storages: MutableList<StorageHit>) {
        activeTask?.cancel()
        activeTask = null

        storages.sortByDescending { it.chunkWeight }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val file = writeReport(storages)
            Bukkit.getScheduler().runTask(plugin, Runnable {
                scanInProgress.set(false)
                player.sendMessage("§aГотово. Найдено хранилищ: ${storages.size}")
                player.sendMessage("§aОтчёт: ${file.toAbsolutePath()}")
            })
        })
    }

    private fun scanChunk(chunk: Chunk, targets: Set<Material>, out: MutableList<StorageHit>) {
        val tileEntities = chunk.tileEntities
        if (tileEntities.isEmpty()) return

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

        if (localHits.isEmpty()) return

        localHits.forEach { it.chunkWeight = chunkWeight }
        out.addAll(localHits)
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

    private fun writeReport(storages: List<StorageHit>): Path {
        if (!plugin.dataFolder.exists()) {
            plugin.dataFolder.mkdirs()
        }

        val fileName = "search-result-${LocalDateTime.now().format(TIME_FORMATTER)}.txt"
        val filePath = plugin.dataFolder.toPath().resolve(fileName)

        Files.newBufferedWriter(filePath).use { writer ->
            writer.write("# SearchItems report")
            writer.newLine()
            writer.write("# Entries: ${storages.size}")
            writer.newLine()
            writer.newLine()

            for (entry in storages) {
                writer.write(formatEntry(entry))
                writer.newLine()
            }
        }

        return filePath
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
            .mapNotNull { Material.matchMaterial(it, true) }
            .toCollection(LinkedHashSet())
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

    companion object {
        private const val PERMISSION_USE = "searchitems.command.search"
        private const val MAX_SHULKER_DEPTH = 8
        private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    }
}
