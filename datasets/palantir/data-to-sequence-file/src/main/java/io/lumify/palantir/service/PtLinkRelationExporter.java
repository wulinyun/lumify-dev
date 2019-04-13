package io.lumify.palantir.service;

import io.lumify.palantir.DataToSequenceFile;
import io.lumify.palantir.model.PtLinkRelation;
import org.apache.hadoop.io.SequenceFile;

import java.io.IOException;

public class PtLinkRelationExporter extends ExporterBase<PtLinkRelation> {
    private StringBuilder xml;

    public PtLinkRelationExporter() {
        super(PtLinkRelation.class);
    }

    @Override
    protected void beforeProcessRows(Exporter.ExporterSource exporterSource) {
        super.beforeProcessRows(exporterSource);

        xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" ?>\n");
        xml.append("<link_relations>\n");
    }

    @Override
    protected void processRow(Exporter.ExporterSource exporterSource, PtLinkRelation row, SequenceFile.Writer outputFile) throws IOException {
        super.processRow(exporterSource, row, outputFile);
        xml.append("  <link_relation_config>\n");
        xml.append("    <tableType1>").append(row.getTableTypeId1()).append("</tableType1>\n");
        xml.append("    <uri1>").append(row.getUri1()).append("</uri1>\n");
        xml.append("    <tableType2>").append(row.getTableTypeId2()).append("</tableType2>\n");
        xml.append("    <uri2>").append(row.getUri2()).append("</uri2>\n");
        xml.append("    <linkUri>").append(row.getLinkUri()).append("</linkUri>\n");
        xml.append("    <linkStatus>").append(row.getLinkStatus()).append("</linkStatus>\n");
        xml.append("    <hidden>").append(row.isHidden()).append("</hidden>\n");
        xml.append("  </link_relation_config>\n");
    }

    @Override
    protected void afterProcessRows(Exporter.ExporterSource exporterSource) {
        super.afterProcessRows(exporterSource);
        xml.append("</link_relations>\n");

        writeOntologyXmlFile(exporterSource, "pt_link_relation", xml.toString());
    }

    @Override
    protected String getSql() {
        return "SELECT * FROM {namespace}.PT_LINK_RELATION ORDER BY id";
    }
}
