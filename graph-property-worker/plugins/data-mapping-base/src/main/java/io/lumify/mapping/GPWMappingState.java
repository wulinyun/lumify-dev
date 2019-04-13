package io.lumify.mapping;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.lumify.core.ingest.graphProperty.GraphPropertyWorkData;
import java.util.HashMap;
import java.util.Map;
import org.securegraph.Authorizations;
import org.securegraph.EdgeBuilder;
import org.securegraph.Graph;
import org.securegraph.Vertex;

/**
 * Maintains State information during a document mapping. This class
 * encapsulates the GraphPropertyWorkData of the input job and provides
 * a Vertex cache that can be used by DocumentMapping implementations to
 * avoid creating duplicate Vertices.
 */
public final class GPWMappingState implements MappingState {
    private final AbstractDocumentMappingGraphPropertyWorker<? extends DocumentMapping> worker;
    private final GraphPropertyWorkData data;
    private final Graph graph;
    private final Authorizations authorizations;
    private final String multiKey;
    private final Map<String, String> vertexIdCache;
    private final Cache<String, Vertex> vertexCache;

    public GPWMappingState(final AbstractDocumentMappingGraphPropertyWorker<? extends DocumentMapping> _worker, final GraphPropertyWorkData _data, final Graph _graph,
                           final Authorizations _authorizations, final String _multiKey) {
        worker = _worker;
        data = _data;
        graph = _graph;
        authorizations = _authorizations;
        multiKey = _multiKey;
        vertexIdCache = new HashMap<>();
        vertexCache = CacheBuilder.newBuilder().maximumSize(100).build();
    }

    @Override
    public GraphPropertyWorkData getData() {
        return data;
    }

    @Override
    public Graph getGraph() {
        return graph;
    }

    @Override
    public Authorizations getAuthorizations() {
        return authorizations;
    }

    @Override
    public String getMultiKey() {
        return multiKey;
    }

    @Override
    public String getCachedVertexId(final String hash) {
        return vertexIdCache.get(hash);
    }

    @Override
    public void cacheVertex(final String hash, final Vertex vertex) {
        vertexIdCache.put(hash, vertex.getId());
        vertexCache.put(hash, vertex);
    }

    @Override
    public void removeVertex(final Vertex vertex) {
        String hash = null;
        for (Map.Entry<String, String> idEntry : vertexIdCache.entrySet()) {
            if (idEntry.getValue().equals(vertex.getId())) {
                hash = idEntry.getKey();
                break;
            }
        }
        if (hash != null) {
            vertexIdCache.remove(hash);
            vertexCache.invalidate(hash);
        }
    }

    @Override
    public Vertex getVertex(final String hash) {
        Vertex vertex = vertexCache.getIfPresent(hash);
        if (vertex == null) {
            String vertexId = getCachedVertexId(hash);
            if (vertexId != null) {
                vertex = graph.getVertex(vertexId, authorizations);
            }
        }
        return vertex;
    }

    @Override
    public void createHasEntityEdge(final Vertex entityVertex) {
        Vertex artifactVertex = (Vertex) data.getElement();
        String edgeId = String.format("%s_hasEntity_%s", artifactVertex.getId(), entityVertex.getId());
        EdgeBuilder edge = graph.prepareEdge(edgeId, artifactVertex, entityVertex, worker.getHasEntityIri(), data.getVisibility());
        data.setVisibilityJsonOnElement(edge);
        edge.save(authorizations);
    }

    @Override
    public void addVertexToWorkspaceIfNeeded(final Vertex vertex) {
        worker.addVertexToWorkspaceIfNeeded(data, vertex);
    }
}
