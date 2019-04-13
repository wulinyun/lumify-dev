package io.lumify.opennlpme;

import io.lumify.core.ingest.graphProperty.GraphPropertyWorkData;
import io.lumify.core.ingest.graphProperty.GraphPropertyWorker;
import io.lumify.core.ingest.graphProperty.GraphPropertyWorkerPrepareData;
import io.lumify.core.model.properties.LumifyProperties;
import io.lumify.core.model.termMention.TermMentionBuilder;
import io.lumify.core.util.LumifyLogger;
import io.lumify.core.util.LumifyLoggerFactory;
import io.lumify.web.clientapi.model.VisibilityJson;
import opennlp.tools.namefind.NameFinderME;
import opennlp.tools.namefind.TokenNameFinder;
import opennlp.tools.namefind.TokenNameFinderModel;
import opennlp.tools.tokenize.Tokenizer;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import opennlp.tools.util.ObjectStream;
import opennlp.tools.util.PlainTextByLineStream;
import opennlp.tools.util.Span;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.securegraph.Element;
import org.securegraph.Property;
import org.securegraph.Vertex;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class OpenNLPMaximumEntropyExtractorGraphPropertyWorker extends GraphPropertyWorker {
    private static final LumifyLogger LOGGER = LumifyLoggerFactory.getLogger(OpenNLPMaximumEntropyExtractorGraphPropertyWorker.class);
    public static final String PATH_PREFIX_CONFIG = "termextraction.opennlp.pathPrefix";
    private static final String DEFAULT_PATH_PREFIX = "hdfs://";
    private static final int NEW_LINE_CHARACTER_LENGTH = 1;

    private List<TokenNameFinder> finders;
    private Tokenizer tokenizer;
    private String locationIri;
    private String organizationIri;
    private String personIri;

    @Override
    public void prepare(GraphPropertyWorkerPrepareData workerPrepareData) throws Exception {
        super.prepare(workerPrepareData);

        this.locationIri = getOntologyRepository().getRequiredConceptIRIByIntent("location");
        this.organizationIri = getOntologyRepository().getRequiredConceptIRIByIntent("organization");
        this.personIri = getOntologyRepository().getRequiredConceptIRIByIntent("person");

        String pathPrefix = (String) workerPrepareData.getConfiguration().get(PATH_PREFIX_CONFIG);
        if (pathPrefix == null) {
            pathPrefix = DEFAULT_PATH_PREFIX;
        }
        this.tokenizer = loadTokenizer(pathPrefix, workerPrepareData.getHdfsFileSystem());
        this.finders = loadFinders(pathPrefix, workerPrepareData.getHdfsFileSystem());
    }

    @Override
    public void execute(InputStream in, GraphPropertyWorkData data) throws Exception {
        ObjectStream<String> untokenizedLineStream = new PlainTextByLineStream(in, "UTF-8");
        String line;
        int charOffset = 0;

        LOGGER.debug("Processing artifact content stream");
        Vertex sourceVertex = (Vertex) data.getElement();
        List<Vertex> termMentions = new ArrayList<>();
        while ((line = untokenizedLineStream.read()) != null) {
            termMentions.addAll(processLine(sourceVertex, data.getProperty().getKey(), line, charOffset, LumifyProperties.VISIBILITY_JSON.getPropertyValue(sourceVertex)));
            getGraph().flush();
            charOffset += line.length() + NEW_LINE_CHARACTER_LENGTH;
        }
        applyTermMentionFilters(sourceVertex, termMentions);
        pushTextUpdated(data);

        untokenizedLineStream.close();
        LOGGER.debug("Stream processing completed");
    }

    private List<Vertex> processLine(Vertex sourceVertex, String propertyKey, String line, int charOffset, VisibilityJson visibilityJson) {
        List<Vertex> termMentions = new ArrayList<>();
        String tokenList[] = tokenizer.tokenize(line);
        Span[] tokenListPositions = tokenizer.tokenizePos(line);
        for (TokenNameFinder finder : finders) {
            Span[] foundSpans = finder.find(tokenList);
            for (Span span : foundSpans) {
                termMentions.add(createTermMention(sourceVertex, propertyKey, charOffset, span, tokenList, tokenListPositions, visibilityJson));
            }
            finder.clearAdaptiveData();
        }
        return termMentions;
    }

    private Vertex createTermMention(Vertex sourceVertex, String propertyKey, int charOffset, Span foundName, String[] tokens, Span[] tokenListPositions, VisibilityJson visibilityJson) {
        String name = Span.spansToStrings(new Span[]{foundName}, tokens)[0];
        int start = charOffset + tokenListPositions[foundName.getStart()].getStart();
        int end = charOffset + tokenListPositions[foundName.getEnd() - 1].getEnd();
        String type = foundName.getType();
        String ontologyClassUri = mapToOntologyIri(type);

        return new TermMentionBuilder()
                .sourceVertex(sourceVertex)
                .propertyKey(propertyKey)
                .start(start)
                .end(end)
                .title(name)
                .conceptIri(ontologyClassUri)
                .visibilityJson(visibilityJson)
                .process(getClass().getName())
                .save(getGraph(), getVisibilityTranslator(), getAuthorizations());
    }

    protected String mapToOntologyIri(String type) {
        String ontologyClassUri;
        if ("location".equals(type)) {
            ontologyClassUri = this.locationIri;
        } else if ("organization".equals(type)) {
            ontologyClassUri = this.organizationIri;
        } else if ("person".equals(type)) {
            ontologyClassUri = this.personIri;
        } else {
            ontologyClassUri = LumifyProperties.CONCEPT_TYPE_THING;
        }
        return ontologyClassUri;
    }

    @Override
    public boolean isHandled(Element element, Property property) {
        if (property == null) {
            return false;
        }

        if (property.getName().equals(LumifyProperties.RAW.getPropertyName())) {
            return false;
        }

        String mimeType = LumifyProperties.MIME_TYPE.getMetadataValue(property.getMetadata(), null);
        return !(mimeType == null || !mimeType.startsWith("text"));
    }

    protected List<TokenNameFinder> loadFinders(String pathPrefix, FileSystem fs)
            throws IOException {
        Path finderHdfsPaths[] = {
                new Path(pathPrefix + "/en-ner-location.bin"),
                new Path(pathPrefix + "/en-ner-organization.bin"),
                new Path(pathPrefix + "/en-ner-person.bin")};
        List<TokenNameFinder> finders = new ArrayList<>();
        for (Path finderHdfsPath : finderHdfsPaths) {
            TokenNameFinderModel model;
            try (InputStream finderModelInputStream = fs.open(finderHdfsPath)) {
                model = new TokenNameFinderModel(finderModelInputStream);
            }
            NameFinderME finder = new NameFinderME(model);
            finders.add(finder);
        }

        return finders;
    }

    protected Tokenizer loadTokenizer(String pathPrefix, FileSystem fs) throws IOException {
        Path tokenizerHdfsPath = new Path(pathPrefix + "/en-token.bin");

        TokenizerModel tokenizerModel;
        try (InputStream tokenizerModelInputStream = fs.open(tokenizerHdfsPath)) {
            tokenizerModel = new TokenizerModel(tokenizerModelInputStream);
        }

        return new TokenizerME(tokenizerModel);
    }
}
