package net.perfectdreams.dreamcorrida.events

import com.okkero.skedule.SynchronizationContext
import com.okkero.skedule.schedule
import net.perfectdreams.dreamcore.DreamCore
import net.perfectdreams.dreamcore.eventmanager.ServerEvent
import net.perfectdreams.dreamcore.utils.DreamUtils
import net.perfectdreams.dreamcore.utils.PlayerUtils
import net.perfectdreams.dreamcore.utils.scheduler
import net.perfectdreams.dreamcorrida.DreamCorrida
import net.perfectdreams.dreamcorrida.utils.toChatColor
import org.bukkit.Bukkit
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import net.perfectdreams.dreamcore.utils.chance
import net.perfectdreams.dreamcore.utils.extensions.removeAllPotionEffects
import net.perfectdreams.dreamcorrida.utils.Checkpoint
import net.perfectdreams.dreamcorrida.utils.Corrida
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.*

class EventoCorrida(val m: DreamCorrida) : ServerEvent("Corrida", "/corrida") {
    init {
        this.requiredPlayers = 30
        this.delayBetween = 3600000 // 1 hora
        this.discordAnnouncementRole = "477979984275701760"
    }

    var corrida: Corrida? = null
    var playerCheckpoints = mutableMapOf<Player, Checkpoint>()
    var wonPlayers = mutableListOf<UUID>()
    var startCooldown = 15
    val damageCooldown = mutableMapOf<Player, Long>()

    override fun preStart() {
        val canStart = m.availableCorridas.filter { it.ready }.isNotEmpty()

        if (!canStart) {
            this.lastTime = System.currentTimeMillis()
            return
        }

        running = true
        broadcastEventAnnouncement()
        start()
    }

    override fun start() {
        startCooldown = 15
        damageCooldown.clear()
        val corrida = m.availableCorridas.filter { it.ready }.random()
        this.corrida = corrida

        val spawnPoint = corrida.spawn.toLocation()

        val world = spawnPoint.world

        scheduler().schedule(m) {
            while (startCooldown > 0) {
                world.players.forEach {
                    it.sendTitle("§aCorrida irá começar em...", "§c${startCooldown}s", 0, 100, 0)
                    it.playSound(it.location, Sound.UI_BUTTON_CLICK, SoundCategory.PLAYERS, 1f, 1f)

                    it.addPotionEffect(PotionEffect(PotionEffectType.SLOW, 300, 1, true, false))
                }

                waitFor(20) // 1 segundo
                startCooldown--
            }

            world.players.forEach {
                it.sendTitle("§aCorra e se divirta!", "§bBoa sorte!", 0, 60, 20)
                it.playSound(it.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f)

                it.removeAllPotionEffects()
                it.fallDistance = 0.0f
                it.fireTicks = 0
                PlayerUtils.healAndFeed(it)
                it.activePotionEffects.filter { it.type != PotionEffectType.SPEED && it.type != PotionEffectType.JUMP } .forEach { effect ->
                    it.removePotionEffect(effect.type)
                }

                it.addPotionEffect(PotionEffect(PotionEffectType.SPEED, 200, 1, false, false))
            }
        }

        var idx = 0
        scheduler().schedule(m) {
            while (running) {
                if (idx % 3 == 0) {
                    Bukkit.broadcastMessage("${DreamCorrida.PREFIX} Evento Corrida começou! §6/corrida")
                }

                if (0 >= startCooldown)
                    world.players.forEach {
                        it.fallDistance = 0.0f
                        it.fireTicks = 0
                        PlayerUtils.healAndFeed(it)
                        it.activePotionEffects.filter { (it.type != PotionEffectType.SPEED && it.amplifier != 0) && (it.type != PotionEffectType.JUMP && it.amplifier != 0) } .forEach { effect ->
                            it.removePotionEffect(effect.type)
                        }

                        it.addPotionEffect(PotionEffect(PotionEffectType.SPEED, 200, 0, false, false))
                        it.addPotionEffect(PotionEffect(PotionEffectType.JUMP, 200, 0, false, false))
                    }

                waitFor(100) // 5 segundos
                idx++
            }
        }
    }
}
