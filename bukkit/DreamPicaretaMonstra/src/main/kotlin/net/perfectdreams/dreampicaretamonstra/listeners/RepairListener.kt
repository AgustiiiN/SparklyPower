package net.perfectdreams.dreampicaretamonstra.listeners

import com.gmail.nossr50.events.skills.repair.McMMOPlayerRepairCheckEvent
import com.gmail.nossr50.events.skills.salvage.McMMOPlayerSalvageCheckEvent
import net.perfectdreams.dreamcore.utils.extensions.getStoredMetadata
import net.perfectdreams.dreampicaretamonstra.DreamPicaretaMonstra
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.PrepareAnvilEvent

class RepairListener(val m: DreamPicaretaMonstra) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onRepair(e: McMMOPlayerRepairCheckEvent) {
        if (e.repairedObject.getStoredMetadata("isMonsterPickaxe") == "true") {
            e.isCancelled = true
            e.player.sendMessage("§cVocê não pode reparar uma ferramenta monstra!")
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSalvage(e: McMMOPlayerSalvageCheckEvent) {
        if (e.salvageItem.getStoredMetadata("isMonsterPickaxe") == "true") {
            e.isCancelled = true
            e.player.sendMessage("§cVocê não pode salvar uma ferramenta monstra!")
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onAnvilRepair(e: PrepareAnvilEvent) {
        val inventory = e.inventory
        if (e.result?.getStoredMetadata("isMonsterPickaxe") == "true")
            inventory.repairCost *= 16
    }
}