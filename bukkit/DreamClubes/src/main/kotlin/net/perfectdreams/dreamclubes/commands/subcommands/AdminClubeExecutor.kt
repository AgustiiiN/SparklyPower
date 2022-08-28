package net.perfectdreams.dreamclubes.commands.subcommands

import net.perfectdreams.dreamclubes.DreamClubes
import net.perfectdreams.dreamclubes.commands.SparklyClubesCommandExecutor
import net.perfectdreams.dreamclubes.utils.ClubePermissionLevel
import net.perfectdreams.dreamcore.utils.Databases
import net.perfectdreams.dreamcore.utils.DreamUtils
import net.perfectdreams.dreamcore.utils.commands.context.CommandArguments
import net.perfectdreams.dreamcore.utils.commands.context.CommandContext
import net.perfectdreams.dreamcore.utils.commands.options.CommandOptions
import net.perfectdreams.dreamcore.utils.scheduler.onAsyncThread
import org.jetbrains.exposed.sql.transactions.transaction

class AdminClubeExecutor(m: DreamClubes) : SparklyClubesCommandExecutor(m) {
    inner class Options : CommandOptions() {
        val playerName = word("player_name")
    }

    override val options = Options()

    override fun execute(context: CommandContext, args: CommandArguments) {
        val player = context.requirePlayer()
        val playerName = args[options.playerName]

        withPlayerClube(player) { clube, selfMember ->
            val uniqueId = onAsyncThread {
                DreamUtils.retrieveUserUniqueId(playerName)
            }

            val permissionLevel = selfMember.permissionLevel

            if (!permissionLevel.canExecute(ClubePermissionLevel.OWNER)) {
                player.sendMessage("${DreamClubes.PREFIX} §cVocê não tem permissão!")
                return@withPlayerClube
            }

            val thePlayer = onAsyncThread { clube.retrieveMember(uniqueId) }
            if (thePlayer == null) {
                player.sendMessage("${DreamClubes.PREFIX} §cPlayer não está no clube!")
                return@withPlayerClube
            }

            if (thePlayer.id.value == player.uniqueId) {
                player.sendMessage("${DreamClubes.PREFIX} §cVocê não pode mudar as suas próprias permissões, bobinho!")
                return@withPlayerClube
            }

            if (thePlayer.permissionLevel == ClubePermissionLevel.OWNER) {
                player.sendMessage("${DreamClubes.PREFIX} §cVocê não pode mudar as permissões do dono, bobinho!")
                return@withPlayerClube
            }

            if (thePlayer.permissionLevel == ClubePermissionLevel.ADMIN) {
                onAsyncThread {
                    transaction(Databases.databaseNetwork) {
                        thePlayer.permissionLevel = ClubePermissionLevel.MEMBER
                    }
                }
                player.sendMessage("${DreamClubes.PREFIX} §d${playerName}§a não é mais um administrador...")
                clube.sendInfoOnAsyncThread("§7§b${playerName}§7 não é mais um administrador de ${clube.shortName}§7...")
            } else {
                onAsyncThread {
                    transaction(Databases.databaseNetwork) {
                        thePlayer.permissionLevel = ClubePermissionLevel.ADMIN
                    }
                }
                player.sendMessage("${DreamClubes.PREFIX} §d${playerName}§a virou um administrador!")
                clube.sendInfoOnAsyncThread("§7Parabéns §b${playerName}§7 por virar um administrador do ${clube.shortName}§7! ╰(*°▽°*)╯")
            }
        }
    }
}