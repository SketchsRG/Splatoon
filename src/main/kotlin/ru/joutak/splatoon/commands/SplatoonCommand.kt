package ru.joutak.splatoon.commands

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import ru.joutak.splatoon.SplatoonPlugin
import ru.joutak.splatoon.config.SplatoonSettings
import ru.joutak.splatoon.scripts.Game
import ru.joutak.splatoon.scripts.GameManager

class SplatoonCommand(private val plugin: SplatoonPlugin) : CommandExecutor, TabCompleter {

    private fun hasAdmin(sender: CommandSender): Boolean {
        return sender.hasPermission("splatoon.admin") || sender.isOp
    }

    private fun formatTime(secondsTotal: Int): String {
        val s = secondsTotal.coerceAtLeast(0)
        val m = s / 60
        val sec = s % 60
        return "$m:${sec.toString().padStart(2, '0')}"
    }

    private fun resolveGame(sender: CommandSender, token: String): Game? {
        val t = token.trim()
        if (t.equals("here", ignoreCase = true)) {
            val p = sender as? Player ?: return null
            return GameManager.getGameByWorld(p.world.name)
        }

        if (t.startsWith("#")) {
            val idx = t.removePrefix("#").toIntOrNull() ?: return null
            val list = GameManager.getActiveGames().sortedBy { it.worldName }
            return list.getOrNull(idx - 1)
        }

        GameManager.getGameByWorld(t)?.let { return it }

        return GameManager.getActiveGames().firstOrNull { it.arenaId.equals(t, ignoreCase = true) }
    }

    private fun sendHelp(sender: CommandSender, label: String) {
        sender.sendMessage("§6Splatoon admin:")
        sender.sendMessage("§e/$label get <gun|bomb|bacillus> §7- выдать предмет")
        sender.sendMessage("§e/$label games §7- список активных игр")
        sender.sendMessage("§e/$label spectate <id|world|arena|here> §7- наблюдать")
        sender.sendMessage("§e/$label unspectate §7- выйти из наблюдения")
        sender.sendMessage("§e/$label phase skip <id|world|arena|here> §7- скип фазы (COUNTDOWN→RUNNING→ENDING→CLEANUP)")
        sender.sendMessage("§e/$label skip <seconds> <id|world|arena|here> §7- скип секунд (countdown/таймер)")
        sender.sendMessage("§e/$label time set <seconds> <id|world|arena|here> §7- выставить время (RUNNING)")
        sender.sendMessage("§e/$label time add <deltaSeconds> <id|world|arena|here> §7- добавить/убрать время (RUNNING)")
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty() || args[0].equals("help", ignoreCase = true)) {
            sendHelp(sender, label)
            return true
        }

        val sub = args[0].lowercase()
        when (sub) {
            "skin" -> {
                if (sender !is Player) {
                    sender.sendMessage("§cТолько для игроков")
                    return true
                }

                val skins = SplatoonSettings.skinsInventory

                if (skins == null) {
                    sender.sendMessage("§cСкины отсутствуют")
                    return true
                }

                (sender as Player).openInventory(skins as org.bukkit.inventory.Inventory)
                return true
            }

            "get" -> {
                if (sender !is Player) {
                    sender.sendMessage("§cТолько для игроков")
                    return true
                }
                if (!hasAdmin(sender)) {
                    sender.sendMessage("§cНедостаточно прав")
                    return true
                }
                if (args.size < 2) {
                    sender.sendMessage("§cИспользование: /$label get <gun|bomb|bacillus>")
                    return true
                }

                when (args[1].lowercase()) {
                    "gun" -> {
                        sender.inventory.addItem(AdminItems.gun())
                        sender.sendMessage("§aВыдано: Сплат-пушка")
                    }
                    "bomb" -> {
                        sender.inventory.addItem(AdminItems.bomb())
                        sender.sendMessage("§aВыдано: Сплат-бомба")
                    }
                    "bacillus" -> {
                        sender.inventory.addItem(AdminItems.bacillus())
                        sender.sendMessage("§aВыдано: Бацилла")
                    }
                    else -> sender.sendMessage("§cНеизвестный предмет. Доступно: gun, bomb, bacillus")
                }
                return true
            }

            "games", "status", "list" -> {
                if (!hasAdmin(sender)) {
                    sender.sendMessage("§cНедостаточно прав")
                    return true
                }

                val games = GameManager.getActiveGames().sortedBy { it.worldName }
                if (games.isEmpty()) {
                    sender.sendMessage("§eСейчас нет активных игр.")
                    return true
                }

                sender.sendMessage("§6Активные игры:")
                games.forEachIndexed { index, game ->
                    sender.sendMessage(
                        "§e#${index + 1} §7arena=§f${game.arenaId} §7world=§f${game.worldName} " +
                            "§7players=§f${game.getActivePlayerCount()} §7spec=§f${game.getSpectatorCount()} " +
                            "§7time=§f${formatTime(game.getTimeLeftSeconds())}"
                    )
                }
                sender.sendMessage("§7/$label spectate #<id> §8или §7/$label spectate here")
                return true
            }


            "phase" -> {
                if (!hasAdmin(sender)) {
                    sender.sendMessage("§cНедостаточно прав")
                    return true
                }
                val action = args.getOrNull(1)
                if (action == null || !action.equals("skip", ignoreCase = true)) {
                    sender.sendMessage("§cИспользование: /$label phase skip <id|world|arena|here>")
                    return true
                }
                val token = args.getOrNull(2) ?: run {
                    if (sender is Player) "here" else null
                }
                if (token == null) {
                    sender.sendMessage("§cДля консоли укажи id/world/arena")
                    return true
                }
                val game = resolveGame(sender, token)
                if (game == null) {
                    sender.sendMessage("§cИгра не найдена. Используй: /$label games")
                    return true
                }
                sender.sendMessage(game.adminSkipPhase())
                return true
            }

            "skip" -> {
                if (!hasAdmin(sender)) {
                    sender.sendMessage("§cНедостаточно прав")
                    return true
                }
                val sec = args.getOrNull(1)?.toIntOrNull()
                if (sec == null) {
                    sender.sendMessage("§cИспользование: /$label skip <seconds> <id|world|arena|here>")
                    return true
                }
                val token = args.getOrNull(2) ?: run {
                    if (sender is Player) "here" else null
                }
                if (token == null) {
                    sender.sendMessage("§cДля консоли укажи id/world/arena")
                    return true
                }
                val game = resolveGame(sender, token)
                if (game == null) {
                    sender.sendMessage("§cИгра не найдена. Используй: /$label games")
                    return true
                }
                sender.sendMessage(game.adminSkipSeconds(sec))
                return true
            }

            "time" -> {
                if (!hasAdmin(sender)) {
                    sender.sendMessage("§cНедостаточно прав")
                    return true
                }
                val mode = args.getOrNull(1)?.lowercase()
                val sec = args.getOrNull(2)?.toIntOrNull()
                if (mode == null || sec == null || (mode != "set" && mode != "add")) {
                    sender.sendMessage("§cИспользование: /$label time <set|add> <seconds> <id|world|arena|here>")
                    return true
                }
                val token = args.getOrNull(3) ?: run {
                    if (sender is Player) "here" else null
                }
                if (token == null) {
                    sender.sendMessage("§cДля консоли укажи id/world/arena")
                    return true
                }
                val game = resolveGame(sender, token)
                if (game == null) {
                    sender.sendMessage("§cИгра не найдена. Используй: /$label games")
                    return true
                }
                val msg = when (mode) {
                    "set" -> game.adminSetTimeLeft(sec)
                    else -> game.adminAddTime(sec)
                }
                sender.sendMessage(msg)
                return true
            }

            "spectate", "spec" -> {
                if (sender !is Player) {
                    sender.sendMessage("§cТолько для игроков")
                    return true
                }
                if (!hasAdmin(sender)) {
                    sender.sendMessage("§cНедостаточно прав")
                    return true
                }

                val token = args.getOrNull(1) ?: "here"
                val game = resolveGame(sender, token)
                if (game == null) {
                    sender.sendMessage("§cИгра не найдена. Используй: /$label games")
                    return true
                }

                val asPlayer = GameManager.getGame(sender)
                if (asPlayer != null) {
                    sender.sendMessage("§cНельзя включить наблюдение, пока ты участвуешь в матче.")
                    return true
                }

                val old = GameManager.getSpectatingGame(sender)
                if (old != null && old != game) {
                    runCatching { old.removeSpectator(sender, silent = true, forceLobby = false) }
                }

                game.addSpectator(sender)
                return true
            }

            "unspectate", "unspec", "leave" -> {
                if (sender !is Player) {
                    sender.sendMessage("§cТолько для игроков")
                    return true
                }
                if (!hasAdmin(sender)) {
                    sender.sendMessage("§cНедостаточно прав")
                    return true
                }
                val game = GameManager.getSpectatingGame(sender)
                if (game == null) {
                    sender.sendMessage("§eТы сейчас не наблюдаешь ни за одной игрой.")
                    return true
                }
                game.removeSpectator(sender, silent = false, forceLobby = false)
                return true
            }

            else -> {
                sender.sendMessage("§cНеизвестная команда. /$label help")
                return true
            }
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): MutableList<String> {
        if (args.size == 1) {
            val base = listOf("help", "get", "games", "spectate", "unspectate", "phase", "skip", "time", "skin")
            return base.filter { it.startsWith(args[0], ignoreCase = true) }.toMutableList()
        }

        if (args.size == 2 && args[0].equals("get", ignoreCase = true)) {
            return listOf("gun", "bomb", "bacillus")
                .filter { it.startsWith(args[1], ignoreCase = true) }
                .toMutableList()
        }


        if (args.size == 2 && args[0].equals("phase", ignoreCase = true)) {
            return listOf("skip")
                .filter { it.startsWith(args[1], ignoreCase = true) }
                .toMutableList()
        }

        if (args.size == 2 && args[0].equals("time", ignoreCase = true)) {
            return listOf("set", "add")
                .filter { it.startsWith(args[1], ignoreCase = true) }
                .toMutableList()
        }

        if (args.size >= 2 && (args[0].equals("phase", ignoreCase = true) || args[0].equals("skip", ignoreCase = true) || args[0].equals("time", ignoreCase = true))) {
            fun gameTokens(): List<String> {
                val games = GameManager.getActiveGames().sortedBy { it.worldName }
                val tokens = mutableListOf<String>()
                tokens.add("here")
                games.forEachIndexed { idx, g ->
                    tokens.add("#${idx + 1}")
                    tokens.add(g.worldName)
                    tokens.add(g.arenaId)
                }
                return tokens.distinct()
            }

            val tokenIndex = when {
                args[0].equals("phase", ignoreCase = true) -> 2
                args[0].equals("skip", ignoreCase = true) -> 2
                args[0].equals("time", ignoreCase = true) -> 3
                else -> 999
            }

            if (args.size == tokenIndex + 1) {
                val want = args[tokenIndex]
                return gameTokens().filter { it.startsWith(want, ignoreCase = true) }.toMutableList()
            }
        }

        if (args.size == 2 && (args[0].equals("spectate", ignoreCase = true) || args[0].equals("spec", ignoreCase = true))) {
            val games = GameManager.getActiveGames().sortedBy { it.worldName }
            val tokens = mutableListOf<String>()
            tokens.add("here")
            games.forEachIndexed { idx, g ->
                tokens.add("#${idx + 1}")
                tokens.add(g.worldName)
                tokens.add(g.arenaId)
            }
            return tokens.distinct()
                .filter { it.startsWith(args[1], ignoreCase = true) }
                .toMutableList()
        }

        return mutableListOf()
    }
}
