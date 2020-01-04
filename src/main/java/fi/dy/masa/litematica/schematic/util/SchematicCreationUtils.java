package fi.dy.masa.litematica.schematic.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import com.google.common.collect.ImmutableMap;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import fi.dy.masa.litematica.LiteModLitematica;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic.EntityInfo;
import fi.dy.masa.litematica.schematic.SchematicMetadata;
import fi.dy.masa.litematica.schematic.SchematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.interfaces.IStringConsumer;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.IntBoundingBox;
import fi.dy.masa.malilib.util.NBTUtils;
import fi.dy.masa.malilib.util.StringUtils;

public class SchematicCreationUtils
{
    /**
     * Creates an empty schematic with all the maps and lists and containers already created.
     * This is intended to be used for the chunk-wise schematic creation.
     * @param area
     * @param author
     * @param feedback
     * @return
     */
    public static LitematicaSchematic createEmptySchematic(AreaSelection area, String author)
    {
        List<Box> boxes = PositionUtils.getValidBoxes(area);

        if (boxes.isEmpty())
        {
            InfoUtils.showGuiOrInGameMessage(MessageType.ERROR, StringUtils.translate("litematica.error.schematic.create.no_selections"));
            return null;
        }

        LitematicaSchematic schematic = new LitematicaSchematic(null);
        SchematicMetadata metadata = schematic.getMetadata();

        schematic.setSubRegions(boxes, area.getEffectiveOrigin());

        metadata.setAuthor(author);
        metadata.setName(area.getName());
        metadata.setRegionCount(boxes.size());
        metadata.setTotalVolume(PositionUtils.getTotalVolume(boxes));
        metadata.setEnclosingSize(PositionUtils.getEnclosingAreaSize(boxes));

        return schematic;
    }

    @Nullable
    public static LitematicaSchematic createFromWorld(World world, AreaSelection area, boolean ignoreEntities, String author, IStringConsumer feedback)
    {
        List<Box> boxes = PositionUtils.getValidBoxes(area);

        if (boxes.isEmpty())
        {
            feedback.setString(StringUtils.translate("litematica.error.schematic.create.no_selections"));
            return null;
        }

        LitematicaSchematic schematic = new LitematicaSchematic(null);
        long time = System.currentTimeMillis();

        BlockPos origin = area.getEffectiveOrigin();
        schematic.setSubRegions(boxes, origin);

        takeBlocksFromWorld(schematic, world, boxes);

        if (ignoreEntities == false)
        {
            takeEntitiesFromWorld(schematic, world, boxes, origin);
        }

        SchematicMetadata metadata = schematic.getMetadata();
        metadata.setAuthor(author);
        metadata.setName(area.getName());
        metadata.setTimeCreated(time);
        metadata.setTimeModified(time);
        metadata.setRegionCount(boxes.size());
        metadata.setTotalVolume(PositionUtils.getTotalVolume(boxes));
        metadata.setEnclosingSize(PositionUtils.getEnclosingAreaSize(boxes));
        metadata.setTotalBlocks(schematic.getTotalBlocksReadFromWorld());

        return schematic;
    }

    public static void takeEntityDataFromSchematicaSchematic(LitematicaSchematic schematic,
            SchematicaSchematic schematicaSchematic, String subRegionName)
    {
        Map<BlockPos, NBTTagCompound> blockEntityMap = schematic.getBlockEntityMap(subRegionName);
        List<EntityInfo> schematicEntityList = schematic.getEntityList(subRegionName);

        if (blockEntityMap != null && schematicEntityList != null)
        {
            blockEntityMap.putAll(schematicaSchematic.getTiles());
            schematicEntityList.addAll(schematicaSchematic.getEntities());
        }
        else
        {
            InfoUtils.showGuiOrInGameMessage(MessageType.ERROR, "litematica.message.error.schematic_save.missing_container", subRegionName);
        }
    }

    private static void takeEntitiesFromWorld(LitematicaSchematic schematic, World world, List<Box> boxes, BlockPos origin)
    {
        for (Box box : boxes)
        {
            List<EntityInfo> schematicEntityList = schematic.getEntityList(box.getName());

            if (schematicEntityList == null)
            {
                InfoUtils.showGuiOrInGameMessage(MessageType.ERROR, "litematica.message.error.schematic_save.missing_entity_list", box.getName());
                continue;
            }

            AxisAlignedBB bb = PositionUtils.createEnclosingAABB(box.getPos1(), box.getPos2());
            BlockPos regionPosAbs = box.getPos1();
            List<EntityInfo> list = new ArrayList<>();
            List<Entity> entities = world.getEntitiesInAABBexcluding(null, bb, null);

            for (Entity entity : entities)
            {
                NBTTagCompound tag = new NBTTagCompound();

                if (entity.writeToNBTOptional(tag))
                {
                    Vec3d posVec = new Vec3d(entity.posX - regionPosAbs.getX(), entity.posY - regionPosAbs.getY(), entity.posZ - regionPosAbs.getZ());
                    NBTUtils.writeEntityPositionToTag(posVec, tag);
                    list.add(new EntityInfo(posVec, tag));
                }
            }

            schematicEntityList.addAll(list);
        }
    }

    public static void takeEntitiesFromWorldWithinChunk(LitematicaSchematic schematic, World world, int chunkX, int chunkZ,
            ImmutableMap<String, IntBoundingBox> volumes, ImmutableMap<String, Box> boxes, Set<UUID> existingEntities, BlockPos origin)
    {
        for (Map.Entry<String, IntBoundingBox> entry : volumes.entrySet())
        {
            String regionName = entry.getKey();
            List<EntityInfo> list = schematic.getEntityList(regionName);
            Box box = boxes.get(regionName);

            if (box == null || list == null)
            {
                InfoUtils.showGuiOrInGameMessage(MessageType.ERROR, "litematica.message.error.schematic_save.missing_entity_list", regionName);
                continue;
            }

            AxisAlignedBB bb = PositionUtils.createAABBFrom(entry.getValue());
            List<Entity> entities = world.getEntitiesInAABBexcluding(null, bb, null);
            BlockPos regionPosAbs = box.getPos1();

            for (Entity entity : entities)
            {
                UUID uuid = entity.getUniqueID();
                /*
                if (entity.posX >= bb.minX && entity.posX < bb.maxX &&
                    entity.posY >= bb.minY && entity.posY < bb.maxY &&
                    entity.posZ >= bb.minZ && entity.posZ < bb.maxZ)
                */
                if (existingEntities.contains(uuid) == false)
                {
                    NBTTagCompound tag = new NBTTagCompound();

                    if (entity.writeToNBTOptional(tag))
                    {
                        Vec3d posVec = new Vec3d(entity.posX - regionPosAbs.getX(), entity.posY - regionPosAbs.getY(), entity.posZ - regionPosAbs.getZ());
                        NBTUtils.writeEntityPositionToTag(posVec, tag);
                        list.add(new EntityInfo(posVec, tag));
                        existingEntities.add(uuid);
                    }
                }
            }
        }
    }

    private static void takeBlocksFromWorld(LitematicaSchematic schematic, World world, List<Box> boxes)
    {
        BlockPos.MutableBlockPos posMutable = new BlockPos.MutableBlockPos(0, 0, 0);
        long totalBlocks = 0;

        for (Box box : boxes)
        {
            String regionName = box.getName();
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(regionName);
            Map<BlockPos, NBTTagCompound> blockEntityMap = schematic.getBlockEntityMap(regionName);
            Map<BlockPos, NextTickListEntry> tickMap = schematic.getBlockTickMap(regionName);

            if (container == null || blockEntityMap == null || tickMap == null)
            {
                InfoUtils.showGuiOrInGameMessage(MessageType.ERROR, "litematica.message.error.schematic_save.missing_container", regionName);
                continue;
            }

            Vec3i size = box.getSize();
            final int sizeX = Math.abs(size.getX());
            final int sizeY = Math.abs(size.getY());
            final int sizeZ = Math.abs(size.getZ());

            // We want to loop nice & easy from 0 to n here, but the per-sub-region pos1 can be at
            // any corner of the area. Thus we need to offset from the total area origin
            // to the minimum/negative (ie. 0,0 in the loop) corner here.
            final BlockPos minCorner = PositionUtils.getMinCorner(box.getPos1(), box.getPos2());
            final int startX = minCorner.getX();
            final int startY = minCorner.getY();
            final int startZ = minCorner.getZ();

            for (int y = 0; y < sizeY; ++y)
            {
                for (int z = 0; z < sizeZ; ++z)
                {
                    for (int x = 0; x < sizeX; ++x)
                    {
                        posMutable.setPos(x + startX, y + startY, z + startZ);
                        IBlockState state = world.getBlockState(posMutable).getActualState(world, posMutable);
                        container.set(x, y, z, state);

                        if (state.getBlock() != Blocks.AIR)
                        {
                            ++totalBlocks;
                        }

                        if (state.getBlock().hasTileEntity())
                        {
                            TileEntity te = world.getTileEntity(posMutable);

                            if (te != null)
                            {
                                // TODO Add a TileEntity NBT cache from the Chunk packets, to get the original synced data (too)
                                BlockPos pos = new BlockPos(x, y, z);
                                NBTTagCompound tag = te.writeToNBT(new NBTTagCompound());
                                NBTUtils.writeBlockPosToTag(pos, tag);
                                blockEntityMap.put(pos, tag);
                            }
                        }
                    }
                }
            }

            if (world instanceof WorldServer)
            {
                IntBoundingBox structureBB = IntBoundingBox.createProper(
                        startX,         startY,         startZ,
                        startX + sizeX, startY + sizeY, startZ + sizeZ);
                List<NextTickListEntry> pendingTicks = ((WorldServer) world).getPendingBlockUpdates(structureBB.toVanillaBox(), false);

                if (pendingTicks != null)
                {
                    final int listSize = pendingTicks.size();
                    final long currentTime = world.getTotalWorldTime();

                    // The getPendingBlockUpdates() method doesn't check the y-coordinate... :-<
                    for (int i = 0; i < listSize; ++i)
                    {
                        NextTickListEntry entry = pendingTicks.get(i);

                        if (entry.position.getY() >= startY && entry.position.getY() < structureBB.maxY)
                        {
                            // Store the delay, ie. relative time
                            BlockPos posRelative = new BlockPos(
                                    entry.position.getX() - minCorner.getX(),
                                    entry.position.getY() - minCorner.getY(),
                                    entry.position.getZ() - minCorner.getZ());
                            NextTickListEntry newEntry = new NextTickListEntry(posRelative, entry.getBlock());
                            newEntry.setPriority(entry.priority);
                            newEntry.setScheduledTime(entry.scheduledTime - currentTime);

                            tickMap.put(posRelative, newEntry);
                        }
                    }
                }
            }
        }

        schematic.setTotalBlocksReadFromWorld(totalBlocks);
    }

    public static void takeBlocksFromWorldWithinChunk(LitematicaSchematic schematic, World world, int chunkX, int chunkZ,
            ImmutableMap<String, IntBoundingBox> volumes, ImmutableMap<String, Box> boxes)
    {
        BlockPos.MutableBlockPos posMutable = new BlockPos.MutableBlockPos(0, 0, 0);
        long totalBlocks = schematic.getTotalBlocksReadFromWorld();

        for (Map.Entry<String, IntBoundingBox> volumeEntry : volumes.entrySet())
        {
            String regionName = volumeEntry.getKey();
            IntBoundingBox bb = volumeEntry.getValue();
            Box box = boxes.get(regionName);

            if (box == null)
            {
                LiteModLitematica.logger.error("null Box for sub-region '{}' while trying to save chunk-wise schematic", regionName);
                continue;
            }

            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(regionName);
            Map<BlockPos, NBTTagCompound> blockEntityMap = schematic.getBlockEntityMap(regionName);
            Map<BlockPos, NextTickListEntry> tickMap = schematic.getBlockTickMap(regionName);

            if (container == null || blockEntityMap == null || tickMap == null)
            {
                InfoUtils.showGuiOrInGameMessage(MessageType.ERROR, "litematica.message.error.schematic_save.missing_container", regionName);
                LiteModLitematica.logger.error("null map(s) for sub-region '{}' while trying to save chunk-wise schematic", regionName);
                continue;
            }

            // We want to loop nice & easy from 0 to n here, but the per-sub-region pos1 can be at
            // any corner of the area. Thus we need to offset from the total area origin
            // to the minimum/negative corner (ie. 0,0 in the loop) corner here.
            final BlockPos minCorner = PositionUtils.getMinCorner(box.getPos1(), box.getPos2());
            final int offsetX = minCorner.getX();
            final int offsetY = minCorner.getY();
            final int offsetZ = minCorner.getZ();
            // Relative coordinates within the sub-region container:
            final int startX = bb.minX - minCorner.getX();
            final int startY = bb.minY - minCorner.getY();
            final int startZ = bb.minZ - minCorner.getZ();
            final int endX = startX + (bb.maxX - bb.minX);
            final int endY = startY + (bb.maxY - bb.minY);
            final int endZ = startZ + (bb.maxZ - bb.minZ);

            for (int y = startY; y <= endY; ++y)
            {
                for (int z = startZ; z <= endZ; ++z)
                {
                    for (int x = startX; x <= endX; ++x)
                    {
                        posMutable.setPos(x + offsetX, y + offsetY, z + offsetZ);
                        IBlockState state = world.getBlockState(posMutable).getActualState(world, posMutable);
                        container.set(x, y, z, state);

                        if (state.getBlock() != Blocks.AIR)
                        {
                            ++totalBlocks;
                        }

                        if (state.getBlock().hasTileEntity())
                        {
                            TileEntity te = world.getTileEntity(posMutable);

                            if (te != null)
                            {
                                // TODO Add a TileEntity NBT cache from the Chunk packets, to get the original synced data (too)
                                BlockPos pos = new BlockPos(x, y, z);
                                NBTTagCompound tag = te.writeToNBT(new NBTTagCompound());
                                NBTUtils.writeBlockPosToTag(pos, tag);
                                blockEntityMap.put(pos, tag);
                            }
                        }
                    }
                }
            }

            if (world instanceof WorldServer)
            {
                IntBoundingBox structureBB = IntBoundingBox.createProper(
                        offsetX + startX  , offsetY + startY  , offsetZ + startZ  ,
                        offsetX + endX + 1, offsetY + endY + 1, offsetZ + endZ + 1);
                List<NextTickListEntry> pendingTicks = ((WorldServer) world).getPendingBlockUpdates(structureBB.toVanillaBox(), false);

                if (pendingTicks != null)
                {
                    final int listSize = pendingTicks.size();
                    final long currentTime = world.getTotalWorldTime();

                    // The getPendingBlockUpdates() method doesn't check the y-coordinate... :-<
                    for (int i = 0; i < listSize; ++i)
                    {
                        NextTickListEntry entry = pendingTicks.get(i);

                        if (entry.position.getY() >= offsetY && entry.position.getY() < structureBB.maxY)
                        {
                            // Store the delay, ie. relative time
                            BlockPos posRelative = new BlockPos(
                                    entry.position.getX() - minCorner.getX(),
                                    entry.position.getY() - minCorner.getY(),
                                    entry.position.getZ() - minCorner.getZ());
                            NextTickListEntry newEntry = new NextTickListEntry(posRelative, entry.getBlock());
                            newEntry.setPriority(entry.priority);
                            newEntry.setScheduledTime(entry.scheduledTime - currentTime);

                            tickMap.put(posRelative, newEntry);
                        }
                    }
                }
            }
        }

        schematic.setTotalBlocksReadFromWorld(totalBlocks);
    }
}