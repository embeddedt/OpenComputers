package li.cil.oc.common.item

import li.cil.oc.CreativeTab
import net.minecraft.item.Item
import net.minecraft.item.Item.Properties
import net.minecraftforge.common.extensions.IForgeItem

class Memory(val tier: Int, props: Properties = new Properties().tab(CreativeTab)) extends Item(props) with IForgeItem with traits.SimpleItem with traits.ItemTier {
  @Deprecated
  override def getDescriptionId = super.getDescriptionId + tier

  override protected def tooltipName = Option(unlocalizedName)
}
