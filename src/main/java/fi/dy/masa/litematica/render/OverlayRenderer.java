package fi.dy.masa.litematica.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import javax.annotation.Nullable;
import org.lwjgl.opengl.GL11;
import com.google.common.collect.ImmutableMap;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.config.Hotkeys;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicVerifier;
import fi.dy.masa.litematica.data.SchematicVerifier.BlockMismatch;
import fi.dy.masa.litematica.data.SchematicVerifier.MismatchType;
import fi.dy.masa.litematica.gui.widgets.WidgetSchematicVerificationResult.BlockMismatchInfo;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionManager;
import fi.dy.masa.litematica.util.ItemUtils;
import fi.dy.masa.litematica.util.PositionUtils.Corner;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.RayTraceUtils.RayTraceWrapper;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.config.HudAlignment;
import fi.dy.masa.malilib.util.Color4f;
import fi.dy.masa.malilib.util.WorldUtils;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.MainWindow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.state.DirectionProperty;
import net.minecraft.state.IProperty;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.registry.IRegistry;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

public class OverlayRenderer
{
    private static final OverlayRenderer INSTANCE = new OverlayRenderer();

    // https://stackoverflow.com/questions/470690/how-to-automatically-generate-n-distinct-colors
    public static final int[] KELLY_COLORS = {
            0xFFB300,    // Vivid Yellow
            0x803E75,    // Strong Purple
            0xFF6800,    // Vivid Orange
            0xA6BDD7,    // Very Light Blue
            0xC10020,    // Vivid Red
            0xCEA262,    // Grayish Yellow
            0x817066,    // Medium Gray
            // The following don't work well for people with defective color vision
            0x007D34,    // Vivid Green
            0xF6768E,    // Strong Purplish Pink
            0x00538A,    // Strong Blue
            0xFF7A5C,    // Strong Yellowish Pink
            0x53377A,    // Strong Violet
            0xFF8E00,    // Vivid Orange Yellow
            0xB32851,    // Strong Purplish Red
            0xF4C800,    // Vivid Greenish Yellow
            0x7F180D,    // Strong Reddish Brown
            0x93AA00,    // Vivid Yellowish Green
            0x593315,    // Deep Yellowish Brown
            0xF13A13,    // Vivid Reddish Orange
            0x232C16     // Dark Olive Green
        };

    private final Minecraft mc;
    private final Map<SchematicPlacement, ImmutableMap<String, Box>> placements = new HashMap<>();
    private Color4f colorPos1 = new Color4f(1f, 0.0625f, 0.0625f);
    private Color4f colorPos2 = new Color4f(0.0625f, 0.0625f, 1f);
    private Color4f colorOverlapping = new Color4f(1f, 0.0625f, 1f);
    private Color4f colorX = new Color4f(   1f, 0.25f, 0.25f);
    private Color4f colorY = new Color4f(0.25f,    1f, 0.25f);
    private Color4f colorZ = new Color4f(0.25f, 0.25f,    1f);
    private Color4f colorArea = new Color4f(1f, 1f, 1f);
    private Color4f colorBoxPlacementSelected = new Color4f(0x16 / 255f, 1f, 1f);
    private Color4f colorSelectedCorner = new Color4f(0f, 1f, 1f);
    private Color4f colorAreaOrigin = new Color4f(1f, 0x90 / 255f, 0x10 / 255f);

    private long infoUpdateTime;
    private List<String> blockInfoLines = new ArrayList<>();

    private OverlayRenderer()
    {
        this.mc = Minecraft.getInstance();
    }

    public static OverlayRenderer getInstance()
    {
        return INSTANCE;
    }

    public void updatePlacementCache()
    {
        this.placements.clear();
        List<SchematicPlacement> list = DataManager.getSchematicPlacementManager().getAllSchematicsPlacements();

        for (SchematicPlacement placement : list)
        {
            if (placement.isEnabled())
            {
                this.placements.put(placement, placement.getSubRegionBoxes(RequiredEnabled.PLACEMENT_ENABLED));
            }
        }
    }

    public void renderSelectionAreas(float partialTicks)
    {
        Entity renderViewEntity = this.mc.getRenderViewEntity();
        float expand = 0.001f;
        float lineWidthBlockBox = 2f;
        float lineWidthArea = 1.5f;

        SelectionManager sm = DataManager.getSelectionManager();
        AreaSelection currentSelection = sm.getCurrentSelection();
        final boolean hasWork = currentSelection != null || this.placements.isEmpty() == false;

        if (hasWork)
        {
            GlStateManager.depthMask(true);
            GlStateManager.disableLighting();
            GlStateManager.disableTexture2D();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.01F);
            GlStateManager.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            GlStateManager.pushMatrix();
        }

        if (currentSelection != null)
        {
            GlStateManager.enablePolygonOffset();
            GlStateManager.polygonOffset(-1.2f, -0.2f);
            GlStateManager.depthMask(false);

            Box currentBox = currentSelection.getSelectedSubRegionBox();

            for (Box box : currentSelection.getAllSubRegionBoxes())
            {
                BoxType type = box == currentBox ? BoxType.AREA_SELECTED : BoxType.AREA_UNSELECTED;
                this.renderSelectionBox(box, type, expand, lineWidthBlockBox, lineWidthArea, renderViewEntity, partialTicks, null);
            }

            Color4f color = currentSelection.isOriginSelected() ? this.colorSelectedCorner : this.colorAreaOrigin;
            RenderUtils.renderBlockOutline(currentSelection.getOrigin(), expand, lineWidthBlockBox, color, renderViewEntity, partialTicks);

            GlStateManager.depthMask(true);
            GlStateManager.polygonOffset(0f, 0f);
            GlStateManager.disablePolygonOffset();
        }

        if (this.placements.isEmpty() == false)
        {
            SchematicPlacementManager spm = DataManager.getSchematicPlacementManager();
            SchematicPlacement currentPlacement = spm.getSelectedSchematicPlacement();

            for (Map.Entry<SchematicPlacement, ImmutableMap<String, Box>> entry : this.placements.entrySet())
            {
                SchematicPlacement schematicPlacement = entry.getKey();
                ImmutableMap<String, Box> boxMap = entry.getValue();
                boolean origin = schematicPlacement.getSelectedSubRegionPlacement() == null;

                for (Map.Entry<String, Box> entryBox : boxMap.entrySet())
                {
                    String boxName = entryBox.getKey();
                    boolean boxSelected = schematicPlacement == currentPlacement && (origin || boxName.equals(schematicPlacement.getSelectedSubRegionName()));
                    BoxType type = boxSelected ? BoxType.PLACEMENT_SELECTED : BoxType.PLACEMENT_UNSELECTED;
                    this.renderSelectionBox(entryBox.getValue(), type, expand, 1f, 1f, renderViewEntity, partialTicks, schematicPlacement);
                }

                Color4f color = schematicPlacement == currentPlacement && origin ? this.colorSelectedCorner : schematicPlacement.getBoxesBBColor();
                RenderUtils.renderBlockOutline(schematicPlacement.getOrigin(), expand, lineWidthBlockBox, color, renderViewEntity, partialTicks);

                if (Configs.Visuals.RENDER_PLACEMENT_ENCLOSING_BOX.getBooleanValue())
                {
                    Box box = schematicPlacement.getEclosingBox();

                    if (schematicPlacement.shouldRenderEnclosingBox() && box != null)
                    {
                        RenderUtils.renderAreaOutline(box.getPos1(), box.getPos2(), 1f, color, color, color, renderViewEntity, partialTicks);

                        if (Configs.Visuals.RENDER_PLACEMENT_ENCLOSING_BOX_SIDES.getBooleanValue())
                        {
                            float alpha = (float) Configs.Visuals.PLACEMENT_BOX_SIDE_ALPHA.getDoubleValue();
                            color = new Color4f(color.r, color.g, color.b, alpha);
                            RenderUtils.renderAreaSides(box.getPos1(), box.getPos2(), color, renderViewEntity, partialTicks);
                        }
                    }
                }
            }
        }

        if (hasWork)
        {
            GlStateManager.popMatrix();
            GlStateManager.enableTexture2D();
            GlStateManager.enableCull();
            GlStateManager.enableLighting();
            GlStateManager.depthMask(true);
        }
    }

    public void renderSelectionBox(Box box, BoxType boxType, float expand,
            float lineWidthBlockBox, float lineWidthArea, Entity renderViewEntity, float partialTicks, @Nullable SchematicPlacement placement)
    {
        BlockPos pos1 = box.getPos1();
        BlockPos pos2 = box.getPos2();

        if (pos1 == null && pos2 == null)
        {
            return;
        }

        Color4f color1;
        Color4f color2;
        Color4f colorX;
        Color4f colorY;
        Color4f colorZ;

        switch (boxType)
        {
            case AREA_SELECTED:
                colorX = this.colorX;
                colorY = this.colorY;
                colorZ = this.colorZ;
                break;
            case AREA_UNSELECTED:
                colorX = this.colorArea;
                colorY = this.colorArea;
                colorZ = this.colorArea;
                break;
            case PLACEMENT_SELECTED:
                colorX = this.colorBoxPlacementSelected;
                colorY = this.colorBoxPlacementSelected;
                colorZ = this.colorBoxPlacementSelected;
                break;
            case PLACEMENT_UNSELECTED:
                Color4f color = placement.getBoxesBBColor();
                colorX = color;
                colorY = color;
                colorZ = color;
                break;
            default:
                return;
        }

        Color4f sideColor;

        if (boxType == BoxType.PLACEMENT_SELECTED)
        {
            color1 = this.colorBoxPlacementSelected;
            color2 = color1;
            float alpha = (float) Configs.Visuals.PLACEMENT_BOX_SIDE_ALPHA.getDoubleValue();
            sideColor = new Color4f(color1.r, color1.g, color1.b, alpha);
        }
        else if (boxType == BoxType.PLACEMENT_UNSELECTED)
        {
            color1 = placement.getBoxesBBColor();
            color2 = color1;
            float alpha = (float) Configs.Visuals.PLACEMENT_BOX_SIDE_ALPHA.getDoubleValue();
            sideColor = new Color4f(color1.r, color1.g, color1.b, alpha);
        }
        else
        {
            color1 = box.getSelectedCorner() == Corner.CORNER_1 ? this.colorSelectedCorner : this.colorPos1;
            color2 = box.getSelectedCorner() == Corner.CORNER_2 ? this.colorSelectedCorner : this.colorPos2;
            sideColor = Color4f.fromColor(Configs.Colors.AREA_SELECTION_BOX_SIDE_COLOR.getIntegerValue());
        }

        if (pos1 != null && pos2 != null)
        {
            if (pos1.equals(pos2) == false)
            {
                RenderUtils.renderBlockOutline(pos1, expand, lineWidthBlockBox, color1, renderViewEntity, partialTicks);
                RenderUtils.renderBlockOutline(pos2, expand, lineWidthBlockBox, color2, renderViewEntity, partialTicks);

                RenderUtils.renderAreaOutlineNoCorners(pos1, pos2, lineWidthArea, colorX, colorY, colorZ, renderViewEntity, partialTicks);

                if (((boxType == BoxType.AREA_SELECTED || boxType == BoxType.AREA_UNSELECTED) &&
                      Configs.Visuals.RENDER_AREA_SELECTION_BOX_SIDES.getBooleanValue())
                    ||
                     ((boxType == BoxType.PLACEMENT_SELECTED || boxType == BoxType.PLACEMENT_UNSELECTED) &&
                       Configs.Visuals.RENDER_PLACEMENT_BOX_SIDES.getBooleanValue()))
                {
                    RenderUtils.renderAreaSides(pos1, pos2, sideColor, renderViewEntity, partialTicks);
                }
            }
            else
            {
                RenderUtils.renderBlockOutlineOverlapping(pos1, expand, lineWidthBlockBox, color1, color2, this.colorOverlapping, renderViewEntity, partialTicks);
            }
        }
        else
        {
            if (pos1 != null)
            {
                RenderUtils.renderBlockOutline(pos1, expand, lineWidthBlockBox, color1, renderViewEntity, partialTicks);
            }

            if (pos2 != null)
            {
                RenderUtils.renderBlockOutline(pos2, expand, lineWidthBlockBox, color2, renderViewEntity, partialTicks);
            }
        }
    }

    public void renderSchematicMismatches(float partialTicks)
    {
        SchematicPlacement placement = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();

        if (placement != null && placement.hasVerifier())
        {
            SchematicVerifier verifier = placement.getSchematicVerifier();

            if (verifier.getSelectedMismatchTypeForRender() != null)
            {
                List<BlockPos> posList = verifier.getSelectedMismatchPositionsForRender();
                RayTraceResult trace = RayTraceUtils.traceToPositions(posList, this.mc.player, 10);
                BlockPos posLook = trace != null && trace.type == RayTraceResult.Type.BLOCK ? trace.getBlockPos() : null;
                this.renderSchematicMismatches(verifier.getSelectedMismatchTypeForRender(), posList, posLook, partialTicks);
            }
        }
    }

    private void renderSchematicMismatches(MismatchType type, List<BlockPos> posList, @Nullable BlockPos lookPos, float partialTicks)
    {
        GlStateManager.disableDepthTest();
        GlStateManager.depthMask(false);
        GlStateManager.disableLighting();
        GlStateManager.disableTexture2D();
        GlStateManager.pushMatrix();

        if (posList.isEmpty() == false)
        {
            GlStateManager.lineWidth(2f);

            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.getBuffer();
            buffer.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION_COLOR);

            for (BlockPos pos : posList)
            {
                if (lookPos == null || lookPos.equals(pos) == false)
                {
                    RenderUtils.renderBlockOutlineBatched(pos, 0.002, type.getColor(), this.mc.player, buffer, partialTicks);
                }
            }

            if (lookPos != null)
            {
                tessellator.draw();
                buffer.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION_COLOR);

                GlStateManager.lineWidth(6f);
                RenderUtils.renderBlockOutlineBatched(lookPos, 0.002, type.getColor(), this.mc.player, buffer, partialTicks);
            }

            tessellator.draw();
        }

        if (Configs.Visuals.RENDER_ERROR_MARKER_SIDES.getBooleanValue())
        {
            GlStateManager.enableBlend();
            GlStateManager.disableCull();

            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.getBuffer();
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);

            for (BlockPos pos : posList)
            {
                Color4f color = type.getColor();
                Color4f colorSides = new Color4f(color.r, color.g, color.b, (float) Configs.InfoOverlays.VERIFIER_ERROR_HILIGHT_ALPHA.getDoubleValue());
                RenderUtils.renderAreaSidesBatched(pos, pos, colorSides, 0.002, this.mc.player, partialTicks, buffer);
            }

            tessellator.draw();

            GlStateManager.disableBlend();
        }

        GlStateManager.popMatrix();
        GlStateManager.enableTexture2D();
        GlStateManager.enableCull();
        GlStateManager.enableLighting();
        GlStateManager.depthMask(true);
        GlStateManager.enableDepthTest();
    }

    public void renderHoverInfo(Minecraft mc)
    {
        if (mc.world != null && mc.player != null)
        {
            if (Configs.InfoOverlays.ENABLE_VERIFIER_OVERLAY_RENDERING.getBooleanValue() &&
                Configs.InfoOverlays.RENDER_BLOCK_INFO_OVERLAY.getBooleanValue() &&
                (Hotkeys.RENDER_INFO_OVERLAY.getKeybind().isValid() == false ||
                 Hotkeys.RENDER_INFO_OVERLAY.getKeybind().isKeybindHeld()))
            {
                this.renderVerifierOverlay(mc);
            }

            boolean renderBlockInfoLines = Configs.InfoOverlays.RENDER_BLOCK_INFO_LINES.getBooleanValue();
            boolean renderInfoOverlay = Configs.InfoOverlays.ENABLE_INFO_OVERLAY_RENDERING.getBooleanValue() &&
                                        (Hotkeys.RENDER_INFO_OVERLAY.getKeybind().isValid() == false ||
                                        Hotkeys.RENDER_INFO_OVERLAY.getKeybind().isKeybindHeld());
            RayTraceWrapper traceWrapper = null;

            if (renderBlockInfoLines || renderInfoOverlay)
            {
                traceWrapper = RayTraceUtils.getGenericTrace(mc.world, mc.player, 10, true);
            }

            if (traceWrapper != null)
            {
                if (renderBlockInfoLines)
                {
                    this.renderBlockInfoLines(traceWrapper, mc);
                }

                if (renderInfoOverlay)
                {
                    this.renderBlockInfoOverlay(traceWrapper, mc);
                }
            }
        }
    }

    private void renderBlockInfoLines(RayTraceWrapper traceWrapper, Minecraft mc)
    {
        long currentTime = System.currentTimeMillis();

        // Only update the text once per game tick
        if (currentTime - this.infoUpdateTime >= 50)
        {
            this.updateBlockInfoLines(traceWrapper, mc);
            this.infoUpdateTime = currentTime;
        }

        int x = Configs.InfoOverlays.BLOCK_INFO_OFFSET_X.getIntegerValue();
        int y = Configs.InfoOverlays.BLOCK_INFO_OFFSET_Y.getIntegerValue();
        double fontScale = Configs.InfoOverlays.BLOCK_INFO_FONT_SCALE.getDoubleValue();
        int textColor = 0xFFFFFFFF;
        int bgColor = 0xA0505050;
        HudAlignment alignment = (HudAlignment) Configs.InfoOverlays.BLOCK_INFO_LINES_ALIGNMENT.getOptionListValue();
        boolean useBackground = true;
        boolean useShadow = false;

        fi.dy.masa.malilib.render.RenderUtils.renderText(mc, x, y, fontScale, textColor, bgColor, alignment, useBackground, useShadow, this.blockInfoLines);
    }

    private void renderVerifierOverlay(Minecraft mc)
    {
        SchematicPlacement placement = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();

        if (placement != null && placement.hasVerifier())
        {
            SchematicVerifier verifier = placement.getSchematicVerifier();
            List<BlockPos> posList = verifier.getSelectedMismatchPositionsForRender();
            RayTraceResult trace = RayTraceUtils.traceToPositions(posList, mc.player, 10);

            if (trace != null && trace.type == RayTraceResult.Type.BLOCK)
            {
                BlockMismatch mismatch = verifier.getMismatchForPosition(trace.getBlockPos());

                if (mismatch != null)
                {
                    BlockMismatchInfo info = new BlockMismatchInfo(mismatch.stateExpected, mismatch.stateFound);
                    MainWindow window = mc.mainWindow;
                    info.render(window.getScaledWidth() / 2 - info.getTotalWidth() / 2, window.getScaledHeight() / 2 + 10, mc);
                    return;
                }
            }
        }
    }

    private void renderBlockInfoOverlay(RayTraceWrapper traceWrapper, Minecraft mc)
    {
        MainWindow window = mc.mainWindow;

        BlockPos pos = traceWrapper.getRayTraceResult().getBlockPos();
        IBlockState stateClient = mc.world.getBlockState(pos);
        World worldClient = WorldUtils.getBestWorld(mc);

        World worldSchematic = SchematicWorldHandler.getSchematicWorld();
        IBlockState stateSchematic = worldSchematic.getBlockState(pos);
        IBlockState air = Blocks.AIR.getDefaultState();

        ItemUtils.setItemForBlock(worldSchematic, pos, stateSchematic);
        ItemUtils.setItemForBlock(mc.world, pos, stateClient);

        // Not just a missing block
        if (stateSchematic != stateClient && stateClient != air && stateSchematic != air)
        {
            BlockMismatchInfo info = new BlockMismatchInfo(stateSchematic, stateClient);
            info.render(window.getScaledWidth() / 2 - info.getTotalWidth() / 2, window.getScaledHeight() / 2 + 10, mc);

            RenderUtils.renderInventoryOverlay(-1, worldSchematic, pos, mc);
            RenderUtils.renderInventoryOverlay(1, worldClient, pos, mc);
        }
        else if (traceWrapper.getHitType() == RayTraceWrapper.HitType.VANILLA)
        {
            BlockInfo info = new BlockInfo(stateClient, "litematica.gui.label.block_info.state_client");
            info.render(window.getScaledWidth() / 2 - info.getTotalWidth() / 2, window.getScaledHeight() / 2 + 10, mc);
            RenderUtils.renderInventoryOverlay(0, worldClient, pos, mc);
        }
        else if (traceWrapper.getHitType() == RayTraceWrapper.HitType.SCHEMATIC_BLOCK)
        {
            int xOffset = 0;
            TileEntity te = mc.world.getTileEntity(pos);

            if (te instanceof IInventory)
            {
                BlockInfo info = new BlockInfo(stateClient, "litematica.gui.label.block_info.state_client");
                info.render(window.getScaledWidth() / 2 - info.getTotalWidth() / 2, window.getScaledHeight() / 2 + 10, mc);
                RenderUtils.renderInventoryOverlay(1, worldClient, pos, mc);
                xOffset = -1;
            }
            else
            {
                BlockInfo info = new BlockInfo(stateSchematic, "litematica.gui.label.block_info.state_schematic");
                info.render(window.getScaledWidth() / 2 - info.getTotalWidth() / 2, window.getScaledHeight() / 2 + 10, mc);
            }

            RenderUtils.renderInventoryOverlay(xOffset, worldSchematic, pos, mc);
        }
    }

    private void updateBlockInfoLines(RayTraceWrapper traceWrapper, Minecraft mc)
    {
        this.blockInfoLines.clear();

        BlockPos pos = traceWrapper.getRayTraceResult().getBlockPos();
        IBlockState stateClient = mc.world.getBlockState(pos);

        World worldSchematic = SchematicWorldHandler.getSchematicWorld();
        IBlockState stateSchematic = worldSchematic.getBlockState(pos);
        IBlockState air = Blocks.AIR.getDefaultState();
        String ul = TextFormatting.UNDERLINE.toString();

        if (stateSchematic != stateClient && stateSchematic != air && stateClient != air)
        {
            this.blockInfoLines.add(ul + "Schematic:");
            this.addBlockInfoLines(stateSchematic);

            this.blockInfoLines.add("");
            this.blockInfoLines.add(ul + "Client:");
            this.addBlockInfoLines(stateClient);
        }
        else if (traceWrapper.getHitType() == RayTraceWrapper.HitType.SCHEMATIC_BLOCK)
        {
            this.blockInfoLines.add(ul + "Schematic:");
            this.addBlockInfoLines(stateSchematic);
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends Comparable<T>> void addBlockInfoLines(IBlockState state)
    {
        this.blockInfoLines.add(String.valueOf(IRegistry.BLOCK.getKey(state.getBlock())));

        for (Entry <IProperty<?>, Comparable<?>> entry : state.getValues().entrySet())
        {
            IProperty<T> property = (IProperty<T>) entry.getKey();
            T value = (T) entry.getValue();
            String valueName = property.getName(value);

            if (property instanceof DirectionProperty)
            {
                valueName = TextFormatting.GOLD + valueName;
            }
            else if (Boolean.TRUE.equals(value))
            {
                valueName = TextFormatting.GREEN + valueName;
            }
            else if (Boolean.FALSE.equals(value))
            {
                valueName = TextFormatting.RED + valueName;
            }
            else if (Integer.class.equals(property.getValueClass()))
            {
                valueName = TextFormatting.GREEN + valueName;
            }

            this.blockInfoLines.add(property.getName() + ": " + valueName);
        }
    }

    private enum BoxType
    {
        AREA_SELECTED,
        AREA_UNSELECTED,
        PLACEMENT_SELECTED,
        PLACEMENT_UNSELECTED;
    }
}
