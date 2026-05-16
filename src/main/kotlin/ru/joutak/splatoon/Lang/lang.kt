package ru.joutak.splatoon.lang

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.ChatColor
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

object Lang {

    private lateinit var config: FileConfiguration
    private val serializer = LegacyComponentSerializer.legacySection()

    fun load(plugin: JavaPlugin) {
        val file = File(plugin.dataFolder, "lang.yml")

        if (!file.exists()) {
            plugin.saveResource("lang.yml", false)
            plugin.logger.info("lang.yml created")
        }

        config = YamlConfiguration.loadConfiguration(file)
        plugin.logger.info("Lang loaded")
    }

    fun reload(plugin: JavaPlugin) {
        load(plugin)
        plugin.logger.info("Lang reloaded")
    }

    // 🔹 Просто строка
    fun get(path: String): String {
        return colorize(config.getString(path) ?: "§cMissing lang key: $path")
    }

    // 🔹 С плейсхолдерами
    fun get(path: String, vararg replacements: Pair<String, String>): String {
        var text = config.getString(path) ?: "§cMissing lang key: $path"

        replacements.forEach { (key, value) ->
            text = text.replace("%$key%", value)
        }

        return colorize(text)
    }

    // 🔹 Список строк (БЕЗ плейсхолдеров)
    fun getList(path: String): List<String> {
        return config.getStringList(path).map { colorize(it) }
    }

    // 🔥 Список строк С плейсхолдерами (ГЛАВНОЕ ДОБАВЛЕНИЕ)
    fun getList(path: String, vararg replacements: Pair<String, String>): List<String> {
        return config.getStringList(path).map { line ->
            var result = line

            replacements.forEach { (key, value) ->
                result = result.replace("%$key%", value)
            }

            colorize(result)
        }
    }

    // 🔥 Component
    fun component(path: String): Component {
        return serializer.deserialize(get(path))
    }

    fun component(path: String, vararg replacements: Pair<String, String>): Component {
        return serializer.deserialize(get(path, *replacements))
    }

    fun componentList(path: String): List<Component> {
        return getList(path).map { serializer.deserialize(it) }
    }

    // 🔥 ComponentList с плейсхолдерами (ТОЖЕ ВАЖНО)
    fun componentList(path: String, vararg replacements: Pair<String, String>): List<Component> {
        return getList(path, *replacements).map { serializer.deserialize(it) }
    }

    // 🔹 Перевод & → §
    private fun colorize(text: String): String {
        return ChatColor.translateAlternateColorCodes('&', text)
    }
}