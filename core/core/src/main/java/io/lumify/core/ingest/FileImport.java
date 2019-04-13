package io.lumify.core.ingest;

import com.google.inject.Inject;
import io.lumify.core.bootstrap.InjectHelper;
import io.lumify.core.config.Configuration;
import io.lumify.core.ingest.FileImportSupportingFileHandler.AddSupportingFilesResult;
import io.lumify.core.model.properties.LumifyProperties;
import io.lumify.core.model.workQueue.WorkQueueRepository;
import io.lumify.core.model.workspace.Workspace;
import io.lumify.core.model.workspace.WorkspaceRepository;
import io.lumify.core.security.LumifyVisibility;
import io.lumify.core.security.VisibilityTranslator;
import io.lumify.core.user.User;
import io.lumify.core.util.*;
import io.lumify.web.clientapi.model.VisibilityJson;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.json.JSONObject;
import org.securegraph.*;
import org.securegraph.property.StreamingPropertyValue;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import static org.securegraph.util.IterableUtils.toList;

public class FileImport {
    private static final LumifyLogger LOGGER = LumifyLoggerFactory.getLogger(FileImport.class);
    public static final String MULTI_VALUE_KEY = FileImport.class.getName();
    private final VisibilityTranslator visibilityTranslator;
    private final Graph graph;
    private final WorkQueueRepository workQueueRepository;
    private final WorkspaceRepository workspaceRepository;
    private final Configuration configuration;
    private List<FileImportSupportingFileHandler> fileImportSupportingFileHandlers;

    @Inject
    public FileImport(
            VisibilityTranslator visibilityTranslator,
            Graph graph,
            WorkQueueRepository workQueueRepository,
            WorkspaceRepository workspaceRepository,
            Configuration configuration
    ) {
        this.visibilityTranslator = visibilityTranslator;
        this.graph = graph;
        this.workQueueRepository = workQueueRepository;
        this.workspaceRepository = workspaceRepository;
        this.configuration = configuration;
    }

    public void importDirectory(File dataDir, boolean queueDuplicates, String visibilitySource, Workspace workspace, User user, Authorizations authorizations) throws IOException {
        ensureInitialized();

        LOGGER.debug("Importing files from %s", dataDir);
        File[] files = dataDir.listFiles();
        if (files == null || files.length == 0) {
            return;
        }

        int totalFileCount = files.length;
        int fileCount = 0;
        int importedFileCount = 0;
        try {
            for (File f : files) {
                if (f.getName().startsWith(".") || f.length() == 0) {
                    continue;
                }
                if (isSupportingFile(f)) {
                    continue;
                }

                LOGGER.debug("Importing file (%d/%d): %s", fileCount + 1, totalFileCount, f.getAbsolutePath());
                try {
                    importFile(f, queueDuplicates, visibilitySource, workspace, user, authorizations);
                    importedFileCount++;
                } catch (Exception ex) {
                    LOGGER.error("Could not import %s", f.getAbsolutePath(), ex);
                }
                fileCount++;
            }
        } finally {
            graph.flush();
        }

        LOGGER.debug(String.format("Imported %d, skipped %d files from %s", importedFileCount, fileCount - importedFileCount, dataDir));
    }

    private boolean isSupportingFile(File f) {
        for (FileImportSupportingFileHandler fileImportSupportingFileHandler : this.fileImportSupportingFileHandlers) {
            if (fileImportSupportingFileHandler.isSupportingFile(f)) {
                return true;
            }
        }
        return false;
    }

    public Vertex importFile(File f, boolean queueDuplicates, String visibilitySource, Workspace workspace, User user, Authorizations authorizations) throws Exception {
        ensureInitialized();

        String hash = calculateFileHash(f);

        Vertex vertex = findExistingVertexWithHash(hash, authorizations);
        if (vertex != null) {
            LOGGER.warn("vertex already exists with hash %s", hash);
            if (queueDuplicates) {
                pushOnQueue(vertex, MULTI_VALUE_KEY, LumifyProperties.RAW.getPropertyName(), workspace, visibilitySource);
            }
            return vertex;
        }

        List<FileImportSupportingFileHandler.AddSupportingFilesResult> addSupportingFilesResults = new ArrayList<>();

        try (FileInputStream fileInputStream = new FileInputStream(f)) {
            JSONObject metadataJson = loadMetadataJson(f);
            String predefinedId = null;
            if (metadataJson != null) {
                predefinedId = metadataJson.optString("id", null);
                String metadataVisibilitySource = metadataJson.optString("visibilitySource", null);
                if (metadataVisibilitySource != null) {
                    visibilitySource = metadataVisibilitySource;
                }
            }

            StreamingPropertyValue rawValue = new StreamingPropertyValue(fileInputStream, byte[].class);
            rawValue.searchIndex(false);

            VisibilityJson visibilityJson = GraphUtil.updateVisibilitySourceAndAddWorkspaceId(null, visibilitySource, workspace == null ? null : workspace.getWorkspaceId());
            LumifyVisibility lumifyVisibility = this.visibilityTranslator.toVisibility(visibilityJson);
            Visibility visibility = lumifyVisibility.getVisibility();
            Metadata propertyMetadata = new Metadata();
            LumifyProperties.CONFIDENCE.setMetadata(propertyMetadata, 0.1, visibilityTranslator.getDefaultVisibility());
            LumifyProperties.VISIBILITY_JSON.setMetadata(propertyMetadata, visibilityJson, visibilityTranslator.getDefaultVisibility());

            VertexBuilder vertexBuilder;
            if (predefinedId == null) {
                vertexBuilder = this.graph.prepareVertex(visibility);
            } else {
                vertexBuilder = this.graph.prepareVertex(predefinedId, visibility);
            }
            LumifyProperties.VISIBILITY_JSON.setProperty(vertexBuilder, visibilityJson, visibility);
            LumifyProperties.RAW.addPropertyValue(vertexBuilder, MULTI_VALUE_KEY, rawValue, propertyMetadata, visibility);
            LumifyProperties.TITLE.addPropertyValue(vertexBuilder, MULTI_VALUE_KEY, f.getName(), propertyMetadata, visibility);
            LumifyProperties.CONTENT_HASH.addPropertyValue(vertexBuilder, MULTI_VALUE_KEY, hash, propertyMetadata, visibility);
            LumifyProperties.FILE_NAME.addPropertyValue(vertexBuilder, MULTI_VALUE_KEY, f.getName(), propertyMetadata, visibility);
            LumifyProperties.FILE_NAME_EXTENSION.addPropertyValue(vertexBuilder, MULTI_VALUE_KEY, FilenameUtils.getExtension(f.getName()), propertyMetadata, visibility);
            LumifyProperties.CREATE_DATE.addPropertyValue(vertexBuilder, MULTI_VALUE_KEY, new Date(f.lastModified()), propertyMetadata, visibility);

            for (FileImportSupportingFileHandler fileImportSupportingFileHandler : this.fileImportSupportingFileHandlers) {
                FileImportSupportingFileHandler.AddSupportingFilesResult addSupportingFilesResult = fileImportSupportingFileHandler.addSupportingFiles(vertexBuilder, f, visibility);
                if (addSupportingFilesResult != null) {
                    addSupportingFilesResults.add(addSupportingFilesResult);
                }
            }

            vertex = vertexBuilder.save(authorizations);
            graph.flush();

            if (workspace != null) {
                workspaceRepository.updateEntityOnWorkspace(workspace, vertex.getId(), null, null, user);
            }

            LOGGER.debug("File %s imported. vertex id: %s", f.getAbsolutePath(), vertex.getId());
            pushOnQueue(vertex, MULTI_VALUE_KEY, LumifyProperties.RAW.getPropertyName(), workspace, visibilitySource);
            for (AddSupportingFilesResult result : addSupportingFilesResults) {
                for (String propertyName : result.getPropertiesToQueue()) {
                    pushOnQueue(vertex, propertyName, workspace, visibilitySource);
                }
            }
            return vertex;
        } finally {
            for (FileImportSupportingFileHandler.AddSupportingFilesResult addSupportingFilesResult : addSupportingFilesResults) {
                addSupportingFilesResult.close();
            }
        }
    }

    public List<Vertex> importVertices(Workspace workspace, List<FileAndVisibility> files, User user, Authorizations authorizations) throws Exception {
        ensureInitialized();

        List<Vertex> vertices = new ArrayList<>();
        for (FileAndVisibility file : files) {
            if (isSupportingFile(file.getFile())) {
                LOGGER.debug("Skipping file: %s (supporting file)", file.getFile().getAbsolutePath());
                continue;
            }
            LOGGER.debug("Processing file: %s", file.getFile().getAbsolutePath());
            vertices.add(importFile(file.getFile(), true, file.getVisibilitySource(), workspace, user, authorizations));
        }
        return vertices;
    }

    private JSONObject loadMetadataJson(File f) throws IOException {
        File metadataFile = MetadataFileImportSupportingFileHandler.getMetadataFile(f);
        if (metadataFile.exists()) {
            try (FileInputStream in = new FileInputStream(metadataFile)) {
                String fileContents = IOUtils.toString(in);
                return new JSONObject(fileContents);
            }
        }
        return null;
    }

    private void ensureInitialized() {
        if (fileImportSupportingFileHandlers == null) {
            fileImportSupportingFileHandlers = toList(ServiceLoaderUtil.load(FileImportSupportingFileHandler.class, this.configuration));
            for (FileImportSupportingFileHandler fileImportSupportingFileHandler : fileImportSupportingFileHandlers) {
                InjectHelper.inject(fileImportSupportingFileHandler);
            }
        }
    }

    private void pushOnQueue(Vertex vertex, String propertyName, Workspace workspace, String visibilitySource) {
        pushOnQueue(vertex, null, propertyName, workspace, visibilitySource);
    }

    private void pushOnQueue(Vertex vertex, String propertyKey, String propertyName, Workspace workspace, String visibilitySource) {
        LOGGER.debug("pushing [%s, %s] on to %s queue", vertex.getId(), propertyName, WorkQueueRepository.GRAPH_PROPERTY_QUEUE_NAME);
        this.workQueueRepository.pushElement(vertex);
        if (workspace != null) {
            this.workQueueRepository.pushGraphPropertyQueue(vertex, propertyKey,
                    propertyName, workspace.getWorkspaceId(), visibilitySource);
        } else {
            this.workQueueRepository.pushGraphPropertyQueue(vertex, propertyKey, propertyName);
        }
    }

    private Vertex findExistingVertexWithHash(String hash, Authorizations authorizations) {
        Iterator<Vertex> existingVertices = this.graph.query(authorizations)
                .has(LumifyProperties.CONTENT_HASH.getPropertyName(), hash)
                .vertices()
                .iterator();
        if (existingVertices.hasNext()) {
            return existingVertices.next();
        }
        return null;
    }

    private String calculateFileHash(File f) throws IOException {
        try (FileInputStream fileInputStream = new FileInputStream(f)) {
            return RowKeyHelper.buildSHA256KeyString(fileInputStream);
        }
    }

    public static class FileAndVisibility {
        private File file;
        private String visibilitySource;

        public File getFile() {
            return file;
        }

        public void setFile(File file) {
            this.file = file;
        }

        public String getVisibilitySource() {
            return visibilitySource;
        }

        public void setVisibilitySource(String visibilitySource) {
            this.visibilitySource = visibilitySource;
        }
    }
}
