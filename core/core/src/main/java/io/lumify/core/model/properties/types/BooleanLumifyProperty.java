package io.lumify.core.model.properties.types;

import org.securegraph.Element;

public class BooleanLumifyProperty extends IdentityLumifyProperty<Boolean> {
    public BooleanLumifyProperty(String key) {
        super(key);
    }

    public boolean getPropertyValue(Element element, boolean defaultValue) {
        Boolean nullable = getPropertyValue(element);
        if (nullable == null) {
            return defaultValue;
        }
        return nullable;
    }
}
