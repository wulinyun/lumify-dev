
define([
    '../util/ajax',
    './storeHelper'
], function(ajax, storeHelper) {
    'use strict';

    var api = {

        audit: function(vertexId) {
            return ajax('GET', '/vertex/audit', {
                graphVertexId: vertexId
            }).then(_.property('auditHistory'));
        },

        search: function(options) {
            var params = {},
                q = _.isUndefined(options.query.query) ?
                    options.query :
                    options.query.query;

            if (options.conceptFilter) params.conceptType = options.conceptFilter;
            if (options.paging) {
                if (options.paging.offset) params.offset = options.paging.offset;
                if (options.paging.size) params.size = options.paging.size;
            }

            if (q) {
                params.q = q;
            }

            if (options.query && options.query.relatedToVertexIds) {
                params.relatedToVertexIds = options.query.relatedToVertexIds;
            }
            params.filter = JSON.stringify(options.propertyFilters || []);

            return ajax('POST', '/vertex/search', params);
        },

        'geo-search': function(lat, lon, radius) {
            return ajax('GET', '/vertex/geo-search', {
                lat: lat,
                lon: lon,
                radius: radius
            });
        },

        findPath: function(options) {
            return ajax('GET', '/vertex/find-path', options);
        },

        multiple: function(options) {
            return ajax('POST', '/vertex/multiple', options);
        },

        properties: function(vertexId) {
            return ajax('GET', '/vertex/properties', {
                graphVertexId: vertexId
            });
        },

        propertySourceInfo: function(vertexId, name, key, visibility) {
            return ajax('GET', '/vertex/property/source-info', {
                vertexId: vertexId,
                propertyName: name,
                propertyKey: key,
                visibilitySource: visibility
            });
        },

        edges: function(vertexId, options) {
            var parameters = {
                graphVertexId: vertexId
            };
            if (options) {
                if (options.offset) parameters.offset = options.offset;
                if (options.size) parameters.size = options.size;
                if (options.edgeLabel) parameters.edgeLabel = options.edgeLabel;
            }

            return ajax('GET', '/vertex/edges', parameters);
        },

        'delete': function(vertexId) {
            return ajax('DELETE', '/vertex', {
                graphVertexId: vertexId
            })
        },

        deleteProperty: function(vertexId, property) {
            return ajax('DELETE', '/vertex/property', {
                graphVertexId: vertexId,
                propertyName: property.name,
                propertyKey: property.key
            })
        },

        'highlighted-text': function(vertexId, propertyKey) {
            return ajax('GET->HTML', '/vertex/highlighted-text', {
                graphVertexId: vertexId,
                propertyKey: propertyKey
            });
        },

        related: function(vertexIds, options) {
            return ajax('POST', '/vertex/find-related', {
                graphVertexIds: vertexIds,
                limitEdgeLabel: options.limitEdgeLabel,
                limitParentConceptId: options.limitParentConceptId
            });
        },

        store: storeHelper.createStoreAccessorOrDownloader(
            'vertex', 'vertexIds', 'vertices',
            function(toRequest) {
                return api.multiple({
                    vertexIds: toRequest
                });
            }
        ),

        uploadImage: function(vertexId, file) {
            return ajax('POST', '/vertex/upload-image?' +
                'graphVertexId=' + encodeURIComponent(vertexId), file);
        },

        create: function(conceptType, justification, visibilitySource) {
            return ajax('POST', '/vertex/new', _.tap({
                conceptType: conceptType,
                visibilitySource: visibilitySource
            }, function(data) {
                if (justification.justificationText) {
                    data.justificationText = justification.justificationText;
                } else if (justification.sourceInfo) {
                    data.sourceInfo = JSON.stringify(justification.sourceInfo);
                }
            }));
        },

        importFiles: function(files, visibilitySource) {
            var formData = new FormData();

            _.forEach(files, function(f) {
                formData.append('file', f);
                if (_.isString(visibilitySource)) {
                    formData.append('visibilitySource', visibilitySource);
                }
            });

            if (_.isArray(visibilitySource)) {
                _.forEach(visibilitySource, function(v) {
                    formData.append('visibilitySource', v);
                });
            }

            return ajax('POST', '/vertex/import', formData);
        },

        setVisibility: function(vertexId, visibilitySource) {
            return ajax('POST', '/vertex/visibility', {
                graphVertexId: vertexId,
                visibilitySource: visibilitySource
            });
        },

        setProperty: function(vertexId, property, optionalWorkspaceId) {
            var url = '/vertex/' + (
                property.name === 'http://lumify.io/comment#entry' ?
                'comment' : 'property'
            );

            return ajax('POST', url, _.tap({
                 graphVertexId: vertexId,
                 propertyName: property.name,
                 value: property.value,
                 visibilitySource: property.visibilitySource,
                 justificationText: property.justificationText
            }, function(params) {
                if (property.sourceInfo) {
                    params.sourceInfo = JSON.stringify(property.sourceInfo);
                }
                if (!_.isUndefined(property.key)) {
                    params.propertyKey = property.key;
                }
                if (property.metadata) {
                    params.metadata = JSON.stringify(property.metadata)
                }
                if (optionalWorkspaceId) {
                    params.workspaceId = optionalWorkspaceId;
                }
            }));
        },

        resolveTerm: function(params) {
            return ajax('POST', '/vertex/resolve-term', params);
        },

        unresolveTerm: function(params) {
            return ajax('POST', '/vertex/unresolve-term', params);
        },

        resolveDetectedObject: function(params) {
            return ajax('POST', '/vertex/resolve-detected-object', params);
        },

        unresolveDetectedObject: function(params) {
            return ajax('POST', '/vertex/unresolve-detected-object', params);
        }
    };

    return api;
});
