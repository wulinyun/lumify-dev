package io.lumify.palantir.mr.mappers;

import io.lumify.core.exception.LumifyException;
import io.lumify.core.model.properties.LumifyProperties;
import io.lumify.core.security.LumifyVisibility;
import io.lumify.core.util.LumifyLogger;
import io.lumify.core.util.LumifyLoggerFactory;
import io.lumify.palantir.model.PtPropertyAndValue;
import io.lumify.palantir.model.PtPropertyType;
import io.lumify.palantir.util.JGeometryWrapper;
import io.lumify.web.clientapi.model.VisibilityJson;
import org.apache.hadoop.io.LongWritable;
import org.securegraph.Metadata;
import org.securegraph.VertexBuilder;
import org.securegraph.Visibility;
import org.securegraph.type.GeoPoint;
import org.securegraph.type.GeoShape;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.awt.geom.Point2D;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PtPropertyAndValueMapper extends PalantirMapperBase<LongWritable, PtPropertyAndValue> {
    private static final LumifyLogger LOGGER = LumifyLoggerFactory.getLogger(PtPropertyAndValueMapper.class);
    public static final Pattern DURATION_PATTERN = Pattern.compile("(\\d+):(\\d\\d)");
    private Visibility visibility;
    private VisibilityJson visibilityJson;
    private DocumentBuilder dBuilder;
    private static Pattern VALUE_BODY_PATTERN = Pattern.compile("^<VALUE>(.*)</VALUE>$", Pattern.DOTALL);
    private static Pattern UNPARSED_VALUE_BODY_PATTERN = Pattern.compile("^<UNPARSED_VALUE>(.*)</UNPARSED_VALUE>$", Pattern.DOTALL);

    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
        super.setup(context);
        loadPropertyTypes(context);
        visibility = new LumifyVisibility("").getVisibility();
        visibilityJson = new VisibilityJson();
        try {
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            dBuilder = dbFactory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new LumifyException("Could not create document builder", e);
        }
    }

    @Override
    protected void safeMap(LongWritable key, PtPropertyAndValue ptPropertyAndValue, Context context) throws Exception {
        context.setStatus(key.toString());

        if (ptPropertyAndValue.isDeleted()) {
            return;
        }

        PtPropertyType propertyType = getPropertyType(ptPropertyAndValue.getType());
        if (propertyType == null) {
            throw new LumifyException("Could not find property type: " + ptPropertyAndValue.getType());
        }

        String objectVertexId = PtObjectMapper.getObjectVertexId(ptPropertyAndValue.getLinkObjectId());
        String propertyKey = getPropertyKey(ptPropertyAndValue);
        Metadata propertyMetadata = createMetadata(ptPropertyAndValue);
        Value value = cleanUpValueString(propertyType, ptPropertyAndValue.getValue());
        JGeometryWrapper gisData = ptPropertyAndValue.getGeometryGis();
        if (value == null && gisData == null) {
            return;
        }

        VertexBuilder v = prepareVertex(objectVertexId, visibility);
        if (value != null) {
            addValueToVertexBuilder(v, propertyType, propertyKey, propertyMetadata, value);
        }
        if (gisData != null) {
            addGisDataToVertexBuilder(v, propertyType, propertyKey, propertyMetadata, gisData);
        }
        v.save(getAuthorizations());
    }

    private Metadata createMetadata(PtPropertyAndValue ptPropertyAndValue) {
        Metadata propertyMetadata = new Metadata();
        LumifyProperties.CREATED_BY.setMetadata(propertyMetadata, PtUserMapper.getUserVertexId(ptPropertyAndValue.getCreatedBy()), visibility);
        LumifyProperties.CREATE_DATE.setMetadata(propertyMetadata, new Date(ptPropertyAndValue.getTimeCreated()), visibility);
        LumifyProperties.MODIFIED_BY.setMetadata(propertyMetadata, PtUserMapper.getUserVertexId(ptPropertyAndValue.getLastModifiedBy()), visibility);
        LumifyProperties.MODIFIED_DATE.setMetadata(propertyMetadata, new Date(ptPropertyAndValue.getLastModified()), visibility);
        LumifyProperties.VISIBILITY_JSON.setMetadata(propertyMetadata, visibilityJson, visibility);
        return propertyMetadata;
    }

    private void addValueToVertexBuilder(VertexBuilder v, PtPropertyType propertyType, String propertyKey, Metadata propertyMetadata, Value value) {
        if (value instanceof StringValue) {
            String innerKey = null;
            if (propertyType.isGisEnabled()) {
                innerKey = PtPropertyType.VALUE_SUFFIX;
            }
            String propertyName = getPropertyName(propertyType.getUri(), innerKey);
            String valueString = ((StringValue) value).getValue();
            Object valueObject;
            try {
                valueObject = toValue(propertyType, null, valueString);
            } catch (Exception ex) {
                LOGGER.error("Could not convert property value: %s (propertyType: %s)", value, propertyType.getConfigUri(), ex);
                valueObject = valueString;
                propertyName += PtPropertyType.ERROR_SUFFIX;
            }
            v.addPropertyValue(propertyKey, propertyName, valueObject, propertyMetadata, visibility);
        } else if (value instanceof MapValue) {
            MapValue values = (MapValue) value;
            for (Map.Entry<String, String> valueEntry : values.getValues().entrySet()) {
                String innerKey = valueEntry.getKey();
                String propertyName = getPropertyName(propertyType.getUri(), innerKey);
                String valueString = valueEntry.getValue();
                Object valueObject;
                try {
                    valueObject = toValue(propertyType, innerKey, valueString);
                } catch (Exception ex) {
                    LOGGER.error("Could not convert property value: %s (innerKey: %s, propertyType: %s): %s", value, innerKey, propertyType.getConfigUri(), ex.getMessage(), ex);
                    valueObject = valueString;
                    propertyName += PtPropertyType.ERROR_SUFFIX;
                }
                v.addPropertyValue(propertyKey, propertyName, valueObject, propertyMetadata, visibility);
            }
        } else {
            throw new RuntimeException("Unexpected value type: " + value.getClass().getName());
        }
    }

    private void addGisDataToVertexBuilder(VertexBuilder v, PtPropertyType propertyType, String propertyKey, Metadata propertyMetadata, JGeometryWrapper gisData) {
        String propertyName = getBaseIri() + propertyType.getUri() + PtPropertyType.GIS_SUFFIX;
        GeoShape geoShape = toGeoShape(gisData);
        v.addPropertyValue(propertyKey, propertyName, geoShape, propertyMetadata, visibility);
    }

    private GeoShape toGeoShape(JGeometryWrapper gisData) {
        switch (gisData.getType()) {
            case POINT:
                Point2D pt = gisData.getJavaPoint();
                return new GeoPoint(pt.getY(), pt.getX());
            default:
                throw new RuntimeException("Unhandled Geo-shape: " + gisData.getType());
        }
    }

    private Object toValue(PtPropertyType ptPropertyType, String innerKey, String value) {
        String propertyType;
        if (innerKey == null) {
            propertyType = ptPropertyType.getConfigTypeBase();
        } else {
            propertyType = ptPropertyType.getConfigComponentType(innerKey);
        }
        if (propertyType == null) {
            throw new RuntimeException("Could not find property type");
        }
        if (propertyType.equals("com.palantir.type.String")) {
            return value;
        }
        if (propertyType.equals("com.palantir.type.Enumeration")) {
            return value;
        }
        if (propertyType.equals("com.palantir.type.Number")) {
            return parseNumber(value);
        }
        if (propertyType.equals("com.palantir.type.Date")) {
            return parseDate(value);
        }
        if (propertyType.equals("com.palantir.type.Composite")) {
            throw new RuntimeException("Found composite property type without innerKey");
        }
        throw new RuntimeException("Unhandled property type");
    }

    private Object parseDate(String value) {
        // July 2, 2013 09:51:48 -04:00
        try {
            return new SimpleDateFormat("MMMM d, yyyy HHmmss Z").parse(value.replaceAll(":", ""));
        } catch (ParseException ex1) {
            try {
                return new SimpleDateFormat("MMMM d, yyyy").parse(value);
            } catch (ParseException ex2) {
                throw new RuntimeException("Could not parse date", ex2);
            }
        }
    }

    private Object parseNumber(String value) {
        try {
            if (value.contains(":")) {
                Matcher m = DURATION_PATTERN.matcher(value);
                if (m.matches()) {
                    String minutePart = m.group(1);
                    String secondPart = m.group(2);
                    return ((Integer.parseInt(minutePart) * 60) + Integer.parseInt(secondPart));
                } else {
                    throw new RuntimeException("Number has a ':' but does not match duration pattern");
                }
            }
            if (value.contains(".")) {
                return Double.parseDouble(value);
            }
            return Long.parseLong(value);
        } catch (Exception ex) {
            throw new RuntimeException("Could not parse number", ex);
        }
    }

    private String getPropertyName(String uri, String innerKey) {
        return getBaseIri() + uri + (innerKey == null ? "" : ("/" + innerKey));
    }

    private Value cleanUpValueString(PtPropertyType propertyType, String value) throws IOException, SAXException {
        if (value == null) {
            return null;
        }
        value = value.trim();
        if (value.equals("<null></null>")) {
            return null;
        }

        Matcher m = VALUE_BODY_PATTERN.matcher(value);
        if (m.matches()) {
            value = m.group(1).trim();
        }

        m = VALUE_BODY_PATTERN.matcher(value);
        if (m.matches()) {
            return new StringValue(m.group(1).trim());
        }

        m = UNPARSED_VALUE_BODY_PATTERN.matcher(value);
        if (m.matches()) {
            return new StringValue(m.group(1).trim());
        }

        Document d = dBuilder.parse(new ByteArrayInputStream(("<v>" + value + "</v>").getBytes()));
        Map<String, String> values = new HashMap<>();
        NodeList childNodes = d.getDocumentElement().getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            Node childNode = childNodes.item(i);
            if (childNode instanceof Element) {
                Element e = (Element) childNode;
                String tagName = e.getTagName();
                values.put(tagName, e.getTextContent());
            }
        }
        return new MapValue(values);
    }

    private String getPropertyKey(PtPropertyAndValue ptPropertyAndValue) {
        return ID_PREFIX + ptPropertyAndValue.getPropertyValueId();
    }

    private static abstract class Value {

    }

    private static class StringValue extends Value {
        private final String value;

        public StringValue(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        @Override
        public String toString() {
            return "StringValue{" +
                    "value='" + value + '\'' +
                    '}';
        }
    }

    private static class MapValue extends Value {
        private final Map<String, String> values;

        public MapValue(Map<String, String> values) {
            this.values = values;
        }

        public Map<String, String> getValues() {
            return values;
        }

        @Override
        public String toString() {
            return "MapValue{" +
                    "values=" + values +
                    '}';
        }
    }
}
