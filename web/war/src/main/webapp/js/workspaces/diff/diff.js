
define([
    'flight/lib/component',
    'tpl!./diff',
    'util/vertex/formatters',
    'util/privileges',
    'util/withDataRequest'
], function(
    defineComponent,
    template,
    F,
    Privileges,
    withDataRequest) {
    'use strict';

    var SHOW_CHANGES_TEXT_SECONDS = 3;

    return defineComponent(Diff, withDataRequest);

    function titleForEdgesVertices(vertex, vertexId, diffsGroupedByElement) {
        if (vertex) {
            return F.vertex.title(vertex);
        }

        var matchingDiffs = diffsGroupedByElement[vertexId],
            diff = matchingDiffs && matchingDiffs.length && matchingDiffs[0];

        if (diff && diff.title) {
            return diff.title;
        }

        return i18n('vertex.property.title.not_available');
    }

    function Diff() {

        this.defaultAttrs({
            buttonSelector: 'button',
            headerButtonSelector: '.header button',
            rowSelector: 'tr',
            selectAllButtonSelector: '.select-all-publish,.select-all-undo'
        })

        this.after('initialize', function() {
            var self = this;

            this.dataRequest('ontology', 'ontology').done(function(ontology) {
                self.ontologyConcepts = ontology.concepts;
                self.ontologyProperties = ontology.properties;
                self.ontologyRelationships = ontology.relationships;
                self.setup();
            })
        });

        this.setup = function() {
            var self = this,
                formatLabel = function(name) {
                    return self.ontologyProperties.byTitle[name].displayName;
                },
                formatVisibility = function(propertyOrProperties) {
                    var property = _.isArray(propertyOrProperties) ? propertyOrProperties[0] : propertyOrProperties;
                    return JSON.stringify(property['http://lumify.io#visibilityJson']);
                },
                formatValue = function(name, change, property) {
                    return F.vertex.prop({
                        id: property.id,
                        properties: change ? _.isArray(change) ? change : [change] : []
                    }, name, property.key)
                };

            self.processDiffs(self.attr.diffs).done(function(processDiffs) {
                self.$node.html(template({
                    diffs: processDiffs,
                    formatValue: formatValue,
                    formatVisibility: formatVisibility,
                    formatLabel: formatLabel,
                    F: F,
                    Privileges: Privileges
                }));
                self.updateVisibility();
                self.updateHeader();
                self.updateDraggables();
            });

            self.on('click', {
                selectAllButtonSelector: self.onSelectAll,
                buttonSelector: self.onButtonClick,
                headerButtonSelector: self.onApplyAll,
                rowSelector: self.onRowClick
            });
            self.on('diffsChanged', function(event, data) {
                self.processDiffs(data.diffs).done(function(processDiffs) {

                    var scroll = self.$node.find('.diffs-list'),
                        previousScroll = scroll.scrollTop(),
                        previousPublished = self.$node.find('.mark-publish').map(function() {
                            return '.' + F.className.to($(this).data('diffId'));
                        }).toArray(),
                        previousUndo = self.$node.find('.mark-undo').map(function() {
                            return '.' + F.className.to($(this).data('diffId'));
                        }).toArray(),
                        previousSelection  = _.compact(self.$node.find('.active').map(function() {
                            return $(this).data('diffId');
                        }).toArray());

                    self.$node.html(template({
                        diffs: processDiffs,
                        formatValue: formatValue,
                        formatLabel: formatLabel,
                        formatVisibility: formatVisibility,
                        F: F,
                        Privileges: Privileges
                    }));

                    self.selectVertices(previousSelection);
                    self.$node.find(previousPublished.join(',')).each(function() {
                        var $this = $(this);

                        if ($this.find('.actions').length) {
                            $(this).addClass('mark-publish')
                                .find('.publish').addClass('btn-success')
                        }
                    });
                    self.$node.find(previousUndo.join(',')).each(function() {
                        var $this = $(this);

                        if ($this.find('.actions').length) {
                            $(this).addClass('mark-undo')
                                .find('.undo').addClass('btn-danger')
                        }
                    });
                    self.updateVisibility();
                    self.updateHeader(self.$node.closest('.popover:visible').length > 0);
                    self.updateDraggables();
                    self.$node.find('.diffs-list').scrollTop(previousScroll);
                });
            })
            self.on('markPublishDiffItem', self.onMarkPublish);
            self.on('markUndoDiffItem', self.onMarkUndo);
            self.on(document, 'objectsSelected', self.onObjectsSelected);
        };

        this.processDiffs = function(diffs) {
            var self = this,
                referencedVertices = [],
                referencedEdges = [],
                groupedByElement = _.groupBy(diffs, function(diff) {
                    if (diff.elementType === 'vertex' || diff.type === 'VertexDiffItem') {
                        referencedVertices.push(diff.vertexId || diff.elementId || diff.outVertexId);
                    } else if (diff.elementType === 'edge' || diff.type === 'EdgeDiffItem') {
                        referencedEdges.push(diff.edgeId || diff.elementId);
                    }
                    if (diff.inVertexId) {
                        referencedVertices.push(diff.inVertexId);
                    }
                    if (diff.outVertexId) {
                        referencedVertices.push(diff.outVertexId);
                    }
                    if (diff.vertexId) return diff.vertexId;
                    if (diff.edgeId) return diff.edgeId;
                    if (diff.elementId) return diff.elementId;
                    return diff.outVertexId;
                }),
                output = [];

            return Promise.all([
                this.dataRequest('vertex', 'store', { vertexIds: _.unique(referencedVertices) }),
                this.dataRequest('edge', 'store', { edgeIds: _.unique(referencedEdges) })
            ])
                .then(function(result) {
                    var vertices = _.compact(result.shift()),
                        edges = _.compact(result.shift()),
                        verticesById = _.indexBy(vertices, 'id'),
                        edgesById = _.indexBy(edges, 'id');

                    self.diffsForElementId = {};
                    self.diffsById = {};
                    self.diffDependencies = {};
                    self.undoDiffDependencies = {};

                    _.each(groupedByElement, function(diffs, elementId) {
                        var actionTypes = {
                                CREATE: { type: 'create', display: i18n('workspaces.diff.action.types.create') },
                                UPDATE: { type: 'update', display: i18n('workspaces.diff.action.types.update') },
                                DELETE: { type: 'delete', display: i18n('workspaces.diff.action.types.delete') }
                            },
                            outputItem = {
                                properties: [],
                                action: {},
                                className: F.className.to(elementId),
                            },
                            isElementVertex = (
                                diffs[0].elementType === 'vertex' ||
                                diffs[0].type === 'VertexDiffItem'
                            );

                        if (isElementVertex) {
                            outputItem.vertexId = elementId;
                            outputItem.vertex = verticesById[elementId];
                            if (outputItem.vertex) {
                                outputItem.title = F.vertex.title(outputItem.vertex);
                            } else {
                                outputItem.vertex = {
                                    id: elementId,
                                    properties: [],
                                    'http://lumify.io#visibilityJson': diffs[0]['http://lumify.io#visibilityJson']
                                };
                                outputItem.title = diffs[0].title || i18n('vertex.property.title.not_available');
                            }
                            if (diffs[0].conceptType) {
                                var concept = self.ontologyConcepts.byId[diffs[0].conceptType];
                                if (concept) {
                                    outputItem.conceptImage = concept.glyphIconHref;
                                }
                            }
                        } else {
                            outputItem.edgeId = elementId;
                            outputItem.edge = edgesById[elementId];
                            if (outputItem.edge) {
                                outputItem.edgeLabel = self.ontologyRelationships.byTitle[outputItem.edge.label]
                                .displayName;
                            } else {
                                outputItem.edge = {
                                    id: elementId,
                                    properties: [],
                                    'http://lumify.io#visibilityJson': diffs[0].visibilityJson
                                };
                                outputItem.edgeLabel = self.ontologyRelationships.byTitle[diffs[0].label].displayName;
                            }

                            var sourceId = diffs[0].outVertexId,
                                targetId = diffs[0].inVertexId,
                                source = verticesById[sourceId],
                                target = verticesById[targetId];
                            outputItem.sourceTitle = titleForEdgesVertices(source, sourceId, groupedByElement);
                            outputItem.targetTitle = titleForEdgesVertices(target, targetId, groupedByElement);
                        }

                        diffs.forEach(function(diff) {

                            switch (diff.type) {
                                case 'VertexDiffItem':
                                    diff.id = elementId;
                                    outputItem.action = diff.deleted ? actionTypes.DELETE : actionTypes.CREATE;
                                    self.diffsForElementId[elementId] = diff;
                                    self.diffsById[elementId] = diff;
                                    addDiffDependency(diff.id);
                                    break;

                                case 'PropertyDiffItem':

                                    var ontologyProperty = self.ontologyProperties.byTitle[diff.name],
                                        compoundProperty = self.ontologyProperties.byDependentToCompound[diff.name];

                                    if (ontologyProperty && ontologyProperty.userVisible) {
                                        diff.id = elementId + diff.name + diff.key;
                                        addDiffDependency(diff.elementId, diff);

                                        diff.className = F.className.to(diff.id);
                                        if (compoundProperty) {
                                            diff.dependentName = diff.name;
                                            diff.name = compoundProperty;
                                            var previousPropertyWithKey = _.findWhere(outputItem.properties, {
                                                key: diff.key
                                            })
                                            if (previousPropertyWithKey) {
                                                if (previousPropertyWithKey.old) {
                                                    previousPropertyWithKey.old.push(diff.old);
                                                }
                                                if (previousPropertyWithKey.new) {
                                                    previousPropertyWithKey.new.push(diff.new);
                                                }
                                                previousPropertyWithKey.diffs.push(diff)
                                            } else {
                                                if (diff.old) {
                                                    diff.old = [diff.old];
                                                }
                                                if (diff.new) {
                                                    diff.new = [diff.new];
                                                }
                                                diff.diffs = [diff];
                                                outputItem.properties.push(diff)
                                            }
                                        } else {
                                            outputItem.properties.push(diff)
                                        }
                                        self.diffsById[diff.id] = diff;
                                    }
                                    break;

                                case 'EdgeDiffItem':
                                    diff.id = diff.edgeId;
                                    diff.inVertex = verticesById[diff.inVertexId];
                                    diff.outVertex = verticesById[diff.outVertexId];
                                    diff.className = F.className.to(diff.edgeId);
                                    diff.displayLabel = self.ontologyRelationships.byTitle[diff.label].displayName;
                                    self.diffsForElementId[diff.edgeId] = diff;
                                    outputItem.action = diff.deleted ? actionTypes.DELETE : actionTypes.CREATE;
                                    addDiffDependency(diff.inVertexId, diff);
                                    addDiffDependency(diff.outVertexId, diff);
                                    self.diffsById[diff.id] = diff;
                                    break;

                                default:
                                    console.warn('Unknown diff item type', diff.type)
                            }

                            addDiffDependency(diff.id);
                        });

                        if (_.isEmpty(outputItem.action)) {
                            outputItem.action = actionTypes.UPDATE;
                        }

                        output.push(outputItem);
                    });

                    return output;
            });

            function addDiffDependency(id, diff) {
                if (!self.diffDependencies[id]) {
                    self.diffDependencies[id] = [];
                }
                if (diff) {
                    self.diffDependencies[id].push(diff.id);

                    // Undo dependencies are inverse
                    if (!self.undoDiffDependencies[diff.id]) {
                        self.undoDiffDependencies[diff.id] = [];
                    }
                    self.undoDiffDependencies[diff.id].push(id);
                }
            }
        };

        this.onObjectsSelected = function(event, data) {
            var self = this,
                toSelect = data && data.vertices.concat(data.edges || []) || [];

            this.$node.find('.active').removeClass('active');
            this.selectVertices(toSelect);
        };

        this.selectVertices = function(vertices) {
            var self = this,
                cls = vertices.map(function(vertex) {
                    return '.' + F.className.to(_.isString(vertex) ? vertex : vertex.id);
                });
            this.$node.find(cls.join(',')).addClass('active');
        };

        this.onRowClick = function(event) {
            var self = this,
                $target = $(event.target).not('button').closest('tr'),
                vertexRow = $target.is('.vertex-row') ? $target : $target.prevAll('.vertex-row'),
                alreadySelected = vertexRow.is('.active'),
                vertexId = vertexRow.data('vertexId'),
                edgeId = vertexRow.data('edgeId');

            if (vertexId) {
                self.trigger('selectObjects', {
                    vertexIds: (!alreadySelected && vertexId) ? [vertexId] : []
                });
            } else if (edgeId) {
                self.trigger('selectObjects', {
                    edgeIds: (!alreadySelected && edgeId) ? [edgeId] : []
                });
            }
        };

        this.onButtonClick = function(event) {
            var $target = $(event.target),
                $row = $target.closest('tr');

            if ($target.is('.header button') || $target.closest('.select-actions').length) {
                return;
            }

            this.$node.find('.select-actions .actions button').removeClass('btn-danger btn-success');

            event.stopPropagation();
            $target.blur();

            this.trigger(
                'mark' + ($target.hasClass('publish') ? 'Publish' : 'Undo') + 'DiffItem',
                {
                    diffId: $row.data('diffId'),
                    state: !($target.hasClass('btn-success') || $target.hasClass('btn-danger'))
                }
            );
        };

        this.onSelectAll = function(event) {
            var target = $(event.target),
                action = target.data('action'),
                cls = action === 'publish' ? 'success' : 'danger';

            event.stopPropagation();
            target.blur();

            if (target.hasClass('btn-' + cls)) {
                target.removeClass('btn-' + cls);
                this.$node.find('.mark-' + action + ' button.' + action)
                    .each(function() {
                        if ($(this).closest('tr.mark-' + action).length) {
                            $(this).click();
                        }
                    });
            } else {

                this.$node.find('button.' + action)
                    .each(function() {
                        if ($(this).closest('tr.mark-' + action).length === 0) {
                            $(this).click()
                        }
                    });

                this.$node.find('.select-all-publish').removeClass('btn-success');
                this.$node.find('.select-all-undo').removeClass('btn-danger');
                this.$node.find('.select-all-' + action).addClass('btn-' + cls);
            }
        };

        this.onApplyAll = function(event) {
            var self = this,
                button = $(event.target).addClass('loading').attr('disabled', true),
                otherButton = button.siblings('button').attr('disabled', true),
                bothButtons = button.add(otherButton),
                header = this.$node.find('.header'),
                type = button.hasClass('publish-all') ? 'publish' : 'undo',
                diffsToSend = this.$node.find('.mark-' + type).map(function mapper(iOrDiff) {
                    var isDiff = _.isObject(iOrDiff),
                        diff = isDiff ? iOrDiff : self.diffsById[$(this).data('diffId')];

                    if (!isDiff && diff.diffs) {
                        return diff.diffs.map(function(d) {
                            if (d.dependentName) {
                                d.name = d.dependentName;
                            }
                            return mapper(d);
                        });
                    }

                    switch (diff.type) {

                        case 'PropertyDiffItem': return _.tap({
                            type: 'property',
                            key: diff.key,
                            name: diff.name,
                            action: diff.deleted ? 'delete' : 'update',
                            status: diff.sandboxStatus
                        }, function(obj) {
                            obj[diff.elementType + 'Id'] = diff.elementId;
                        });

                        case 'VertexDiffItem': return {
                            type: 'vertex',
                            vertexId: diff.vertexId,
                            action: diff.deleted ? 'delete' : 'create',
                            status: diff.sandboxStatus
                        };

                        case 'EdgeDiffItem': return {
                            type: 'relationship',
                            edgeId: diff.edgeId,
                            sourceId: diff.outVertexId,
                            destId: diff.inVertexId,
                            action: diff.deleted ? 'delete' : 'create',
                            status: diff.sandboxStatus
                        };
                    }
                    console.error('Unknown diff type', diff);
                }).toArray();

            this.dataRequest('workspace', type, diffsToSend)
                .finally(function() {
                    bothButtons.hide().removeAttr('disabled').removeClass('loading');
                    self.$node.find('.diff-content .alert').remove();
                    self.trigger(document, 'updateDiff');
                })
                .then(function(response) {
                    var failures = response.failures,
                        success = response.success;

                    if (failures && failures.length) {
                        var error = $('<div>')
                            .addClass('alert alert-error')
                            .html(
                                '<button type="button" class="close" data-dismiss="alert">&times;</button>' +
                                '<ul><li>' + _.pluck(failures, 'errorMessage').join('</li><li>') + '</li></ul>'
                            )
                            .prependTo(self.$node.find('.diff-content'))
                            .alert();
                        self.updateHeader();
                    }
                })
                .catch(function(errorText) {
                    var error = $('<div>')
                        .addClass('alert alert-error')
                        .html(
                            '<button type="button" class="close" data-dismiss="alert">&times;</button>' +
                            i18n('workspaces.diff.error', type, errorText)
                        )
                        .prependTo(self.$node.find('.diff-content'))
                        .alert();

                    button.show();

                    _.delay(error.remove.bind(error), 5000)
                });
        };

        this.updateDraggables = function() {
            this.$node.find('.vertex-label h1')
                .draggable({
                    appendTo: 'body',
                    helper: 'clone',
                    revert: 'invalid',
                    revertDuration: 250,
                    scroll: false,
                    zIndex: 100,
                    distance: 10,
                    start: function(event, ui) {
                        ui.helper.css({
                            paddingLeft: $(this).css('padding-left'),
                            paddingTop: 0,
                            paddingBottom: 0,
                            margin: 0,
                            fontSize: $(this).css('font-size')
                        })
                    }
                });

            this.$node.droppable({ tolerance: 'pointer', accept: '*' });
        };

        this.updateVisibility = function() {
            var self = this;

            require(['configuration/plugins/visibility/visibilityDisplay'], function(Visibility) {
                self.$node.find('.visibility').each(function() {
                    var node = $(this),
                        visibility = node.data('visibility');

                    Visibility.attachTo(node, {
                        value: visibility && visibility.source
                    });
                });
            });
        };

        this.updateHeader = function(showSuccess) {
            var self = this,
                markedAsPublish = this.$node.find('.mark-publish').length,
                markedAsUndo = this.$node.find('.mark-undo').length,
                header = this.$node.find('.header span'),
                headerText = header.text(),
                publish = this.$node.find('.publish-all'),
                undo = this.$node.find('.undo-all');

            if (this.updateHeaderDelay) {
                clearTimeout(this.updateHeaderDelay);
                this.updateHeaderDelay = null;
            }

            if (showSuccess) {
                publish.hide();
                undo.hide();
                header.show();
                this.updateHeaderDelay = _.delay(function() {
                    self.updateHeader();
                }, SHOW_CHANGES_TEXT_SECONDS * 1000);
            } else {
                header.toggle(markedAsPublish === 0 && markedAsUndo === 0);

                publish.toggle(markedAsPublish > 0)
                    .attr('data-count', F.number.pretty(markedAsPublish));

                undo.toggle(markedAsUndo > 0)
                    .attr('data-count', F.number.pretty(markedAsUndo));
            }
        }

        this.onMarkUndo = function(event, data) {
            var self = this,
                diffId = data.diffId,
                diff = this.diffsById[diffId],
                deps = this.diffDependencies[diffId] || [],
                state = data.state,
                stateBasedClassFunction = state ? 'addClass' : 'removeClass',
                inverseStateBasedClassFunction = !state ? 'addClass' : 'removeClass';

            if (!diff) {
                return;
            }

            this.$node.find('tr.' + F.className.to(diff.id)).each(function() {
                $(this)
                    .removePrefixedClasses('mark-')
                    [stateBasedClassFunction]('mark-undo')
                    .find('button.undo')[stateBasedClassFunction]('btn-danger')
                    .siblings('button.publish').removeClass('btn-success');
            });

            this.updateHeader();

            switch (diff.type) {
                case 'VertexDiffItem':

                    if (state) {
                        if (!diff.deleted) {
                            deps.forEach(function(diffId) {
                                self.trigger('markUndoDiffItem', { diffId: diffId, state: true });
                            })
                        }
                    }

                    break;

                case 'PropertyDiffItem':

                    if (!state) {
                        var vertexDiff = self.diffsForElementId[diff.elementId];
                        if (vertexDiff) {
                            self.trigger('markUndoDiffItem', { diffId: vertexDiff.id, state: false });
                        }
                    }

                    break;

                case 'EdgeDiffItem':

                    var inVertex = self.diffsForElementId[diff.inVertexId],
                        outVertex = self.diffsForElementId[diff.outVertexId];

                    if (state) {
                        if (diff.deleted) {
                            if (inVertex && inVertex.deleted) {
                                self.trigger('markUndoDiffItem', { diffId: inVertex.id, state: true });
                            }
                            if (outVertex && outVertex.deleted) {
                                self.trigger('markUndoDiffItem', { diffId: outVertex.id, state: true });
                            }
                        } else {
                            deps.forEach(function(diffId) {
                                self.trigger('markUndoDiffItem', { diffId: diffId, state: true });
                            })
                        }
                    } else {
                        if (inVertex) {
                            self.trigger('markUndoDiffItem', { diffId: inVertex.id, state: false });
                        }

                        if (outVertex) {
                            self.trigger('markUndoDiffItem', { diffId: outVertex.id, state: false });
                        }
                    }

                    break;

                default: console.warn('Unknown diff item type', diff.type)
            }
        };

        this.onMarkPublish = function(event, data) {
            var self = this,
                diffId = data.diffId,
                diff = this.diffsById[diffId],
                state = data.state,
                stateBasedClassFunction = state ? 'addClass' : 'removeClass',
                inverseStateBasedClassFunction = !state ? 'addClass' : 'removeClass';

            if (!diff) {
                return;
            }

            this.$node.find('tr.' + F.className.to(diff.id)).each(function() {
                $(this)
                    .removePrefixedClasses('mark-')
                    [stateBasedClassFunction]('mark-publish')
                    .find('button.publish')[stateBasedClassFunction]('btn-success')
                    .siblings('button.undo').removeClass('btn-danger');
            });

            this.updateHeader();

            switch (diff.type) {

                case 'VertexDiffItem':
                    if (state && diff.deleted) {
                        this.diffDependencies[diff.id].forEach(function(diffId) {
                            var diff = self.diffsById[diffId];
                            if (diff && diff.type === 'EdgeDiffItem' && diff.deleted) {
                                self.trigger('markPublishDiffItem', { diffId: diffId, state: true });
                            } else {
                                self.trigger('markPublishDiffItem', { diffId: diffId, state: false });
                            }
                        });
                    } else if (!state) {
                        this.diffDependencies[diff.id].forEach(function(diffId) {
                            self.trigger('markPublishDiffItem', { diffId: diffId, state: false });
                        });
                    }

                    break;

                case 'PropertyDiffItem':

                    if (state) {
                        var vertexDiff = this.diffsForElementId[diff.elementId];
                        if (vertexDiff && !vertexDiff.deleted) {
                            this.trigger('markPublishDiffItem', { diffId: diff.elementId, state: true })
                        }
                    }

                    break;

                case 'EdgeDiffItem':

                    if (!state) {
                        // Unpublish all dependents
                        this.diffDependencies[diff.id].forEach(function(diffId) {
                            self.trigger('markPublishDiffItem', { diffId: diffId, state: false });
                        });
                    } else {
                        var inVertexDiff = this.diffsForElementId[diff.inVertexId],
                            outVertexDiff = this.diffsForElementId[diff.outVertexId];

                        if (inVertexDiff && !diff.deleted) {
                            this.trigger('markPublishDiffItem', { diffId: diff.inVertexId, state: true });
                        }
                        if (outVertexDiff && !diff.deleted) {
                            this.trigger('markPublishDiffItem', { diffId: diff.outVertexId, state: true });
                        }
                    }

                    break;

                default: console.warn('Unknown diff item type', diff.type)
            }
        };
    }
});
