package litematica.gui;

import java.nio.file.Path;
import com.google.common.collect.ImmutableList;

import malilib.gui.widget.RadioButtonWidget;
import malilib.overlay.message.MessageDispatcher;
import malilib.util.StringUtils;
import litematica.data.DataManager;
import litematica.schematic.ISchematic;
import litematica.schematic.SchematicType;
import litematica.schematic.placement.SchematicPlacementManager;

public class SaveConvertSchematicScreen extends BaseSaveSchematicScreen
{
    protected final RadioButtonWidget<UpdatePlacementsOption> updatePlacementsWidget;
    protected final ISchematic schematic;
    protected final boolean addUpdatePlacementsElement;

    public SaveConvertSchematicScreen(ISchematic schematic,
                                      boolean addUpdatePlacementsElement)
    {
        this(schematic, addUpdatePlacementsElement, "schematic_manager");
    }

    public SaveConvertSchematicScreen(ISchematic schematic,
                                      boolean addUpdatePlacementsElement,
                                      String browserContext)
    {
        super(10, 74, 20 + 170 + 2, 80, browserContext);

        this.schematic = schematic;
        this.addUpdatePlacementsElement = addUpdatePlacementsElement;
        this.originalName = getDefaultFileNameForSchematic(schematic);

        this.updatePlacementsWidget = new RadioButtonWidget<>(UpdatePlacementsOption.VALUES,
                                                              UpdatePlacementsOption::getDisplayString,
                                                              "litematica.hover.save_schematic.update_dependent_placements");
        this.updatePlacementsWidget.setSelection(UpdatePlacementsOption.NONE, false);

        this.fileNameTextField.setText(this.originalName);

        this.setTitle("litematica.title.screen.save_or_convert_schematic", schematic.getMetadata().getName());
    }

    @Override
    protected void reAddActiveWidgets()
    {
        super.reAddActiveWidgets();

        this.addWidget(this.schematicTypeDropdown);

        if (this.addUpdatePlacementsElement)
        {
            this.addWidget(this.updatePlacementsWidget);
        }
    }

    @Override
    protected void updateWidgetPositions()
    {
        if (this.addUpdatePlacementsElement)
        {
            this.listY = 76;
            this.totalListMarginY = 80;
        }
        else
        {
            this.listY = 64;
            this.totalListMarginY = 68;
        }

        super.updateWidgetPositions();

        this.schematicTypeDropdown.setPosition(this.fileNameTextField.getX(), this.fileNameTextField.getBottom() + 2);
        this.saveButton.setPosition(this.schematicTypeDropdown.getRight() + 2, this.fileNameTextField.getBottom() + 2);
        this.updatePlacementsWidget.setPosition(this.saveButton.getRight() + 4, this.saveButton.getY());
    }

    @Override
    protected void saveSchematic()
    {
        boolean isHoldingShift = isShiftDown();
        Path file = this.getSchematicFileIfCanSave(isHoldingShift);

        if (file == null)
        {
            return;
        }

        SchematicType<?> outputType = this.schematicTypeDropdown.getSelectedEntry();
        ISchematic convertedSchematic = this.schematic;

        if (outputType != this.schematic.getType())
        {
            convertedSchematic = outputType.createSchematic(null);

            try
            {
                convertedSchematic.readFrom(this.schematic);
            }
            catch (Exception e)
            {
                MessageDispatcher.error(8000).translate("litematica.message.error.save_schematic.failed_to_convert");
                MessageDispatcher.error(8000).translate(e.getMessage());
                return;
            }
        }

        if (convertedSchematic.writeToFile(file, isHoldingShift))
        {
            this.onSchematicSaved(file);
        }
        else
        {
            String key = "litematica.message.error.save_schematic.failed_to_save_converted";
            MessageDispatcher.error(key, file.getFileName().toString());
        }
    }

    protected void onSchematicSaved(Path newSchematicFile)
    {
        this.schematic.getMetadata().clearModifiedSinceSaved();
        this.onSchematicChange();

        String key = "litematica.message.success.save_schematic_convert";
        MessageDispatcher.success(key, newSchematicFile.getFileName().toString());

        UpdatePlacementsOption option = this.updatePlacementsWidget.getSelection();

        if (this.addUpdatePlacementsElement && option != UpdatePlacementsOption.NONE)
        {
            boolean selectedOnly = option == UpdatePlacementsOption.SELECTED;
            this.updateDependentPlacements(newSchematicFile, selectedOnly);
        }
    }

    protected void updateDependentPlacements(Path newSchematicFile, boolean selectedOnly)
    {
        if (this.schematic != null)
        {
            SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
            manager.updateDependentPlacements(this.schematic, newSchematicFile, selectedOnly);
        }
    }

    public enum UpdatePlacementsOption
    {
        NONE        ("litematica.name.save_schematic.update_placements.none"),
        SELECTED    ("litematica.name.save_schematic.update_placements.selected"),
        ALL         ("litematica.name.save_schematic.update_placements.all");

        public static final ImmutableList<UpdatePlacementsOption> VALUES = ImmutableList.copyOf(values());

        private final String translationKey;

        UpdatePlacementsOption(String translationKey)
        {
            this.translationKey = translationKey;
        }

        public String getDisplayString()
        {
            return StringUtils.translate(this.translationKey);
        }
    }
}
