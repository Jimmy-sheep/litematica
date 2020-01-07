package fi.dy.masa.litematica.gui;

import java.io.File;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.ISchematic;
import fi.dy.masa.litematica.schematic.SchematicType;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.interfaces.IGuiIcon;
import fi.dy.masa.malilib.gui.interfaces.IIconProvider;
import fi.dy.masa.malilib.gui.util.Message.MessageType;
import fi.dy.masa.malilib.gui.widgets.WidgetDropDownList;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.StringUtils;

public class GuiSchematicSaveConvert extends GuiSchematicSaveBase
{
    private final ISchematic schematic;
    private final WidgetDropDownList<SchematicType<?>> widgetOutputType;

    public GuiSchematicSaveConvert(ISchematic schematic, String inputFileName)
    {
        super(schematic, 10, 83);

        this.schematic = schematic;
        this.defaultText = FileUtils.getNameWithoutExtension(inputFileName);

        this.title = StringUtils.translate("litematica.gui.title.convert_schematic_format", inputFileName);
        this.useTitleHierarchy = false;

        this.widgetOutputType = new WidgetDropDownList<>(9, 57, -1, 22, 200, 10, SchematicType.KNOWN_TYPES, (entry) -> entry.getDisplayName());
        this.widgetOutputType.setIconProvider(new SchematicIconProvider());
    }

    @Override
    public String getBrowserContext()
    {
        return "schematic_convert";
    }

    @Override
    public File getDefaultDirectory()
    {
        return DataManager.getSchematicsBaseDirectory();
    }

    @Override
    public int getBrowserHeight()
    {
        return this.height - 100;
    }

    @Override
    protected void createCustomElements()
    {
        this.addWidget(this.widgetOutputType);
        int x = this.widgetOutputType.getX() + this.widgetOutputType.getWidth() + 4;
        this.createButton(x, this.widgetOutputType.getY() + 1, ButtonType.SAVE);
    }

    @Override
    protected void saveSchematic()
    {
        File dir = this.getListWidget().getCurrentDirectory();
        String fileName = this.getTextFieldText();

        if (dir.isDirectory() == false)
        {
            this.addMessage(MessageType.ERROR, "litematica.error.schematic_save.invalid_directory", dir.getAbsolutePath());
            return;
        }

        if (FileUtils.doesFilenameContainIllegalCharacters(fileName))
        {
            this.addMessage(MessageType.ERROR, "malilib.error.illegal_characters_in_file_name", fileName);
            return;
        }

        if (fileName.isEmpty())
        {
            this.addMessage(MessageType.ERROR, "litematica.error.schematic_save.invalid_schematic_name", fileName);
            return;
        }

        SchematicType<?> outputType = this.widgetOutputType.getSelectedEntry();

        if (outputType != null)
        {
            boolean override = GuiBase.isShiftDown();
            fileName = StringUtils.stripExtensionIfMatches(fileName, this.schematic.getType().getFileNameExtension());
            fileName += outputType.getFileNameExtension();

            ISchematic convertedSchematic = outputType.createSchematic(null);
            convertedSchematic.readFrom(this.schematic);

            if (convertedSchematic.writeToFile(dir, fileName, override))
            {
                this.addMessage(MessageType.SUCCESS, "litematica.message.schematic_convert.success", fileName);
            }
            else
            {
                this.addMessage(MessageType.ERROR, "litematica.error.schematic_convert.failed_to_save", fileName);
            }
        }
        else
        {
            this.addMessage(MessageType.ERROR, "litematica.error.schematic_convert.no_output_type_selected");
        }
    }

    public static class SchematicIconProvider implements IIconProvider<SchematicType<?>>
    {
        @Override
        public int getExpectedWidth()
        {
            return LitematicaGuiIcons.FILE_ICON_LITEMATIC.getWidth();
        }

        @Override
        public IGuiIcon getIconFor(SchematicType<?> entry)
        {
            return entry.getIcon();
        }
    }
}
