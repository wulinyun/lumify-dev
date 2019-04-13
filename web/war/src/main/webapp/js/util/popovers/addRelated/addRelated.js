define([
    'flight/lib/component',
    'util/ontology/conceptSelect',
    'util/ontology/relationshipSelect',
    'util/withFormFieldErrors',
    'util/withDataRequest',
    'util/vertex/formatters',
    '../withPopover'
], function(
    defineComponent,
    ConceptSelector,
    RelationshipSelector,
    withFormFieldErrors,
    withDataRequest,
    F,
    withPopover) {
    'use strict';

    return defineComponent(AddRelatedPopover, withPopover, withFormFieldErrors, withDataRequest);

    function AddRelatedPopover() {

        this.defaultAttrs({
            addButtonSelector: '.add',
            searchButtonSelector: '.search',
            cancelButtonSelector: '.cancel',
            promptAddButtonSelector: '.prompt-add'
        });

        this.before('initialize', function(node, config) {
            if (config.vertex) {
                if (config.vertex.length  > 1) {
                    config.title = i18n('popovers.add_related.title_multiple', config.vertex.length);
                } else {
                    config.title = '"' + F.vertex.title(config.vertex[0]) + '"';
                }
            } else {
                console.warn('vertex attribute required');
                config.title = i18n('popovers.add_related.title_unknown');
            }
            config.template = 'addRelated/template';
        });
        this.after('initialize', function() {
            var self = this;

            this.after('setupWithTemplate', function() {
                this.on(this.popover, 'conceptSelected', this.onConceptSelected);
                this.on(this.popover, 'relationshipSelected', this.onRelationshipSelected);
                this.on(this.popover, 'click', {
                    addButtonSelector: this.onAdd,
                    cancelButtonSelector: this.onCancel,
                    searchButtonSelector: this.onSearch,
                    promptAddButtonSelector: this.onPromptAdd
                });

                this.enterShouldSubmit = 'addButtonSelector';

                ConceptSelector.attachTo(self.popover.find('.concept'), {
                    focus: true,
                    defaultText: i18n('popovers.add_related.concept.default_text'),
                    limitRelatedToConceptId: F.vertex.prop(
                        _.isArray(this.attr.vertex) ? this.attr.vertex[0] : this.attr.vertex,
                        'conceptType'
                    )
                });

                RelationshipSelector.attachTo(self.popover.find('.relationship'), {
                    defaultText: i18n('popovers.add_related.relationship.default_text')
                });

                this.positionDialog();
            });
        });
        this.onRelationshipSelected = function(event, data) {
            this.relationshipId = data.relationship && data.relationship.title;
            this.checkValid();
        };
        this.onConceptSelected = function(event, data) {
            this.conceptId = data.concept && data.concept.id;
            this.checkValid();
            this.trigger(this.popover.find('.relationship'), 'limitParentConceptId', {
                conceptId: this.conceptId
            });
        };

        this.checkValid = function() {
            var searchButton = this.popover.find('.search').hide(),
                promptAdd = this.popover.find('.prompt-add').hide(),
                cancelButton = this.popover.find('.cancel').show(),
                addButton = this.popover.find('.add');

            if (this.relatedRequest && this.relatedRequest.cancel) {
                this.relatedRequest.cancel();
            }
            this.clearFieldErrors(this.popover);
            searchButton.hide();
            promptAdd.hide();
            cancelButton.hide();
            addButton.show();
        };
        this.onSearch = function(event) {
            this.trigger(document, 'searchByRelatedEntity', {
                vertexIds: this.attr.relatedToVertexIds,
                conceptId: this.conceptId,
                relationshipId: this.relationshipId
            });
            this.teardown();
        };

        this.onPromptAdd = function(event) {
            var self = this;
            this.trigger('updateWorkspace', {
                options: {
                    selectAll: true
                },
                entityUpdates: this.promptAddVertices.map(function(vertex) {
                    return {
                        vertexId: vertex.id,
                        graphLayoutJson: {
                            relatedToVertexId: self.attr.relatedToVertexIds[0]
                        }
                    };
                })
            });
            this.teardown();
        };

        this.onCancel = function() {
            if (this.relatedRequest) {
                this.relatedRequest.abort();
            }
        };

        this.onAdd = function(event) {
            var self = this,
                searchButton = this.popover.find('.search').hide(),
                promptAdd = this.popover.find('.prompt-add').hide(),
                cancelButton = this.popover.find('.cancel').show(),
                button = $(event.target).addClass('loading').attr('disabled', true);

            Promise.all([
                this.dataRequest('config', 'properties'),
                (
                    this.relatedRequest = this.dataRequest('vertex', 'related', this.attr.relatedToVertexIds, {
                        limitEdgeLabel: this.relationshipId,
                        limitParentConceptId: this.conceptId
                    })
                )
            ])
                .finally(function() {
                    button.removeClass('loading').removeAttr('disabled');
                    searchButton.hide();
                    promptAdd.hide();
                    cancelButton.hide();
                    self.clearFieldErrors(self.popover);
                })
                .then(function(results) {
                    var config = results.shift(),
                        related = results.shift(),
                        count = related.count,
                        vertices = related.vertices,
                        forceSearch = count > config['vertex.loadRelatedMaxForceSearch'],
                        promptBeforeAdding = count > config['vertex.loadRelatedMaxBeforePrompt'];

                    if (count === 0) {
                        self.markFieldErrors(i18n('popovers.add_related.no_vertices'), self.popover);
                    } else if (forceSearch) {
                        self.markFieldErrors(i18n('popovers.add_related.too_many'), self.popover);
                        button.hide();
                        searchButton.show();
                    } else if (promptBeforeAdding) {
                        button.hide();
                        searchButton.show();
                        self.promptAddVertices = vertices;
                        promptAdd.text(i18n('popovers.add_related.button.prompt_add', count)).show();
                    } else {
                        _.defer(function() {
                            self.trigger('updateWorkspace', {
                                options: {
                                    selectAll: true
                                },
                                entityUpdates: vertices.map(function(vertex) {
                                    return {
                                        vertexId: vertex.id,
                                        graphLayoutJson: {
                                            relatedToVertexId: self.attr.relatedToVertexIds[0]
                                        }
                                    };
                                })
                            });
                        });
                        self.teardown();
                    }

                })
                .catch(function(error) {
                    console.warn("Oh no add related error!!!!!!", error);
                    self.markFieldErrors(i18n('popovers.add_related.error'));
                })
        };
    }
});
