/*
 * Copyright 2015-2018 Igor Maznitsa.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mindolph.mindmap.extension.exporters;

import com.igormaznitsa.mindmap.model.MindMap;
import com.mindolph.base.constant.StrokeType;
import com.mindolph.base.graphic.Graphics;
import com.mindolph.base.util.GeometryConvertUtils;
import com.mindolph.mfx.util.AwtConvertUtils;
import com.mindolph.mfx.util.FontUtils;
import com.mindolph.mfx.util.FxImageUtils;
import com.mindolph.mindmap.I18n;
import com.mindolph.mindmap.MindMapConfig;
import com.mindolph.mindmap.MindMapContext;
import com.mindolph.mindmap.extension.api.BaseExportExtension;
import com.mindolph.mindmap.extension.api.ExtensionContext;
import com.mindolph.mindmap.gfx.MindMapCanvas;
import com.mindolph.mindmap.icon.IconID;
import com.mindolph.mindmap.icon.ImageIconServiceProvider;
import com.mindolph.mindmap.model.TopicNode;
import com.mindolph.mindmap.theme.MindMapTheme;
import com.mindolph.mindmap.util.DialogUtils;
import com.mindolph.mindmap.util.MindMapUtils;
import javafx.geometry.Dimension2D;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.image.Image;
import javafx.scene.input.ClipboardContent;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.scene.shape.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.*;

import static com.mindolph.mindmap.MindMapCalculateHelper.calculateSizeOfMapInPixels;

public class SVGImageExporter extends BaseExportExtension {

    protected static final String FONT_CLASS_NAME = "mindMapTitleFont";
    private static final Map<String, String[]> LOCAL_FONT_MAP = new HashMap<>() {
        {
            put("dialog", new String[]{"sans-serif", "SansSerif"});
            put("dialoginput", new String[]{"monospace", "Monospace"});
            put("monospaced", new String[]{"monospace", "Monospace"});
            put("serif", new String[]{"serif", "Serif"});
            put("sansserif", new String[]{"sans-serif", "SansSerif"});
            put("symbol", new String[]{"'WingDings'", "WingDings"});
        }
    };
    private static final Logger LOGGER = LoggerFactory.getLogger(SVGImageExporter.class);
    private static final Image ICON = ImageIconServiceProvider.getInstance().getIconForId(IconID.POPUP_EXPORT_SVG);
    private static final String SVG_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<!-- Generated by Mindolph SVG exporter https://github.com/mindolph/Mindolph -->\n<svg version=\"1.1\" baseProfile=\"tiny\" id=\"svg-root\" width=\"%d%%\" height=\"%d%%\" viewBox=\"0 0 %s %s\" xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\">";
    private static final String NEXT_LINE = "\n";
    private static final DecimalFormat DOUBLE;
    private boolean flagExpandAllNodes = false;
    private boolean flagDrawBackground = true;

    static {
        DOUBLE = new DecimalFormat("#.###", DecimalFormatSymbols.getInstance(Locale.US));
    }

    private static String dbl2str(double value) {
        return DOUBLE.format(value);
    }

    private static String fontFamilyToSVG(Font font) {
        String fontFamilyStr = font.getFamily();
        String[] logicalFontFamily = LOCAL_FONT_MAP.get(font.getName().toLowerCase());
        if (logicalFontFamily != null) {
            fontFamilyStr = logicalFontFamily[0];
        }
        else {
            fontFamilyStr = String.format("'%s'", fontFamilyStr);
        }
        return fontFamilyStr;
    }

    private static String font2style(Font font) {
        StringBuilder result = new StringBuilder();
        FontWeight weight = FontUtils.fontWeight(font.getStyle());
        FontPosture posture = FontUtils.fontPosture(font.getStyle());
        String fontStyle = posture == FontPosture.ITALIC ? "italic" : "normal";
        String fontWeight = weight == FontWeight.BOLD ? "bold" : "normal";
        String fontSize = DOUBLE.format(font.getSize()) + "px";
        String fontFamily = fontFamilyToSVG(font);

        result.append("font-family: ").append(fontFamily).append(';').append(NEXT_LINE);
        result.append("font-size: ").append(fontSize).append(';').append(NEXT_LINE);
        result.append("font-style: ").append(fontStyle).append(';').append(NEXT_LINE);
        result.append("font-weight: ").append(fontWeight).append(';').append(NEXT_LINE);

        return result.toString();
    }

    @Override
    public List<String> getOptions() {
        return Arrays.asList(I18n.getIns().getString("SvgExporter.optionUnfoldAll"), I18n.getIns().getString("SvgExporter.optionDrawBackground"));
    }

    @Override
    public List<Boolean> getDefaults() {
        return Arrays.asList(false, true);
    }


    private String makeContent(ExtensionContext context, List<Boolean> options) throws IOException {
        if (options != null) {
            this.flagExpandAllNodes = options.get(0);
            this.flagDrawBackground = options.get(1);
        }
        MindMap<TopicNode> workMap = new MindMap<>(context.getModel());
        workMap.resetPayload();

        if (this.flagExpandAllNodes) {
            workMap.getRoot().removeCollapseAttr();
        }

        MindMapConfig newConfig = new MindMapConfig(context.getMindMapConfig());
        MindMapTheme theme = newConfig.getTheme();
        String[] mappedFont = LOCAL_FONT_MAP.get(theme.getTopicFont().getFamily().toLowerCase(Locale.ENGLISH));
        if (mappedFont != null) {
            FontWeight weight = FontUtils.fontWeight(theme.getTopicFont().getStyle());
            FontPosture posture = FontUtils.fontPosture(theme.getTopicFont().getStyle());
            Font adaptedFont = Font.font(mappedFont[1], weight, posture, theme.getTopicFont().getSize());
            theme.setTopicFont(adaptedFont);
        }

        theme.setDrawBackground(this.flagDrawBackground);

        MindMapContext mindMapContext = new MindMapContext();
        Dimension2D blockSize = calculateSizeOfMapInPixels(workMap, newConfig, mindMapContext, flagExpandAllNodes);
        if (blockSize == null) {
            return SVG_HEADER + "</svg>";
        }

        StringBuilder buffer = new StringBuilder(16384);
        buffer.append(String.format(SVG_HEADER, 100, 100, dbl2str(blockSize.getWidth()), dbl2str(blockSize.getHeight()))).append(NEXT_LINE);
        buffer.append(prepareStylePart(buffer, newConfig)).append(NEXT_LINE);

        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        Graphics gfx = new SVGMMGraphics(buffer, g);

        gfx.setClip(0, 0, Math.round(blockSize.getWidth()), Math.round(blockSize.getHeight()));
        try {
            MindMapCanvas mindMapCanvas = new MindMapCanvas(gfx, newConfig, mindMapContext);
            mindMapCanvas.layoutFullDiagramWithCenteringToPaper(workMap, GeometryConvertUtils.dimension2DToBounds(blockSize));
            mindMapCanvas.drawOnGraphicsForConfiguration(workMap, false, null);
        } finally {
            gfx.dispose();
        }
        buffer.append("</svg>");

        return buffer.toString();
    }

    @Override
    public void doExportToClipboard(ExtensionContext context, List<Boolean> options) throws IOException {
        String text = makeContent(context, options);
        ClipboardContent cc = new ClipboardContent();
        cc.putString(text);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
    }

    @Override
    public void doExport(ExtensionContext context, List<Boolean> options, String exportFileName, OutputStream out) throws IOException {
        String text = makeContent(context, options);

        File fileToSave = null;
        if (out == null) {
            fileToSave = DialogUtils.selectFileToSaveForFileFilter(
                    I18n.getIns().getString("SvgExporter.saveDialogTitle"), null,
                    ".svg",
                    I18n.getIns().getString("SvgExporter.filterDescription"),
                    exportFileName);
            fileToSave = MindMapUtils.checkFileAndExtension(fileToSave, ".svg");
            out = fileToSave == null ? null : new BufferedOutputStream(new FileOutputStream(fileToSave, false));
        }
        if (out != null) {
            try {
                IOUtils.write(text, out, "UTF-8");
                Files.setLastModifiedTime(fileToSave.toPath(), FileTime.fromMillis(System.currentTimeMillis()));
            } finally {
                if (fileToSave != null) {
                    IOUtils.closeQuietly(out);
                }
            }
        }
    }


    private String prepareStylePart(StringBuilder buffer, MindMapConfig config) {
        return "<style>%s.%s {%s%s}%s</style>".formatted(NEXT_LINE, FONT_CLASS_NAME, NEXT_LINE, font2style(config.getTheme().getNoteFont()), NEXT_LINE);
    }

    @Override

    public String getName(ExtensionContext context, TopicNode actionTopic) {
        return I18n.getIns().getString("SvgExporter.exporterName");
    }

    @Override
    public String getReference(ExtensionContext context, TopicNode actionTopic) {
        return I18n.getIns().getString("SvgExporter.exporterReference");
    }

    @Override
    public Image getIcon(ExtensionContext panel, TopicNode actionTopic) {
        return ICON;
    }

    @Override
    public int getOrder() {
        return 5;
    }

    public static class SvgClip implements Transferable {

        private static final DataFlavor SVG_FLAVOR = new DataFlavor("image/svg+xml; class=java.io.InputStream", "Scalable Vector Graphic");
        final private String svgContent;

        private final DataFlavor[] supportedFlavors;

        public SvgClip(String str) {
            this.supportedFlavors = new DataFlavor[]{
                    SVG_FLAVOR,};

            this.svgContent = str;
            SystemFlavorMap systemFlavorMap = (SystemFlavorMap) SystemFlavorMap.getDefaultFlavorMap();
            DataFlavor dataFlavor = SVG_FLAVOR;
            systemFlavorMap.addUnencodedNativeForFlavor(dataFlavor, "image/svg+xml");
        }


        static DataFlavor getSVGFlavor() {
            return SvgClip.SVG_FLAVOR;
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            for (DataFlavor supported : this.supportedFlavors) {
                if (flavor.equals(supported)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return this.supportedFlavors;
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException, IOException {
            if (isDataFlavorSupported(flavor) && flavor.equals(SVG_FLAVOR)) {
                return new ByteArrayInputStream(this.svgContent.getBytes(StandardCharsets.UTF_8));
            }
            throw new UnsupportedFlavorException(flavor);
        }

        public void lostOwnership(Clipboard clipboard, Transferable tr) {
        }
    }

    private static final class SVGMMGraphics implements Graphics {

        private static final DecimalFormat OPACITY = new DecimalFormat("#.##");
        private final StringBuilder buffer;
        private final Graphics2D context;
        private double translateX;
        private double translateY;
        private float strokeWidth = 1.0f;
        private StrokeType strokeType = StrokeType.SOLID;

        private SVGMMGraphics(StringBuilder buffer, Graphics2D context) {
            this.buffer = buffer;
            this.context = (Graphics2D) context.create();
        }


        private static String svgRgb(Color color) {
            return String.format("rgb(%s,%s,%s)", color.getRed() * 255, color.getGreen() * 255, color.getBlue() * 255);
        }

        private void printFillOpacity(Color color) {
            if (color.getOpacity() < 1) {
                this.buffer.append(" fill-opacity=\"").append(OPACITY.format(color.getOpacity())).append("\" ");
            }
        }

        private void printFontData() {
            this.buffer.append("class=\"" + FONT_CLASS_NAME + '\"');
        }

        private void printStrokeData(Color color) {
            this.buffer.append(" stroke=\"").append(svgRgb(color))
                    .append("\" stroke-width=\"").append(dbl2str(this.strokeWidth)).append("\"");

            switch (this.strokeType) {
                case SOLID:
                    this.buffer.append(" stroke-linecap=\"round\"");
                    break;
                case DASHES:
                    this.buffer.append(" stroke-linecap=\"butt\" stroke-dasharray=\"").append(dbl2str(this.strokeWidth * 3.0f)).append(',').append(dbl2str(this.strokeWidth)).append("\"");
                    break;
                case DOTS:
                    this.buffer.append(" stroke-linecap=\"butt\" stroke-dasharray=\"").append(dbl2str(this.strokeWidth)).append(',').append(dbl2str(this.strokeWidth * 2.0f)).append("\"");
                    break;
            }
        }

        @Override
        public double getFontMaxAscent() {
            return this.context.getFontMetrics().getMaxAscent();
        }

        @Override
        public Rectangle2D getStringBounds(String str) {
            if (str.isEmpty()) {
                return AwtConvertUtils.awtRectangle2D2Rectangle2D(this.context.getFontMetrics().getStringBounds("", this.context));
            }
            else {
                TextLayout textLayout = new TextLayout(str, this.context.getFont(), this.context.getFontRenderContext());
                return new Rectangle2D(0, -textLayout.getAscent(), textLayout.getAdvance(), textLayout.getAscent() + textLayout.getDescent() + textLayout.getLeading());
            }
        }

        @Override
        public void setClip(double x, double y, double w, double h) {
            this.context.setClip((int) x, (int) y, (int) w, (int) h);
        }

        @Override
        public Graphics copy() {
            SVGMMGraphics result = new SVGMMGraphics(this.buffer, this.context);
            result.translateX = this.translateX;
            result.translateY = this.translateY;
            result.strokeType = this.strokeType;
            result.strokeWidth = this.strokeWidth;
            return result;
        }

        @Override
        public void dispose() {
            this.context.dispose();
        }

        @Override
        public void translate(double x, double y) {
            this.translateX += x;
            this.translateY += y;
            this.context.translate(x, y);
        }

        @Override
        public void setClipBounds(Rectangle2D clipBounds) {

        }

        @Override
        public Rectangle2D getClipBounds() {
            return AwtConvertUtils.awtRectangle2D2Rectangle2D(this.context.getClipBounds());
        }

        @Override
        public void setStroke(float width, StrokeType type) {
            if (type != this.strokeType || Float.compare(this.strokeWidth, width) != 0) {
                this.strokeType = type;
                this.strokeWidth = width;
                if (Float.compare(this.strokeWidth, width) != 0) {
                    this.strokeType = type;
                    this.strokeWidth = width;

                    Stroke stroke;

                    switch (type) {
                        case SOLID:
                            stroke = new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_MITER);
                            break;
                        case DASHES:
                            stroke = new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0f, new float[]{width * 2.0f, width}, 0.0f);
                            break;
                        case DOTS:
                            stroke = new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{width, width * 2.0f}, 0.0f);
                            break;
                        default:
                            throw new Error("Unexpected stroke type : " + type);
                    }
                    this.context.setStroke(stroke);
                }
            }
        }

        @Override
        public void drawLine(Point2D start, Point2D end, Color color) {
            this.drawLine(start.getX(), start.getY(), end.getX(), end.getY(), color);
        }

        @Override
        public void drawLine(double startX, double startY, double endX, double endY, Color color) {
            this.buffer.append("<line x1=\"").append(dbl2str(startX + this.translateX))
                    .append("\" y1=\"").append(dbl2str(startY + this.translateY))
                    .append("\" x2=\"").append(dbl2str(endX + this.translateX))
                    .append("\" y2=\"").append(dbl2str(endY + this.translateY)).append("\" ");
            if (color != null) {
                printStrokeData(color);
                printFillOpacity(color);
            }
            this.buffer.append("/>").append(NEXT_LINE);
        }

        @Override
        public void drawString(String text, double x, double y, Color color) {
            this.buffer.append("<text x=\"").append(dbl2str(this.translateX + x)).append("\" y=\"").append(dbl2str(this.translateY + y)).append('\"');
            if (color != null) {
                this.buffer.append(" fill=\"").append(svgRgb(color)).append("\"");
                printFillOpacity(color);
            }
            this.buffer.append(' ');
            printFontData();
            this.buffer.append('>').append(StringEscapeUtils.escapeXml(text)).append("</text>").append(NEXT_LINE);
        }

        @Override
        public void drawRect(double x, double y, double width, double height, Color border, Color fill) {
            this.buffer.append("<rect x=\"").append(dbl2str(this.translateX + x))
                    .append("\" y=\"").append(dbl2str(translateY + y))
                    .append("\" width=\"").append(dbl2str(width))
                    .append("\" height=\"").append(dbl2str(height))
                    .append("\" ");
            if (border != null) {
                printStrokeData(border);
            }

            if (fill == null) {
                this.buffer.append(" fill=\"none\"");
            }
            else {
                this.buffer.append(" fill=\"").append(svgRgb(fill)).append("\"");
                printFillOpacity(fill);
            }

            this.buffer.append("/>").append(NEXT_LINE);
        }

        @Override
        public void drawRect(Rectangle2D rect, Color border, Color fill) {
            this.drawRect(rect.getMinX(), rect.getMinY(), rect.getWidth(), rect.getHeight(), border, fill);
        }

        @Override
        public void draw(Shape shape, Color border, Color fill) {
            if (shape instanceof Rectangle) {
                Rectangle rect = (Rectangle) shape;
                this.buffer.append("<rect x=\"").append(dbl2str(this.translateX + rect.getX()))
                        .append("\" y=\"").append(dbl2str(translateY + rect.getY()))
                        .append("\" width=\"").append(dbl2str(rect.getWidth()))
                        .append("\" height=\"").append(dbl2str(rect.getHeight()))
                        .append("\" rx=\"").append(dbl2str(rect.getArcWidth() / 2.0d))
                        .append("\" ry=\"").append(dbl2str(rect.getArcHeight() / 2.0d))
                        .append("\" ");

            }
            else if (shape instanceof Path path) {
                double[] data = new double[6];
                this.buffer.append("<path d=\"");
                boolean nofirst = false;
                for (PathElement e : path.getElements()) {
                    if (nofirst) {
                        this.buffer.append(' ');
                    }
                    if (e instanceof MoveTo) {
                        this.buffer.append("M ").append(dbl2str(this.translateX + ((MoveTo) e).getX())).append(' ').append(dbl2str(this.translateY + ((MoveTo) e).getY()));
                    }
                    else if (e instanceof LineTo) {
                        this.buffer.append("L ").append(dbl2str(this.translateX + ((LineTo) e).getX())).append(' ').append(dbl2str(this.translateY + ((LineTo) e).getY()));
                    }
                    else if (e instanceof CubicCurveTo) {
                        // todo the order of the control points should be tested.
                        this.buffer.append("C ")
                                .append(dbl2str(this.translateX + ((CubicCurveTo) e).getX())).append(' ').append(dbl2str(this.translateY + ((CubicCurveTo) e).getY())).append(',')
                                .append(dbl2str(this.translateX + ((CubicCurveTo) e).getControlX1())).append(' ').append(dbl2str(this.translateY + ((CubicCurveTo) e).getControlY1())).append(',')
                                .append(dbl2str(this.translateX + ((CubicCurveTo) e).getControlX2())).append(' ').append(dbl2str(this.translateY + ((CubicCurveTo) e).getControlY2()));
                    }
                    else if (e instanceof QuadCurveTo) {
                        // todo the order of the control points should be tested.
                        this.buffer.append("Q ")
                                .append(dbl2str(this.translateX + ((QuadCurveTo) e).getX())).append(' ').append(dbl2str(this.translateY + ((QuadCurveTo) e).getY())).append(',')
                                .append(dbl2str(this.translateX + ((QuadCurveTo) e).getControlX())).append(' ').append(dbl2str(this.translateY + ((QuadCurveTo) e).getControlY()));
                    }
                    else if (e instanceof ClosePath) {
                        this.buffer.append("Z");
                    }
                    else {
                        LOGGER.warn("Unexpected path segment type");
                    }
                    nofirst = true;
                }
                this.buffer.append("\" ");
            }
            else {
                LOGGER.warn("Detected unexpected shape : " + shape.getClass().getName());
            }

            if (border != null) {
                printStrokeData(border);
            }

            if (fill == null) {
                this.buffer.append(" fill=\"none\"");
            }
            else {
                this.buffer.append(" fill=\"").append(svgRgb(fill)).append("\"");
                printFillOpacity(fill);
            }

            this.buffer.append("/>").append(NEXT_LINE);
        }

        @Override
        public void drawCurve(double startX, double startY, double endX, double endY, Color color) {
            this.buffer.append("<path d=\"M").append(dbl2str(startX + this.translateX)).append(',').append(startY + this.translateY)
                    .append(" C").append(dbl2str(startX))
                    .append(',').append(dbl2str(endY))
                    .append(' ').append(dbl2str(startX))
                    .append(',').append(dbl2str(endY))
                    .append(' ').append(dbl2str(endX))
                    .append(',').append(dbl2str(endY))
                    .append("\" fill=\"none\"");

            if (color != null) {
                printStrokeData(color);
            }
            this.buffer.append(" />").append(NEXT_LINE);
        }

        @Override
        public void drawBezier(double startX, double startY, double endX, double endY, Color color) {
            String s = """
                    <path d="M %s %s Q %s %s, %s %s T %s %s" 
                    """;
            double c1x = startX + (endX - startX) / 2;
            double c1y = startY;
            double c2x = startX + (endX - startX) / 2;
            double c2y = startY + (endY - startY) / 2;
            String formatted = s.formatted(
                    dbl2str(startX), dbl2str(startY),
                    dbl2str(c1x), dbl2str(c1y),
                    dbl2str(c2x), dbl2str(c2y),
                    dbl2str(endX), dbl2str(endY)
            );
            this.buffer.append(formatted);
            if (color != null) {
                printStrokeData(color);
            }
            this.buffer.append(" fill=\"none\"/>").append(NEXT_LINE);
        }

        @Override
        public void drawOval(double x, double y, double w, double h, Color border, Color fill) {
            double rx = w / 2.0d;
            double ry = h / 2.0d;
            double cx = x + this.translateX + rx;
            double cy = y + this.translateY + ry;

            this.buffer.append("<ellipse cx=\"").append(dbl2str(cx))
                    .append("\" cy=\"").append(dbl2str(cy))
                    .append("\" rx=\"").append(dbl2str(rx))
                    .append("\" ry=\"").append(dbl2str(ry))
                    .append("\" ");

            if (border != null) {
                printStrokeData(border);
            }

            if (fill == null) {
                this.buffer.append(" fill=\"none\"");
            }
            else {
                this.buffer.append(" fill=\"").append(svgRgb(fill)).append("\"");
                printFillOpacity(fill);
            }

            this.buffer.append("/>").append(NEXT_LINE);
        }

        @Override
        public void drawImage(Image image, double x, double y) {
            this.drawImage(image, x, y, image.getWidth(), image.getHeight());
        }

        @Override
        public void drawImage(Image image, double x, double y, double width, double height) {
            if (image != null) {
                try {
                    String s = FxImageUtils.imageToBase64(image);
                    this.buffer.append("<image width=\"").append(width).append("\" height=\"").append(height).append("\" x=\"").append(dbl2str(this.translateX + x)).append("\" y=\"").append(dbl2str(this.translateY + y)).append("\" xlink:href=\"data:image/png;base64,");
                    this.buffer.append(s);
                    this.buffer.append("\"/>").append(NEXT_LINE);
                } catch (Exception ex) {
                    LOGGER.error("Can't place image for error", ex);
                }
            }
        }

        @Override
        public void setFont(Font font) {
            this.context.setFont(FontUtils.fxFontToAwtFont(font));
        }

        @Override
        public void setOpacity(double opacity) {
            // TODO
        }
    }
}
