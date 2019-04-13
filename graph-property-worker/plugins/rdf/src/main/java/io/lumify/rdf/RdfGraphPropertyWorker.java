package io.lumify.rdf;

import com.hp.hpl.jena.datatypes.RDFDatatype;
import com.hp.hpl.jena.datatypes.xsd.XSDDatatype;
import com.hp.hpl.jena.datatypes.xsd.XSDDateTime;
import com.hp.hpl.jena.rdf.model.*;
import io.lumify.core.exception.LumifyException;
import io.lumify.core.ingest.graphProperty.GraphPropertyWorkData;
import io.lumify.core.ingest.graphProperty.GraphPropertyWorker;
import io.lumify.core.ingest.graphProperty.GraphPropertyWorkerPrepareData;
import io.lumify.core.model.properties.LumifyProperties;
import io.lumify.core.util.LumifyLogger;
import io.lumify.core.util.LumifyLoggerFactory;
import org.apache.commons.codec.digest.DigestUtils;
import org.json.JSONObject;
import org.securegraph.*;
import org.securegraph.Property;
import org.securegraph.property.StreamingPropertyValue;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

public class RdfGraphPropertyWorker extends GraphPropertyWorker {
    private static final LumifyLogger LOGGER = LumifyLoggerFactory.getLogger(RdfGraphPropertyWorker.class);
    public static final String RDF_TYPE_URI = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private String hasEntityIri;
    private String rdfConceptTypeIri;

    @Override
    public void prepare(GraphPropertyWorkerPrepareData workerPrepareData) throws Exception {
        super.prepare(workerPrepareData);

        hasEntityIri = getOntologyRepository().getRequiredRelationshipIRIByIntent("artifactHasEntity");

        // rdfConceptTypeIri is not required because the concept type could have been set by some other means.
        rdfConceptTypeIri = getOntologyRepository().getConceptIRIByIntent("rdf");
    }

    @Override
    public void execute(InputStream in, GraphPropertyWorkData data) throws Exception {
        importRdf(getGraph(), in, null, data, data.getVisibility(), getAuthorizations());
    }

    @Override
    public boolean isHandled(Element element, Property property) {
        if (property == null) {
            return false;
        }

        String mimeType = LumifyProperties.MIME_TYPE.getPropertyValue(element);
        if (!RdfOntology.MIME_TYPE_TEXT_RDF.equals(mimeType)) {
            return false;
        }

        if (!LumifyProperties.RAW.getPropertyName().equals(property.getName())) {
            return false;
        }

        return true;
    }

    public void importRdf(Graph graph, File inputFile, GraphPropertyWorkData data, Visibility visibility, Authorizations authorizations) throws IOException {
        try (InputStream in = new FileInputStream(inputFile)) {
            File baseDir = inputFile.getParentFile();
            importRdf(graph, in, baseDir, data, visibility, authorizations);
        }
    }

    private void importRdf(Graph graph, InputStream in, File baseDir, GraphPropertyWorkData data, Visibility visibility, Authorizations authorizations) {
        if (rdfConceptTypeIri != null && data != null) {
            LumifyProperties.CONCEPT_TYPE.setProperty(data.getElement(), rdfConceptTypeIri, data.createPropertyMetadata(), visibility, getAuthorizations());
        }

        Model model = ModelFactory.createDefaultModel();
        model.read(in, null);

        Results results = new Results();
        importRdfModel(results, graph, model, baseDir, data, visibility, authorizations);

        graph.flush();

        LOGGER.debug("pushing vertices from RDF import on to work queue");
        for (Vertex vertex : results.getVertices()) {
            getWorkQueueRepository().pushElement(vertex);
            for (Property prop : vertex.getProperties()) {
                getWorkQueueRepository().pushGraphPropertyQueue(vertex, prop);
            }
        }

        LOGGER.debug("pushing edges from RDF import on to work queue");
        for (Edge edge : results.getEdges()) {
            getWorkQueueRepository().pushElement(edge);
            for (Property prop : edge.getProperties()) {
                getWorkQueueRepository().pushGraphPropertyQueue(edge, prop);
            }
        }
    }

    private void importRdfModel(Results results, Graph graph, Model model, File baseDir, GraphPropertyWorkData data, Visibility visibility, Authorizations authorizations) {
        ResIterator subjects = model.listSubjects();
        while (subjects.hasNext()) {
            Resource subject = subjects.next();
            importSubject(results, graph, subject, baseDir, data, visibility, authorizations);
        }
    }

    private void importSubject(Results results, Graph graph, Resource subject, File baseDir, GraphPropertyWorkData data, Visibility visibility, Authorizations authorizations) {
        LOGGER.info("importSubject: %s", subject.toString());
        String graphVertexId = getGraphVertexId(subject);
        VertexBuilder vertexBuilder = graph.prepareVertex(graphVertexId, visibility);
        if (data != null) {
            data.setVisibilityJsonOnElement(vertexBuilder);
        }

        StmtIterator statements = subject.listProperties();
        while (statements.hasNext()) {
            Statement statement = statements.next();
            RDFNode obj = statement.getObject();
            if (obj instanceof Resource) {
                if (isConceptTypeResource(statement)) {
                    String value = statement.getResource().toString();
                    Metadata metadata = null;
                    if (data != null) {
                        metadata = data.createPropertyMetadata();
                    }
                    LumifyProperties.CONCEPT_TYPE.setProperty(vertexBuilder, value, metadata, visibility);
                }
            } else if (obj instanceof Literal) {
                LOGGER.info("set property on %s to %s", subject.toString(), statement.toString());
                importLiteral(vertexBuilder, statement, baseDir, data, visibility);
            } else {
                throw new LumifyException("Unhandled object type: " + obj.getClass().getName());
            }
        }

        Vertex v = vertexBuilder.save(authorizations);
        results.addVertex(v);

        if (data != null) {
            String edgeId = data.getElement().getId() + "_hasEntity_" + v.getId();
            EdgeBuilder e = graph.prepareEdge(edgeId, (Vertex) data.getElement(), v, hasEntityIri, visibility);
            data.setVisibilityJsonOnElement(e);
            results.addEdge(e.save(authorizations));

            addVertexToWorkspaceIfNeeded(data, v);
        }

        statements = subject.listProperties();
        while (statements.hasNext()) {
            Statement statement = statements.next();
            RDFNode obj = statement.getObject();
            if (obj instanceof Resource) {
                if (isConceptTypeResource(statement)) {
                    continue;
                }
                importResource(results, graph, v, statement, data, visibility, authorizations);
            }
        }
    }

    private boolean isConceptTypeResource(Statement statement) {
        String label = statement.getPredicate().toString();
        return label.equals(RDF_TYPE_URI);
    }

    private void importLiteral(VertexBuilder v, Statement statement, File baseDir, GraphPropertyWorkData data, Visibility visibility) {
        String propertyName = statement.getPredicate().toString();

        RDFDatatype datatype = statement.getLiteral().getDatatype();
        Object literalValue = statement.getLiteral().getValue();
        Object value = literalValue;
        String propertyKey = RdfGraphPropertyWorker.class.getName() + "_" + hashValue(value.toString());
        if (datatype == null || XSDDatatype.XSDstring.equals(datatype)) {
            String valueString = statement.getLiteral().toString();
            if (valueString.startsWith("streamingValue:")) {
                value = convertStreamingValueJsonToValueObject(baseDir, valueString);
            }
        } else if (literalValue instanceof XSDDateTime) {
            XSDDateTime xsdDateTime = (XSDDateTime) literalValue;
            value = xsdDateTime.asCalendar().getTime();
        } else {
            throw new LumifyException("unsupported XSDDatatype: " + datatype.getURI());
        }

        Metadata metadata = null;
        if (data != null) {
            metadata = data.createPropertyMetadata();
        }
        v.addPropertyValue(propertyKey, propertyName, value, metadata, visibility);
    }

    private String hashValue(String valueString) {
        // we need a unique value but it's a bit silly to store a whole md5 hash
        return DigestUtils.md5Hex(valueString).substring(0, 10);
    }

    private Object convertStreamingValueJsonToValueObject(File baseDir, String valueString) {
        JSONObject streamingValueJson = new JSONObject(valueString.substring("streamingValue:".length()));
        String fileName = streamingValueJson.getString("fileName");
        if (baseDir == null) {
            throw new LumifyException("Could not import streamingValue. No baseDir specified.");
        }
        File file = new File(baseDir, fileName);
        InputStream in;
        try {
            if (!file.exists()) {
                throw new LumifyException("File " + file.getAbsolutePath() + " does not exist.");
            }
            in = new FileInputStream(file);
        } catch (FileNotFoundException ex) {
            throw new LumifyException("File " + file.getAbsolutePath() + " does not exist.");
        }
        StreamingPropertyValue spv = new StreamingPropertyValue(in, byte[].class);
        spv.searchIndex(false);
        spv.store(true);
        return spv;
    }

    private void importResource(Results results, Graph graph, Vertex outVertex, Statement statement, GraphPropertyWorkData data, Visibility visibility, Authorizations authorizations) {
        String label = statement.getPredicate().toString();
        String vertexId = getGraphVertexId(statement.getResource());
        VertexBuilder inVertexBuilder = graph.prepareVertex(vertexId, visibility);
        if (data != null) {
            data.setVisibilityJsonOnElement(inVertexBuilder);
        }
        Vertex inVertex = inVertexBuilder.save(authorizations);
        results.addVertex(inVertex);
        if (data != null) {
            addVertexToWorkspaceIfNeeded(data, inVertex);
        }
        String edgeId = outVertex.getId() + "_" + label + "_" + inVertex.getId();

        EdgeBuilder e = graph.prepareEdge(edgeId, outVertex, inVertex, label, visibility);
        if (data != null) {
            data.setVisibilityJsonOnElement(e);
        }
        results.addEdge(e.save(authorizations));
        LOGGER.info("importResource: %s = %s", label, vertexId);
    }

    /**
     * RDF requires that all subjects are URIs. To create more portable ids,
     * this method will look for the last '#' character and return everything after that.
     */
    private String getGraphVertexId(Resource subject) {
        String subjectUri = subject.getURI();
        checkNotNull(subjectUri, "could not get uri of subject: " + subject);
        int lastPound = subjectUri.lastIndexOf('#');
        checkArgument(lastPound >= 1, "Could not find '#' in subject uri: " + subjectUri);
        return subjectUri.substring(lastPound + 1);
    }

    private static class Results {
        private final List<Vertex> vertices = new ArrayList<>();
        private final List<Edge> edges = new ArrayList<>();

        public void addEdge(Edge edge) {
            this.edges.add(edge);
        }

        public void addVertex(Vertex vertex) {
            this.vertices.add(vertex);
        }

        public Iterable<Edge> getEdges() {
            return edges;
        }

        public Iterable<Vertex> getVertices() {
            return vertices;
        }
    }
}
