package li.cil.oc.common

import com.google.common.base.Strings
import li.cil.oc._
import li.cil.oc.common.init.Blocks
import li.cil.oc.common.init.Items
import li.cil.oc.common.item.Delegate
import li.cil.oc.common.recipe.Recipes
import li.cil.oc.integration.Mods
import li.cil.oc.server._
import li.cil.oc.server.machine.luac.NativeLuaArchitecture
import li.cil.oc.server.machine.luaj.LuaJLuaArchitecture
import li.cil.oc.util.LuaStateFactory
import net.minecraft.block.Block
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraftforge.fml.common.event._
import net.minecraftforge.fml.common.network.NetworkRegistry
import net.minecraftforge.fml.common.registry.GameRegistry
import net.minecraftforge.oredict.OreDictionary

import scala.collection.convert.WrapAsScala._

class Proxy {
  def preInit(e: FMLPreInitializationEvent) {
    Settings.load(e.getSuggestedConfigurationFile)

    OpenComputers.log.info("Initializing blocks and items.")

    Blocks.init()
    Items.init()

    OpenComputers.log.info("Initializing additional OreDict entries.")

    registerExclusive("craftingPiston", new ItemStack(net.minecraft.init.Blocks.piston), new ItemStack(net.minecraft.init.Blocks.sticky_piston))
    registerExclusive("torchRedstoneActive", new ItemStack(net.minecraft.init.Blocks.redstone_torch))
    registerExclusive("nuggetGold", new ItemStack(net.minecraft.init.Items.gold_nugget))
    registerExclusive("nuggetIron", Items.ironNugget.createItemStack())

    if (OreDictionary.getOres("nuggetIron").exists(Items.ironNugget.createItemStack().isItemEqual)) {
      Recipes.addMultiItem(Items.ironNugget, "nuggetIron")
      Recipes.addItem(net.minecraft.init.Items.iron_ingot, "ingotIron")
    }
    else {
      Items.ironNugget.showInItemList = false
    }

    // Avoid issues with Extra Utilities registering colored obsidian as `obsidian`
    // oredict entry, but not normal obsidian, breaking some recipes.
    OreDictionary.registerOre("obsidian", net.minecraft.init.Blocks.obsidian)

    OpenComputers.log.info("Initializing OpenComputers API.")

    api.CreativeTab.instance = CreativeTab
    api.API.driver = driver.Registry
    api.API.fileSystem = fs.FileSystem
    api.API.items = Items
    api.API.machine = machine.Machine
    api.API.network = network.Network

    api.Machine.LuaArchitecture =
      if (LuaStateFactory.isAvailable && !Settings.get.forceLuaJ) classOf[NativeLuaArchitecture]
      else classOf[LuaJLuaArchitecture]
    api.Machine.add(api.Machine.LuaArchitecture)
    if (Settings.get.registerLuaJArchitecture)
      api.Machine.add(classOf[LuaJLuaArchitecture])
  }

  def registerModel(instance: Delegate, id: String): Unit = {}

  def registerModel(instance: Item, id: String): Unit = {}

  def registerModel(instance: Block, id: String): Unit = {}

  def init(e: FMLInitializationEvent) {
    OpenComputers.channel = NetworkRegistry.INSTANCE.newEventDrivenChannel("OpenComputers")
    OpenComputers.channel.register(server.PacketHandler)

    OpenComputers.log.info("Initializing mod integration.")
    Mods.init()
  }

  def postInit(e: FMLPostInitializationEvent) {
    // Don't allow driver registration after this point, to avoid issues.
    driver.Registry.locked = true
  }

  private def registerExclusive(name: String, items: ItemStack*) {
    if (OreDictionary.getOres(name).isEmpty) {
      for (item <- items) {
        OreDictionary.registerOre(name, item)
      }
    }
  }

  // Yes, this could be boiled down even further, but I like to keep it
  // explicit like this, because it makes it a) clearer, b) easier to
  // extend, in case that should ever be needed.

  private val blockRenames = Map(
    OpenComputers.ID + ":server_rack" -> "serverRack"
  )

  private val itemRenames = Map(
    OpenComputers.ID + ":server_rack" -> "serverRack"
  )

  def missingMappings(e: FMLMissingMappingsEvent) {
    for (missing <- e.get()) {
      if (missing.`type` == GameRegistry.Type.BLOCK) {
        blockRenames.get(missing.name) match {
          case Some(name) =>
            if (Strings.isNullOrEmpty(name)) missing.ignore()
            else missing.remap(GameRegistry.findBlock(OpenComputers.ID, name))
          case _ => missing.warn()
        }
      }
      else if (missing.`type` == GameRegistry.Type.ITEM) {
        itemRenames.get(missing.name) match {
          case Some(name) =>
            if (Strings.isNullOrEmpty(name)) missing.ignore()
            else missing.remap(GameRegistry.findItem(OpenComputers.ID, name))
          case _ => missing.warn()
        }
      }
    }
  }
}
