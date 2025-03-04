package net.perfectdreams.dreammochilas.listeners

import com.Acrobot.ChestShop.Configuration.Properties.SHOP_INTERACTION_INTERVAL
import com.Acrobot.ChestShop.Events.PreTransactionEvent
import com.Acrobot.ChestShop.Events.TransactionEvent
import com.Acrobot.ChestShop.Listeners.Player.PlayerInteract
import com.Acrobot.ChestShop.Listeners.PreTransaction.SpamClickProtector
import com.Acrobot.ChestShop.Signs.ChestShopSign
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.sync.withLock
import net.perfectdreams.dreamcore.utils.*
import net.perfectdreams.dreamcore.utils.extensions.*
import net.perfectdreams.dreamcore.utils.scheduler.onAsyncThread
import net.perfectdreams.dreamcore.utils.scheduler.onMainThread
import net.perfectdreams.dreammochilas.DreamMochilas
import net.perfectdreams.dreammochilas.FunnyIds
import net.perfectdreams.dreammochilas.dao.Mochila
import net.perfectdreams.dreammochilas.utils.MochilaInventoryHolder
import net.perfectdreams.dreammochilas.utils.MochilaUtils
import org.bukkit.Bukkit
import org.bukkit.Keyed
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.SoundCategory
import org.bukkit.block.Sign
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.*
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.lang.reflect.InvocationTargetException
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class InventoryListener(val m: DreamMochilas) : Listener {
    companion object {
        val preparePreTransactionEventMethod by lazy {
            PlayerInteract::class.java.getDeclaredMethod(
                "preparePreTransactionEvent",
                Sign::class.java,
                Player::class.java,
                Action::class.java
            ).apply {
                this.isAccessible = true
            }
        }
        val chestShopSpamClickProtectorMap by lazy {
            val protector = PreTransactionEvent.getHandlerList().registeredListeners.first {
                it.listener::class.java == SpamClickProtector::class.java
            }.listener

            SpamClickProtector::class.java.getDeclaredField(
                "TIME_OF_LATEST_CLICK"
            ).apply {
                this.isAccessible = true
            }.get(protector) as WeakHashMap<Player, Long>
        }
    }

    val mochilasCooldown = ConcurrentHashMap<Player, Long>()

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    fun onTransaction(e: PreTransactionEvent) {
        e.stock.forEach {
            if (MochilaUtils.isMochilaItem(it) && it.amount > 1) {
                e.setCancelled(PreTransactionEvent.TransactionOutcome.OTHER)
                e.client.sendMessage("§cCalma lá amigue, você não pode comprar várias mochilas ao mesmo tempo!")
            }
        }
    }

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        mochilasCooldown.remove(e.player)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCraft(e: CraftItemEvent) {
        val recipe = e.recipe
        if (recipe is Keyed) {
            val recipeKey = recipe.key.key == "rainbow_mochila"

            if (recipeKey) {
                val nonNullItemsFromInventory = e.inventory.filterNotNull()
                val areAllMochilasValid = nonNullItemsFromInventory.filter { it.type == Material.PAPER }.all { MochilaUtils.isMochila(it) }
                val areAllRainbowWoolsValid = nonNullItemsFromInventory.filter { it.type == Material.WHITE_WOOL }.all { it.itemMeta?.hasCustomModelData() == true && it.itemMeta?.customModelData == 1 }

                if (!areAllMochilasValid || !areAllRainbowWoolsValid)
                    e.isCancelled = true
                else {
                    val oldMochilaItem = e.inventory.matrix?.get(4) ?: return
                    val oldMeta = oldMochilaItem.itemMeta

                    e.currentItem?.meta<ItemMeta> {
                        displayName(oldMeta.displayName())
                        lore(oldMeta.lore())

                        fun <T, Z> copyAttributeIfPresent(namespace: NamespacedKey, type: PersistentDataType<T, Z>) {
                            val oldData = oldMeta.persistentDataContainer.get(namespace, type)

                            if (oldData != null)
                                persistentDataContainer.set(namespace, type, oldData)
                        }

                        copyAttributeIfPresent(MochilaUtils.IS_MOCHILA_KEY, PersistentDataType.BYTE)
                        copyAttributeIfPresent(MochilaUtils.MOCHILA_ID_KEY, PersistentDataType.LONG)
                        copyAttributeIfPresent(MochilaUtils.IS_FULL_KEY, PersistentDataType.BYTE)
                        copyAttributeIfPresent(MochilaUtils.HAS_MAGNET_KEY, PersistentDataType.BYTE)
                    }
                }
            }
        }
    }

    @InternalCoroutinesApi
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onInteract(e: PlayerInteractEvent) {
        val clickedBlock = e.clickedBlock ?: return
        val item = e.player.inventory.itemInMainHand

        val mochilaId = MochilaUtils.getMochilaId(item) ?: return

        // Gigantic workaround
        val isSign = clickedBlock.type.name.endsWith("_SIGN")

        if (!isSign)
            return

        // If it is a mochila, just stop here right there
        e.isCancelled = true

        if (ChestShopSign.isValid(e.clickedBlock)) {
            val state = clickedBlock.state as Sign

            // Do not allow interacting with the sign if it is the owner of the sign
            // While not having this doesn't seem to cause any issues (the owner buys items from themselves),
            // it is better to have this as a "better be safe than sorry" measure
            if (ChestShopSign.isOwner(e.player, state))
                return

            m.launchAsyncThread {
                m.logger.info { "Player ${e.player.name} is doing transaction! Is thread async? ${!isPrimaryThread}; Backpack ID: $mochilaId" }

                // We are going to replace the cooldown with OUR OWN cooldown, haha!!
                // The reason we check it here instead of checking after the mochila is loaded is to avoid loading mochilas from the database/cache every time
                val lastTimeUserInteractedWithThis = mochilasCooldown[e.player] ?: 0
                val diff = System.currentTimeMillis() - lastTimeUserInteractedWithThis

                if (SHOP_INTERACTION_INTERVAL > diff && !e.player.hasPermission("dreammochilas.bypasscooldown")) {
                    m.logger.info { "Player ${e.player.name} tried selling but it was during a cooldown! Backpack ID: $mochilaId" }
                    return@launchAsyncThread
                }

                mochilasCooldown[e.player] = System.currentTimeMillis()

                val triggerType = "${e.player.name} buying/selling stuff"
                val mochilaAccessHolder = MochilaUtils.retrieveMochilaAndHold(mochilaId, triggerType)
                if (mochilaAccessHolder == null) {
                    e.player.sendMessage("§cEssa mochila não existe!")
                    return@launchAsyncThread
                }

                val inventory = mochilaAccessHolder.getOrCreateMochilaInventoryAndHold()

                val status = onMainThread {
                    val sign = clickedBlock.state as Sign
                    try {
                        m.logger.info { "Preparing Pre Transaction Event for ${e.player.name}... Is thread async? ${!isPrimaryThread}; Backpack ID: $mochilaId" }

                        val r = preparePreTransactionEventMethod.invoke(
                            null as Any?,
                            sign,
                            e.player,
                            e.action
                        ) as PreTransactionEvent

                        r.clientInventory = inventory

                        // We need to remove from the spam click protector because, if we don't, it will just ignore the event
                        chestShopSpamClickProtectorMap.remove(e.player)

                        Bukkit.getPluginManager().callEvent(r)

                        if (r.isCancelled) {
                            m.logger.info { "Pre Transaction Event for ${e.player.name} was cancelled! ${r.transactionType} ${r.transactionOutcome}; Is thread async? ${!isPrimaryThread}; Backpack ID: $mochilaId" }
                            return@onMainThread false
                        }

                        val tEvent = TransactionEvent(r, sign)

                        Bukkit.getPluginManager().callEvent(tEvent)

                        if (tEvent.isCancelled) {
                            m.logger.info { "Transaction Event for ${e.player.name} was cancelled! ${tEvent.transactionType}; Is thread async? ${!isPrimaryThread}; Backpack ID: $mochilaId" }
                            return@onMainThread false
                        }

                        m.logger.info { "Transaction Event for ${e.player.name} was successfully completed! ${tEvent.transactionType}; Is thread async? ${!isPrimaryThread}; Backpack ID: $mochilaId" }

                        return@onMainThread true
                    } catch (var8: SecurityException) {
                        var8.printStackTrace()
                        return@onMainThread false
                    } catch (var8: IllegalAccessException) {
                        var8.printStackTrace()
                        return@onMainThread false
                    } catch (var8: java.lang.IllegalArgumentException) {
                        var8.printStackTrace()
                        return@onMainThread false
                    } catch (var8: InvocationTargetException) {
                        var8.printStackTrace()
                        return@onMainThread false
                    } catch (var8: NoSuchMethodException) {
                        var8.printStackTrace()
                        return@onMainThread false
                    }
                }

                m.logger.info { "Player ${e.player.name} transaction finished! Holding mochila locks... Is thread async? ${!isPrimaryThread}; Backpack ID: $mochilaId; Status: $status" }
                // Releasing locks...
                (inventory.holder as MochilaInventoryHolder).accessHolders.poll()
                    ?.release(triggerType)
                onMainThread {
                    MochilaUtils.updateMochilaItemLore(inventory, item)
                }
            }
        }
    }

    @InternalCoroutinesApi
    @EventHandler(priority = EventPriority.LOWEST)
    fun onOpen(e: PlayerInteractEvent) {
        if (!e.rightClick)
            return

        // ChestShop, não iremos processar caso o cara esteja clicando em placas
        if (e.clickedBlock?.type?.name?.contains("SIGN") == true)
            return

        val item = e.item ?: return

        // Avoids a bug where harvesting items with the mochila while using Geyser causes the mochila to open
        // The event order when harvesting is
        // First Event: RIGHT_CLICK_BLOCK; useItemInHand: DEFAULT; useInteractedBlock: DENY;
        // Second Event: RIGHT_CLICK_BLOCK; useItemInHand: DEFAULT; useInteractedBlock: DENY;
        // Third Event: LEFT_CLICK_BLOCK; useItemInHand: DEFAULT; useInteractedBlock: ALLOW;
        if (e.action == Action.RIGHT_CLICK_BLOCK && e.useItemInHand() == Event.Result.DEFAULT && e.useInteractedBlock() == Event.Result.DENY)
            return

        if (item.type == Material.CARROT_ON_A_STICK && item.hasItemMeta() && (item.itemMeta as? Damageable)?.damage !in 25..39) {
            // Convert to new system
            val damage = (item.itemMeta as Damageable).damage

            item.type = Material.PAPER

            val itemMeta = item.itemMeta

            itemMeta.setCustomModelData(
                when (damage) {
                    // item/custom/backpacks/backpack_normal -> sparklypower:item/backpack/backpack_brown
                    1 -> 14
                    // item/custom/backpacks/backpack_blue -> sparklypower:item/backpack/backpack_blue
                    2 -> 11
                    // item/custom/backpacks/backpack_orange -> sparklypower:item/backpack/backpack_orange
                    3 -> 36
                    // item/custom/backpacks/backpack_red -> sparklypower:item/backpack/backpack_red
                    4 -> 43
                    // item/custom/backpacks/backpack_purple -> sparklypower:item/backpack/backpack_purple
                    5 -> 42
                    // item/custom/backpacks/backpack_yellow -> sparklypower:item/backpack/backpack_yellow
                    6 -> 45
                    // item/custom/backpacks/backpack_golden -> sparklypower:item/backpack/backpack_golden
                    7 -> 25
                    // item/custom/backpacks/backpack_grey -> sparklypower:item/backpack/backpack_grey
                    8 -> 27
                    // item/custom/backpacks/backpack_green -> sparklypower:item/backpack/backpack_green
                    9 -> 26
                    // item/custom/backpacks/backpack_moon -> sparklypower:item/backpack/backpack_moon
                    10 -> 35
                    // item/custom/backpacks/backpack_crown -> sparklypower:item/backpack/backpack_crown
                    11 -> 18
                    // item/custom/backpacks/backpack_heart -> sparklypower:item/backpack/backpack_heart
                    12 -> 28
                    // item/custom/backpacks/backpack_pillow -> sparklypower:item/backpack/backpack_pillow
                    13 -> 38
                    // item/custom/backpacks/backpack_dragon -> sparklypower:item/backpack/backpack_dragon
                    14 -> 20
                    // item/custom/backpacks/backpack_beach -> sparklypower:item/backpack/backpack_beach
                    15 -> 10
                    // item/custom/backpacks/backpack_deepling -> sparklypower:item/backpack/backpack_deepling
                    16 -> 19
                    // item/custom/backpacks/backpack_fur -> sparklypower:item/backpack/backpack_fur
                    17 -> 23
                    // item/custom/backpacks/backpack_energetic -> sparklypower:item/backpack/backpack_energetic
                    18 -> 21
                    // item/custom/backpacks/backpack_pirate -> sparklypower:item/backpack/backpack_pirate
                    19 -> 39
                    // item/custom/backpacks/backpack_santa -> sparklypower:item/backpack/backpack_santa
                    20 -> 44
                    // item/custom/backpacks/backpack_expedition -> sparklypower:item/backpack/backpack_expedition
                    21 -> 22
                    // item/custom/backpacks/backpack_camouflage -> sparklypower:item/backpack/backpack_camouflage
                    22 -> 17
                    // item/custom/backpacks/backpack_cake -> sparklypower:item/backpack/backpack_cake
                    23 -> 16
                    // item/custom/backpacks/backpack_buggy -> sparklypower:item/backpack/backpack_buggy
                    24 -> 15
                    else -> error("Unknown Mochila!")
                }
            )

            item.itemMeta = itemMeta
        }

        if (MochilaUtils.isMochilaItem(item)) {
            val mochilaId = MochilaUtils.getMochilaId(item)

            e.isCancelled = true

            if (item.amount != 1)
                return

            if (mochilaId == null) { // Criar mochila, caso ainda não tenha um ID associado a ela
                // Old mochila item check, we will convert them
                if (!MochilaUtils.isMochila(item) && item.hasStoredMetadataWithKey("isMochila")) {
                    item.meta<ItemMeta> {
                        persistentDataContainer.set(
                            MochilaUtils.IS_MOCHILA_KEY,
                            PersistentDataType.BYTE,
                            1
                        )

                        val oldMochilaId = item.getStoredMetadata("mochilaId")

                        if (oldMochilaId != null) {
                            persistentDataContainer.set(
                                MochilaUtils.MOCHILA_ID_KEY,
                                PersistentDataType.LONG,
                                oldMochilaId.toLong()
                            )
                        }
                    }

                    // Call the open method again
                    return onOpen(e)
                }

                m.launchAsyncThread {
                    MochilaUtils.mochilaCreationMutex.withLock {
                        val newInventory = Bukkit.createInventory(null, 27, "Mochila")
                        val funnyId = FunnyIds.generatePseudoId()

                        newInventory.addItem(
                            ItemStack(Material.PAPER)
                                .rename("§a§lBem-Vind" + MeninaAPI.getArtigo(e.player) + ", §e§l" + e.player.displayName + "§a§l!")
                                .lore(
                                    "§7Gostou da sua nova Mochila?",
                                    "§7Aqui você pode guardar qualquer item que você quiser!",
                                    "§7Você pode comprar mais mochilas para ter mais espaço!",
                                    "§7",
                                    "§c§lCuidado!",
                                    "§cSe você perder esta mochila,",
                                    "§cvocê irá perder todos os itens que estão dentro dela!"
                                )
                        )

                        val mochila = transaction(Databases.databaseNetwork) {
                            Mochila.new {
                                this.owner = e.player.uniqueId
                                this.size = 27
                                this.content = (newInventory.toBase64(1))
                                this.type = item.itemMeta.customModelData
                                this.funnyId = funnyId
                                this.version = 1
                            }
                        }

                        // Handle just like a normal mochila would
                        // Should NEVER be null
                        val loadedFromDatabaseMochila =
                            MochilaUtils.retrieveMochilaAndHold(mochila.id.value, "${e.player.name} mochila creation")!!

                        val inventory = loadedFromDatabaseMochila.getOrCreateMochilaInventoryAndHold()

                        onMainThread {
                            item.meta<ItemMeta> {
                                lore = listOf(
                                    "§7Mochila de §b${e.player.name}",
                                    "§7",
                                    "§6$funnyId"
                                )

                                persistentDataContainer.set(
                                    MochilaUtils.MOCHILA_ID_KEY,
                                    PersistentDataType.LONG,
                                    mochila.id.value
                                )
                            }

                            e.player.playSound(
                                e.player.location,
                                "sparklypower.sfx.backpack.open",
                                SoundCategory.BLOCKS,
                                1f,
                                DreamUtils.random.nextFloat(0.8f, 1.2f)
                            )

                            e.player.openInventory(inventory)
                        }
                    }
                }
                return
            }

            m.launchAsyncThread {
                val mochilaAccessHolder = MochilaUtils.retrieveMochilaAndHold(mochilaId, "${e.player.name} mochila opening")

                onMainThread {
                    if (mochilaAccessHolder == null) {
                        e.player.sendMessage("§cEssa mochila não existe!")
                        return@onMainThread
                    }

                    onAsyncThread {
                        // Update mochila type
                        transaction(Databases.databaseNetwork) {
                            mochilaAccessHolder.mochila.type = item.itemMeta.customModelData
                            mochilaAccessHolder.mochila.version = 1
                        }
                    }

                    if (e.player.openInventory.topInventory.type != InventoryType.CRAFTING) {
                        m.logger.warning { "Player ${e.player.name} tried opening a backpack when they already had a inventory open! ${e.player.openInventory.topInventory.type} Backpack ID: ${mochilaAccessHolder.mochila.id.value}" }
                        onAsyncThread {
                            mochilaAccessHolder.release("${e.player.name} mochila opening but already had an inventory open")
                        }
                        return@onMainThread
                    }

                    m.logger.info { "Player ${e.player.name} opened a backpack. Backpack ID: ${mochilaAccessHolder.mochila.id.value}" }

                    val itemDisplayName = item.itemMeta?.displayName()
                    val inventoryTitle =
                        if (itemDisplayName != null && itemDisplayName != MochilaUtils.DEFAULT_MOCHILA_TITLE_NAME)
                            itemDisplayName
                        else
                            MochilaUtils.DEFAULT_MOCHILA_TITLE_NAME

                    val inventory = mochilaAccessHolder.getOrCreateMochilaInventoryAndHold(inventoryTitle)

                    e.player.playSound(
                        e.player.location,
                        "sparklypower.sfx.backpack.open",
                        SoundCategory.BLOCKS,
                        1f,
                        DreamUtils.random.nextFloat(0.6f, 1.0f)
                    )

                    e.player.openInventory(inventory)
                }
            }
        }
    }

    @InternalCoroutinesApi
    @EventHandler
    fun onClose(e: InventoryCloseEvent) {
        val holder = e.inventory.holder

        if (holder is MochilaInventoryHolder) { // Closed a mochila inventory holder inventory
            // We don't *really* care about what access holder we will get tbh
            val mochilaAccessHolder = holder.accessHolders.poll()

            if (mochilaAccessHolder == null) {
                m.logger.warning { "Received a InventoryCloseEvent for a MochilaInventoryHolder, but there isn't any access holders available! Bug?" }
                return
            }

            // Antes de fechar, vamos verificar se a mochila está na mochila
            val closingMochilaId = mochilaAccessHolder.mochila.id.value
            for (idx in 0 until e.inventory.size) {
                val item = e.inventory.getItem(idx) ?: continue

                val mochilaId = MochilaUtils.getMochilaId(item)

                if (mochilaId == closingMochilaId) { // omg
                    m.logger.warning("Player ${e.player.name} is trying to close $mochilaId while the backpack is within itself! Giving item to player...")
                    if (e.player.inventory.canHoldItem(item))
                        e.player.inventory.addItem(item)
                    else
                        e.player.world.dropItem(e.player.location, item)
                    e.inventory.clear(idx)
                }
            }

            (e.player as? Player)?.playSound(
                e.player.location,
                "sparklypower.sfx.backpack.close",
                SoundCategory.BLOCKS,
                1f,
                DreamUtils.random.nextFloat(1.0f, 1.4f)
            )

            m.launchAsyncThread {
                val item = e.player.inventory.itemInMainHand

                mochilaAccessHolder.release("${e.player.name} closing inventory")

                if (!MochilaUtils.isMochila(item))
                    return@launchAsyncThread

                onMainThread {
                    MochilaUtils.updateMochilaItemLore(
                        e.inventory,
                        item
                    )
                }
            }
        }
    }

    @EventHandler
    fun onMove(e: InventoryClickEvent) {
        // Não deixar colocar a mochila dentro da mochila
        val mochilaId = MochilaUtils.getMochilaId(e.currentItem ?: return) ?: return

        val holder = e.inventory.holder

        if (holder is MochilaInventoryHolder) {
            // Just a liiil peek ewe
            val peekedElement = holder.accessHolders.peek()
            if (peekedElement.mochila.id.value == mochilaId) {
                e.isCancelled = true
            }
        }
    }

    @EventHandler
    fun onMove(e: InventoryMoveItemEvent) {
        // Não deixar colocar a mochila dentro da mochila
        val mochilaId = MochilaUtils.getMochilaId(e.item) ?: return

        val holder = e.destination.holder

        if (holder is MochilaInventoryHolder) {
            // Just a liiil peek ewe
            val peekedElement = holder.accessHolders.peek()
            if (peekedElement.mochila.id.value == mochilaId) {
                e.isCancelled = true
            }
        }
    }
}