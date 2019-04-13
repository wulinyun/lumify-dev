package io.lumify.palantir.service;

import io.lumify.palantir.model.PtObject;

public class PtObjectExporter extends ExporterBase<PtObject> {
    public PtObjectExporter() {
        super(PtObject.class);
    }

    @Override
    protected String getSql() {
        return "SELECT * FROM {namespace}.PT_OBJECT ORDER BY object_id";
    }
}
