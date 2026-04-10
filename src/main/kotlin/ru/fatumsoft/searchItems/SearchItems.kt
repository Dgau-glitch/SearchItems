package ru.fatumsoft.searchItems

import org.bukkit.plugin.java.JavaPlugin
import ru.fatumsoft.searchItems.command.SearchItemsCommand

class SearchItems : JavaPlugin() {

    private lateinit var searchCommand: SearchItemsCommand

    override fun onEnable() {
        saveDefaultConfig()

        searchCommand = SearchItemsCommand(this)
        getCommand("searchitems")?.apply {
            setExecutor(searchCommand)
            tabCompleter = searchCommand
        }
    }

    override fun onDisable() {
        if (this::searchCommand.isInitialized) {
            searchCommand.shutdown()
        }
    }
}
