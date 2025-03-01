package li.cil.oc.server

import li.cil.oc.api
import li.cil.oc.api.event.FileSystemAccessEvent
import li.cil.oc.api.event.NetworkActivityEvent
import li.cil.oc.api.network.EnvironmentHost
import li.cil.oc.api.network.Node
import li.cil.oc.common._
import li.cil.oc.common.nanomachines.ControllerImpl
import li.cil.oc.common.tileentity.Waypoint
import li.cil.oc.common.tileentity.traits._
import li.cil.oc.util.BlockPosition
import li.cil.oc.util.PackedColor
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.player.ServerPlayerEntity
import net.minecraft.inventory.container.Container
import net.minecraft.item.ItemStack
import net.minecraft.nbt.CompressedStreamTools
import net.minecraft.nbt.CompoundNBT
import net.minecraft.particles.IParticleData
import net.minecraft.util.Direction
import net.minecraft.util.ResourceLocation
import net.minecraft.util.SoundCategory
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.registries.ForgeRegistries

import scala.collection.mutable

object PacketSender {
  def sendAdapterState(t: tileentity.Adapter): Unit = {
    val pb = new SimplePacketBuilder(PacketType.AdapterState)

    pb.writeTileEntity(t)
    pb.writeByte(t.compressSides)

    pb.sendToPlayersNearTileEntity(t)
  }

  def sendAnalyze(address: String, player: ServerPlayerEntity) {
    val pb = new SimplePacketBuilder(PacketType.Analyze)

    pb.writeUTF(address)

    pb.sendToPlayer(player)
  }

  def sendChargerState(t: tileentity.Charger) {
    val pb = new SimplePacketBuilder(PacketType.ChargerState)

    pb.writeTileEntity(t)
    pb.writeDouble(t.chargeSpeed)
    pb.writeBoolean(t.hasPower)

    pb.sendToPlayersNearTileEntity(t)
  }

  def sendClientLog(line: String, player: ServerPlayerEntity) {
    val pb = new CompressedPacketBuilder(PacketType.ClientLog)

    pb.writeUTF(line)

    pb.sendToPlayer(player)
  }

  def sendClipboard(player: ServerPlayerEntity, text: String) {
    val pb = new SimplePacketBuilder(PacketType.Clipboard)

    pb.writeUTF(text)

    pb.sendToPlayer(player)
  }

  def sendColorChange(t: Colored) {
    val pb = new SimplePacketBuilder(PacketType.ColorChange)

    pb.writeTileEntity(t)
    pb.writeInt(t.getColor)

    pb.sendToPlayersNearTileEntity(t)
  }

  def sendComputerState(t: Computer) {
    val pb = new SimplePacketBuilder(PacketType.ComputerState)

    pb.writeTileEntity(t)
    pb.writeBoolean(t.isRunning)
    pb.writeBoolean(t.hasErrored)

    pb.sendToPlayersNearTileEntity(t)
  }

  def sendMachineItemState(player: ServerPlayerEntity, stack: ItemStack, isRunning: Boolean): Unit = {
    val pb = new SimplePacketBuilder(PacketType.MachineItemStateResponse)

    pb.writeItemStack(stack)
    pb.writeBoolean(isRunning)

    pb.sendToPlayer(player)
  }

  def sendComputerUserList(t: Computer, list: Array[String]) {
    val pb = new SimplePacketBuilder(PacketType.ComputerUserList)

    pb.writeTileEntity(t)
    pb.writeInt(list.length)
    list.foreach(pb.writeUTF)

    pb.sendToPlayersNearTileEntity(t)
  }

  def sendContainerUpdate(c: Container, nbt: CompoundNBT, player: ServerPlayerEntity): Unit = {
    if (!nbt.isEmpty) {
      val pb = new SimplePacketBuilder(PacketType.ContainerUpdate)

      pb.writeInt(c.containerId)
      pb.writeNBT(nbt)

      pb.sendToPlayer(player)
    }
  }

  def sendDisassemblerActive(t: tileentity.Disassembler, active: Boolean) {
    val pb = new SimplePacketBuilder(PacketType.DisassemblerActiveChange)

    pb.writeTileEntity(t)
    pb.writeBoolean(active)

    pb.sendToPlayersNearTileEntity(t)
  }

  // Avoid spamming the network with disk activity notices.
  val fileSystemAccessTimeouts: mutable.WeakHashMap[Node, mutable.Map[String, Long]] = mutable.WeakHashMap.empty[Node, mutable.Map[String, Long]]

  def sendFileSystemActivity(node: Node, host: EnvironmentHost, name: String): Unit = fileSystemAccessTimeouts.synchronized {
    fileSystemAccessTimeouts.get(node) match {
      case Some(hostTimeouts) if hostTimeouts.getOrElse(name, 0L) > System.currentTimeMillis() => // Cooldown.
      case _ =>
        val event = host match {
          case t: net.minecraft.tileentity.TileEntity => new FileSystemAccessEvent.Server(name, t, node)
          case _ => new FileSystemAccessEvent.Server(name, host.world, host.xPosition, host.yPosition, host.zPosition, node)
        }
        MinecraftForge.EVENT_BUS.post(event)
        if (!event.isCanceled) {
          fileSystemAccessTimeouts.getOrElseUpdate(node, mutable.Map.empty) += name -> (System.currentTimeMillis() + 500)

          val pb = new SimplePacketBuilder(PacketType.FileSystemActivity)

          pb.writeUTF(event.getSound)
          CompressedStreamTools.write(event.getData, pb)
          event.getBlockEntity match {
            case t: net.minecraft.tileentity.TileEntity =>
              pb.writeBoolean(true)
              pb.writeTileEntity(t)
            case _ =>
              pb.writeBoolean(false)
              pb.writeUTF(event.getWorld.dimension.location.toString)
              pb.writeDouble(event.getX)
              pb.writeDouble(event.getY)
              pb.writeDouble(event.getZ)
          }

          pb.sendToPlayersNearHost(host, Option(64))
        }
    }
  }

  def sendNetworkActivity(node: Node, host: EnvironmentHost): Unit = {

    val event = host match {
      case t: net.minecraft.tileentity.TileEntity => new NetworkActivityEvent.Server(t, node)
      case _ => new NetworkActivityEvent.Server(host.world, host.xPosition, host.yPosition, host.zPosition, node)
    }
    MinecraftForge.EVENT_BUS.post(event)
    if (!event.isCanceled) {

      val pb = new SimplePacketBuilder(PacketType.NetworkActivity)

      CompressedStreamTools.write(event.getData, pb)
      event.getBlockEntity match {
        case t: net.minecraft.tileentity.TileEntity =>
          pb.writeBoolean(true)
          pb.writeTileEntity(t)
        case _ =>
          pb.writeBoolean(false)
          pb.writeUTF(event.getWorld.dimension.location.toString)
          pb.writeDouble(event.getX)
          pb.writeDouble(event.getY)
          pb.writeDouble(event.getZ)
      }

      pb.sendToPlayersNearHost(host, Option(64))
    }
  }

  def sendFloppyChange(t: tileentity.DiskDrive, stack: ItemStack = ItemStack.EMPTY) {
    val pb = new SimplePacketBuilder(PacketType.FloppyChange)

    pb.writeTileEntity(t)
    pb.writeItemStack(stack)

    pb.sendToPlayersNearTileEntity(t)
  }

  def sendHologramClear(t: tileentity.Hologram) {
    val pb = new SimplePacketBuilder(PacketType.HologramClear)

    pb.writeTileEntity(t)

    pb.sendToPlayersNearTileEntity(t)
  }

  def sendHologramColor(t: tileentity.Hologram, index: Int, value: Int) {
    val pb = new SimplePacketBuilder(PacketType.HologramColor)

    pb.writeTileEntity(t)
    pb.writeInt(index)
    pb.writeInt(value)

    pb.sendToPlayersNearTileEntity(t)
  }

  def sendHologramPowerChange(t: tileentity.Hologram) {
    val pb = new SimplePacketBuilder(PacketType.HologramPowerChange)

    pb.writeTileEntity(t)
    pb.writeBoolean(t.hasPower)

    pb.sendToPlayersNearTileEntity(t)
  }

  def sendHologramScale(t: tileentity.Hologram) {
    val pb = new SimplePacketBuilder(PacketType.HologramScale)

    pb.writeTileEntity(t)
    pb.writeDouble(t.scale)

    pb.sendToPlayersNearTileEntity(t)
  }

  def sendHologramArea(t: tileentity.Hologram) {
    val pb = new CompressedPacketBuilder(PacketType.HologramArea)

    pb.writeTileEntity(t)
    pb.writeByte(t.dirtyFromX)
    pb.writeByte(t.dirtyUntilX)
    pb.writeByte(t.dirtyFromZ)
    pb.writeByte(t.dirtyUntilZ)
    for (x <- t.dirtyFromX until t.dirtyUntilX) {
      for (z <- t.dirtyFromZ until t.dirtyUntilZ) {
        pb.writeInt(t.volume(x + z * t.width))
        pb.writeInt(t.volume(x + z * t.width + t.width * t.width))
      }
    }

    pb.sendToPlayersNearTileEntity(t)
  }

  def sendHologramValues(t: tileentity.Hologram): Unit = {
    val pb = new CompressedPacketBuilder(PacketType.HologramValues)

    pb.writeTileEntity(t)
    pb.writeInt(t.dirty.size)
    for (xz <- t.dirty) {
      val x = (xz >> 8).toByte
      val z = xz.toByte
      pb.writeShort(xz)
      val rangeStart: Int = x + z * t.width
      val rangeFinal: Int = x + z * t.width + t.width * t.width
      pb.writeInt(t.volume(rangeStart max 0 min t.volume.length - 1))
      pb.writeInt(t.volume(rangeFinal max 0 min t.volume.length - 1))
    }

    pb.sendToPlayersNearTileEntity(t)
  }

  def sendHologramOffset(t: tileentity.Hologram) {
    val pb = new SimplePacketBuilder(PacketType.HologramTranslation)

    pb.writeTileEntity(t)
    pb.writeDouble(t.translation.x)
    pb.writeDouble(t.translation.y)
    pb.writeDouble(t.translation.z)

    pb.sendToPlayersNearTileEntity(t)
  }

  def sendHologramRotation(t: tileentity.Hologram) {
    val pb = new SimplePacketBuilder(PacketType.HologramRotation)

    pb.writeTileEntity(t)
    pb.writeFloat(t.rotationAngle)
    pb.writeFloat(t.rotationX)
    pb.writeFloat(t.rotationY)
    pb.writeFloat(t.rotationZ)

    pb.sendToPlayersNearTileEntity(t)
  }

  def sendHologramRotationSpeed(t: tileentity.Hologram) {
    val pb = new SimplePacketBuilder(PacketType.HologramRotationSpeed)

    pb.writeTileEntity(t)
    pb.writeFloat(t.rotationSpeed)
    pb.writeFloat(t.rotationSpeedX)
    pb.writeFloat(t.rotationSpeedY)
    pb.writeFloat(t.rotationSpeedZ)

    pb.sendToPlayersNearTileEntity(t)
  }

  def sendLootDisks(p: ServerPlayerEntity): Unit = {
    // Sending as separate packets, because CompressedStreamTools hiccups otherwise...
    val stacks = Loot.worldDisks.map(_._1)
    for (stack <- stacks) {
      val pb = new SimplePacketBuilder(PacketType.LootDisk)

      pb.writeItemStack(stack)

      pb.sendToPlayer(p)
    }
    for (stack <- Loot.disksForCyclingServer) {
      val pb = new SimplePacketBuilder(PacketType.CyclingDisk)

      pb.writeItemStack(stack)

      pb.sendToPlayer(p)
    }
  }

  def sendNanomachineConfiguration(player: PlayerEntity): Unit = {
    val pb = new SimplePacketBuilder(PacketType.NanomachinesConfiguration)

    pb.writeEntity(player)
    api.Nanomachines.getController(player) match {
      case controller: ControllerImpl =>
        pb.writeBoolean(true)
        val nbt = new CompoundNBT()
        controller.saveData(nbt)
        pb.writeNBT(nbt)
      case _ =>
        pb.writeBoolean(false)
    }

    pb.sendToPlayersNearEntity(player)
  }

  def sendNanomachineInputs(player: PlayerEntity): Unit = {
    api.Nanomachines.getController(player) match {
      case controller: ControllerImpl =>
        val pb = new SimplePacketBuilder(PacketType.NanomachinesInputs)

        pb.writeEntity(player)
        val inputs = controller.configuration.triggers.map(i => if (i.isActive) 1.toByte else 0.toByte).toArray
        pb.writeInt(inputs.length)
        pb.write(inputs)

        pb.sendToPlayersNearEntity(player)
      case _ => // Wat.
    }
  }

  def sendNanomachinePower(player: PlayerEntity): Unit = {
    api.Nanomachines.getController(player) match {
      case controller: ControllerImpl =>
        val pb = new SimplePacketBuilder(PacketType.NanomachinesPower)

        pb.writeEntity(player)
        pb.writeDouble(controller.getLocalBuffer)

        pb.sendToPlayersNearEntity(player)
      case _ => // Wat.
    }
  }

  def sendNetSplitterState(t: tileentity.NetSplitter): Unit = {
    val pb = new SimplePacketBuilder(PacketType.NetSplitterState)

    pb.writeTileEntity(t)
    pb.writeBoolean(t.isInverted)
    pb.writeByte(t.compressSides)

    pb.sendToPlayersNearTileEntity(t)
  }

  def sendParticleEffect(position: BlockPosition, particleType: IParticleData, count: Int, velocity: Double, direction: Option[Direction] = None): Unit = if (count > 0) {
    val pb = new SimplePacketBuilder(PacketType.ParticleEffect)

    pb.writeUTF(position.world.get.dimension.location.toString)
    pb.writeInt(position.x)
    pb.writeInt(position.y)
    pb.writeInt(position.z)
    pb.writeDouble(velocity)
    pb.writeDirection(direction)
    pb.writeRegistryEntry(ForgeRegistries.PARTICLE_TYPES, particleType.getType())
    pb.writeByte(count.toByte)

    pb.sendToNearbyPlayers(position.world.get, position.x, position.y, position.z, Some(32.0))
  }

  def sendPetVisibility(name: Option[String] = None, player: Option[ServerPlayerEntity] = None) {
    val pb = new SimplePacketBuilder(PacketType.PetVisibility)

    name match {
      case Some(n) =>
        pb.writeInt(1)
        pb.writeUTF(n)
        pb.writeBoolean(!PetVisibility.hidden.contains(n))
      case _ =>
        pb.writeInt(PetVisibility.hidden.size)
        for (n <- PetVisibility.hidden) {
          pb.writeUTF(n)
          pb.writeBoolean(false)
        }
    }

    player match {
      case Some(p) => pb.sendToPlayer(p)
      case _ => pb.sendToAllPlayers()
    }
  }

  def sendPowerState(t: PowerInformation) {
    val pb = new SimplePacketBuilder(PacketType.PowerState)

    pb.writeTileEntity(t)
    pb.writeDouble(math.round(t.globalBuffer))
    pb.writeDouble(t.globalBufferSize)

    pb.sendToPlayersNearTileEntity(t)
  }

  def sendPrinting(t: tileentity.Printer, printing: Boolean) {
    val pb = new SimplePacketBuilder(PacketType.PrinterState)

    pb.writeTileEntity(t)
    pb.writeBoolean(printing)

    pb.sendToPlayersNearHost(t)
  }

  def sendRackInventory(t: tileentity.Rack) {
    val pb = new SimplePacketBuilder(PacketType.RackInventory)

    pb.writeTileEntity(t)
    pb.writeInt(t.getContainerSize)
    for (slot <- 0 until t.getContainerSize) {
      pb.writeInt(slot)
      pb.writeItemStack(t.getItem(slot))
    }

    pb.sendToPlayersNearTileEntity(t)
  }

  def sendRackInventory(t: tileentity.Rack, slot: Int): Unit = {
    val pb = new SimplePacketBuilder(PacketType.RackInventory)

    pb.writeTileEntity(t)
    pb.writeInt(1)
    pb.writeInt(slot)
    pb.writeItemStack(t.getItem(slot))

    pb.sendToPlayersNearTileEntity(t)
  }

  def sendRackMountableData(t: tileentity.Rack, mountable: Int) {
    val pb = new SimplePacketBuilder(PacketType.RackMountableData)

    pb.writeTileEntity(t)
    pb.writeInt(mountable)
    pb.writeNBT(t.lastData(mountable))

    pb.sendToPlayersNearTileEntity(t)
  }

  def sendRaidChange(t: tileentity.Raid) {
    val pb = new SimplePacketBuilder(PacketType.RaidStateChange)

    pb.writeTileEntity(t)
    for (slot <- 0 until t.getContainerSize) {
      pb.writeBoolean(!t.getItem(slot).isEmpty)
    }

    pb.sendToPlayersNearTileEntity(t)
  }

  def sendRedstoneState(t: RedstoneAware) {
    val pb = new SimplePacketBuilder(PacketType.RedstoneState)

    pb.writeTileEntity(t)
    pb.writeBoolean(t.isOutputEnabled)
    for (d <- Direction.values) {
      pb.writeByte(t.getOutput(d))
    }

    pb.sendToPlayersNearTileEntity(t)
  }

  def sendRobotAssembling(t: tileentity.Assembler, assembling: Boolean) {
    val pb = new SimplePacketBuilder(PacketType.RobotAssemblingState)

    pb.writeTileEntity(t)
    pb.writeBoolean(assembling)

    pb.sendToPlayersNearHost(t)
  }

  def sendRobotMove(t: tileentity.Robot, position: BlockPos, direction: Direction) {
    val pb = new SimplePacketBuilder(PacketType.RobotMove)

    // Custom pb.writeTileEntity() with fake coordinates (valid for the client).
    pb.writeUTF(t.world.dimension.location.toString)
    pb.writeInt(position.getX)
    pb.writeInt(position.getY)
    pb.writeInt(position.getZ)
    pb.writeDirection(Option(direction))

    pb.sendToPlayersNearTileEntity(t)
  }

  def sendRobotAnimateSwing(t: tileentity.Robot) {
    val pb = new SimplePacketBuilder(PacketType.RobotAnimateSwing)

    pb.writeTileEntity(t.proxy)
    pb.writeInt(t.animationTicksTotal)

    pb.sendToPlayersNearTileEntity(t, Option(64))
  }

  def sendRobotAnimateTurn(t: tileentity.Robot) {
    val pb = new SimplePacketBuilder(PacketType.RobotAnimateTurn)

    pb.writeTileEntity(t.proxy)
    pb.writeByte(t.turnAxis)
    pb.writeInt(t.animationTicksTotal)

    pb.sendToPlayersNearTileEntity(t, Option(64))
  }

  def sendRobotInventory(t: tileentity.Robot, slot: Int, stack: ItemStack) {
    val pb = new SimplePacketBuilder(PacketType.RobotInventoryChange)

    pb.writeTileEntity(t.proxy)
    pb.writeInt(slot)
    pb.writeItemStack(stack)

    pb.sendToPlayersNearTileEntity(t)
  }

  def sendRobotLightChange(t: tileentity.Robot) {
    val pb = new SimplePacketBuilder(PacketType.RobotLightChange)

    pb.writeTileEntity(t.proxy)
    pb.writeInt(t.info.lightColor)

    pb.sendToPlayersNearTileEntity(t, Option(64))
  }

  def sendRobotNameChange(t: tileentity.Robot) {
    val pb = new SimplePacketBuilder(PacketType.RobotNameChange)

    pb.writeTileEntity(t.proxy)
    val name = t.name
    val len = name.length.toShort
    pb.writeShort(len)
    for (x <- 0 until len) {
      pb.writeChar(name(x))
    }

    pb.sendToPlayersNearTileEntity(t)
  }

  def sendRobotSelectedSlotChange(t: tileentity.Robot) {
    val pb = new SimplePacketBuilder(PacketType.RobotSelectedSlotChange)

    pb.writeTileEntity(t.proxy)
    pb.writeInt(t.selectedSlot)

    pb.sendToPlayersNearTileEntity(t, Option(16))
  }

  def sendRotatableState(t: Rotatable) {
    val pb = new SimplePacketBuilder(PacketType.RotatableState)

    pb.writeTileEntity(t)
    pb.writeDirection(Option(t.pitch))
    pb.writeDirection(Option(t.yaw))

    pb.sendToPlayersNearTileEntity(t)
  }

  def sendSwitchActivity(t: tileentity.Relay) {
    val pb = new SimplePacketBuilder(PacketType.SwitchActivity)

    pb.writeTileEntity(t)

    pb.sendToPlayersNearTileEntity(t, Option(64))
  }

  def appendTextBufferColorChange(pb: PacketBuilder, foreground: PackedColor.Color, background: PackedColor.Color) {
    pb.writePacketType(PacketType.TextBufferMultiColorChange)

    pb.writeInt(foreground.value)
    pb.writeBoolean(foreground.isPalette)
    pb.writeInt(background.value)
    pb.writeBoolean(background.isPalette)
  }

  def appendTextBufferCopy(pb: PacketBuilder, col: Int, row: Int, w: Int, h: Int, tx: Int, ty: Int) {
    pb.writePacketType(PacketType.TextBufferMultiCopy)

    pb.writeInt(col)
    pb.writeInt(row)
    pb.writeInt(w)
    pb.writeInt(h)
    pb.writeInt(tx)
    pb.writeInt(ty)
  }

  def appendTextBufferDepthChange(pb: PacketBuilder, value: api.internal.TextBuffer.ColorDepth) {
    pb.writePacketType(PacketType.TextBufferMultiDepthChange)

    pb.writeInt(value.ordinal)
  }

  def appendTextBufferFill(pb: PacketBuilder, col: Int, row: Int, w: Int, h: Int, c: Char) {
    pb.writePacketType(PacketType.TextBufferMultiFill)

    pb.writeInt(col)
    pb.writeInt(row)
    pb.writeInt(w)
    pb.writeInt(h)
    pb.writeChar(c)
  }

  def appendTextBufferPaletteChange(pb: PacketBuilder, index: Int, color: Int) {
    pb.writePacketType(PacketType.TextBufferMultiPaletteChange)

    pb.writeInt(index)
    pb.writeInt(color)
  }

  def appendTextBufferResolutionChange(pb: PacketBuilder, w: Int, h: Int) {
    pb.writePacketType(PacketType.TextBufferMultiResolutionChange)

    pb.writeInt(w)
    pb.writeInt(h)
  }

  def appendTextBufferViewportResolutionChange(pb: PacketBuilder, w: Int, h: Int) {
    pb.writePacketType(PacketType.TextBufferMultiViewportResolutionChange)

    pb.writeInt(w)
    pb.writeInt(h)
  }

  def appendTextBufferMaxResolutionChange(pb: PacketBuilder, w: Int, h: Int): Unit = {
    pb.writePacketType(PacketType.TextBufferMultiMaxResolutionChange)

    pb.writeInt(w)
    pb.writeInt(h)
  }

  def appendTextBufferSet(pb: PacketBuilder, col: Int, row: Int, s: String, vertical: Boolean) {
    pb.writePacketType(PacketType.TextBufferMultiSet)

    pb.writeInt(col)
    pb.writeInt(row)
    pb.writeUTF(s)
    pb.writeBoolean(vertical)
  }

  def appendTextBufferBitBlt(pb: PacketBuilder, col: Int, row: Int, w: Int, h: Int, owner: String, id: Int, fromCol: Int, fromRow: Int): Unit = {
    pb.writePacketType(PacketType.TextBufferBitBlt)

    pb.writeInt(col)
    pb.writeInt(row)
    pb.writeInt(w)
    pb.writeInt(h)
    pb.writeUTF(owner)
    pb.writeInt(id)
    pb.writeInt(fromCol)
    pb.writeInt(fromRow)
  }

  def appendTextBufferRamInit(pb: PacketBuilder, address: String, id: Int, nbt: CompoundNBT): Unit = {
    pb.writePacketType(PacketType.TextBufferRamInit)

    pb.writeUTF(address)
    pb.writeInt(id)
    pb.writeNBT(nbt)
  }

  def appendTextBufferRamDestroy(pb: PacketBuilder, owner: String, id: Int): Unit = {
    pb.writePacketType(PacketType.TextBufferRamDestroy)
    pb.writeUTF(owner)
    pb.writeInt(id)
  }

  def appendTextBufferRawSetText(pb: PacketBuilder, col: Int, row: Int, text: Array[Array[Char]]) {
    pb.writePacketType(PacketType.TextBufferMultiRawSetText)

    pb.writeInt(col)
    pb.writeInt(row)
    pb.writeShort(text.length.toShort)
    for (y <- 0 until text.length.toShort) {
      val line = text(y)
      pb.writeShort(line.length.toShort)
      for (x <- 0 until line.length.toShort) {
        pb.writeChar(line(x))
      }
    }
  }

  def appendTextBufferRawSetBackground(pb: PacketBuilder, col: Int, row: Int, color: Array[Array[Int]]) {
    pb.writePacketType(PacketType.TextBufferMultiRawSetBackground)

    pb.writeInt(col)
    pb.writeInt(row)
    pb.writeShort(color.length.toShort)
    for (y <- 0 until color.length.toShort) {
      val line = color(y)
      pb.writeShort(line.length.toShort)
      for (x <- 0 until line.length.toShort) {
        pb.writeInt(line(x))
      }
    }
  }

  def appendTextBufferRawSetForeground(pb: PacketBuilder, col: Int, row: Int, color: Array[Array[Int]]) {
    pb.writePacketType(PacketType.TextBufferMultiRawSetForeground)

    pb.writeInt(col)
    pb.writeInt(row)
    pb.writeShort(color.length.toShort)
    for (y <- 0 until color.length.toShort) {
      val line = color(y)
      pb.writeShort(line.length.toShort)
      for (x <- 0 until line.length.toShort) {
        pb.writeInt(line(x))
      }
    }
  }

  def sendTextBufferInit(address: String, value: CompoundNBT, player: ServerPlayerEntity) {
    val pb = new CompressedPacketBuilder(PacketType.TextBufferInit)

    pb.writeUTF(address)
    pb.writeNBT(value)

    pb.sendToPlayer(player)
  }

  def sendTextBufferPowerChange(address: String, hasPower: Boolean, host: EnvironmentHost) {
    val pb = new SimplePacketBuilder(PacketType.TextBufferPowerChange)

    pb.writeUTF(address)
    pb.writeBoolean(hasPower)

    pb.sendToPlayersNearHost(host)
  }

  def sendScreenTouchMode(t: tileentity.Screen, value: Boolean) {
    val pb = new SimplePacketBuilder(PacketType.ScreenTouchMode)

    pb.writeTileEntity(t)
    pb.writeBoolean(value)

    pb.sendToPlayersNearTileEntity(t)
  }

  def sendSound(world: World, x: Double, y: Double, z: Double, sound: ResourceLocation, category: SoundCategory, range: Double) {
    val pb = new SimplePacketBuilder(PacketType.SoundEffect)

    pb.writeUTF(world.dimension.location.toString)
    pb.writeDouble(x)
    pb.writeDouble(y)
    pb.writeDouble(z)
    pb.writeUTF(sound.toString)
    pb.writeByte(category.ordinal())
    pb.writeFloat(range.toFloat)

    pb.sendToNearbyPlayers(world, x, y, z, Option(range))
  }

  def sendSound(world: World, x: Double, y: Double, z: Double, frequency: Int, duration: Int) {
    val pb = new SimplePacketBuilder(PacketType.Sound)

    val blockPos = BlockPosition(x, y, z)
    pb.writeUTF(world.dimension.location.toString)
    pb.writeInt(blockPos.x)
    pb.writeInt(blockPos.y)
    pb.writeInt(blockPos.z)
    pb.writeShort(frequency.toShort)
    pb.writeShort(duration.toShort)

    pb.sendToNearbyPlayers(world, x, y, z, Option(32))
  }

  def sendSound(world: World, x: Double, y: Double, z: Double, pattern: String) {
    val pb = new SimplePacketBuilder(PacketType.SoundPattern)

    val blockPos = BlockPosition(x, y, z)
    pb.writeUTF(world.dimension.location.toString)
    pb.writeInt(blockPos.x)
    pb.writeInt(blockPos.y)
    pb.writeInt(blockPos.z)
    pb.writeUTF(pattern)

    pb.sendToNearbyPlayers(world, x, y, z, Option(32))
  }

  def sendTransposerActivity(t: tileentity.Transposer) {
    val pb = new SimplePacketBuilder(PacketType.TransposerActivity)

    pb.writeTileEntity(t)

    pb.sendToPlayersNearTileEntity(t, Option(32))
  }

  def sendWaypointLabel(t: Waypoint): Unit = {
    val pb = new SimplePacketBuilder(PacketType.WaypointLabel)

    pb.writeTileEntity(t)
    pb.writeUTF(t.label)

    pb.sendToPlayersNearTileEntity(t)
  }
}
