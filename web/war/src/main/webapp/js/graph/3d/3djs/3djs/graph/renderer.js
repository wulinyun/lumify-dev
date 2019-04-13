
define([
    'three',
    '../three-plugins/TrackballControls',
    './layout/force-directed'
], function(THREE, TrackballControls, ForceDirectedLayout) {

    function GraphRenderer(domElement, options) {
        this.domElement = domElement;
        this.options = options || {};

        this.browserSupported = (function() {
            try {
                var canvas = document.createElement('canvas');
                return !!window.WebGLRenderingContext && (
                    canvas.getContext('webgl') || canvas.getContext('experimental-webgl')
                );
            } catch(e) {
                return false;
            }
        })();
    }

    GraphRenderer.prototype = {
        addEventListener: THREE.EventDispatcher.prototype.addEventListener,
        hasEventListener: THREE.EventDispatcher.prototype.hasEventListener,
        removeEventListener: THREE.EventDispatcher.prototype.removeEventListener,
        dispatchEvent: THREE.EventDispatcher.prototype.dispatchEvent
    };

    GraphRenderer.prototype.renderGraph = function(graph) {
        this.graph = graph;

        this._init();
        this._setupScene();
        this._setupLayout();
    };

    GraphRenderer.prototype.teardown = function() {
        this.running = false;

        this._renderer.context.viewport(0,0,1,1)

        this._pickingTexture.dispose();
        if (this._pickingParticleSystem) {
            this._pickingParticleSystem.geometry.dispose();
            this._pickingParticleSystem.material.dispose();
        }

        this._renderer.clear();

        var scene = this._scene;
        scene.children.forEach(function(n) {
            if (n.geometry) n.geometry.dispose();
            if (n.material) n.material.dispose();
            scene.remove(n);
        });

        scene = this._pickingScene;
        scene.children.forEach(function(n) {
            if (n.geometry) n.geometry.dispose();
            if (n.material) n.material.dispose();
            scene.remove(n);
        });

        scene.remove(this._camera);

        this._camera = null;
        this._renderer = null;
        this._controls = null;
        this._pickingParticleSystem = null;
        this._scene = null;
        this._pickingScene = null;

        this._pickingData = [];

        this._layout.stopCalculating();
        this.teardownEvents();
    };

    GraphRenderer.prototype._init = function() {
        var width = this.domElement.offsetWidth,
            height = this.domElement.offsetHeight,
            camera = new THREE.PerspectiveCamera(50, width / height, 100, 10000),
            renderer = new THREE.WebGLRenderer({ antialias: true }),
            controls = new THREE.TrackballControls(camera, renderer.domElement),
            projector = new THREE.Projector(),
            mouse = { };

        renderer.setSize(width, height);
        renderer.sortObjects = false;
        renderer.setClearColor(0xffffff);

        this._renderer = renderer;
        this._camera = camera;
        this._controls = controls;

        this._pickingTexture = new THREE.WebGLRenderTarget(width, height);
        this._pickingTexture.generateMipmaps = false;
        this._pickingData = [];

        this._mouse = mouse;

        controls.rotateSpeed = 1.0;
        controls.zoomSpeed = 0.8;
        controls.panSpeed = 0.8;
        controls.noZoom = false;
        controls.noPan = false;
        controls.staticMoving = true;
        controls.dynamicDampingFactor = 0.3;
        controls.maxDistance = 8000;

        var self = this;

        this.domElement.appendChild(renderer.domElement);

        renderer.domElement.addEventListener('mousemove', moveHandler);
        renderer.domElement.addEventListener('mousedown', downHandler);
        renderer.domElement.addEventListener('mouseup', upHandler);
        renderer.domElement.addEventListener('click', clickHandler);
        renderer.domElement.addEventListener('mousewheel', wheelHandler);
        renderer.domElement.addEventListener('DOMMouseScroll', wheelHandler);

        windowResizeHandler = _.throttle(windowResizeHandler, 250);
        window.addEventListener('resize', windowResizeHandler, false);

        self.teardownEvents = function() {
            renderer.domElement.removeEventListener('mousemove', moveHandler);
            renderer.domElement.removeEventListener('mousedown', downHandler);
            renderer.domElement.removeEventListener('mouseup', upHandler);
            renderer.domElement.removeEventListener('click', clickHandler);
            renderer.domElement.removeEventListener('mousewheel', wheelHandler);
            renderer.domElement.removeEventListener('DOMMouseScroll', wheelHandler);
            window.removeEventListener('resize', windowResizeHandler);
            controls.teardown();
        };

        self.triggerMouseMoving = function() {
            if (self.mousemoving === false) {
                self.continueAnimation();
            }
            self.mousemoving = true;
            clearTimeout(self.mousemovingTimeout);
            self.mousemovingTimeout = _.delay(function() {
                self.mousemoving = false;
            }, 250)

        };

        function wheelHandler() {
            self.triggerMouseMoving();
        }

        function moveHandler(e) {
            mouse.x = e.pageX - self.domElement.offsetLeft;
            mouse.y = e.pageY - self.domElement.offsetTop;

            self.triggerMouseMoving();

            var dragging = self.dragging;

            if (dragging) {

                var x = (e.clientX / width) * 2 - 1,
                    y = -(e.clientY / height) * 2 + 1,
                    vector = new THREE.Vector3(x, y, 0.5);

                projector.unprojectVector(vector, camera);

                var dir = vector.sub(camera.position).normalize(),
                    distance = -camera.position.z / dir.z,
                    pos = camera.position.clone().add(dir.multiplyScalar(distance));

                dragging.layout = {};
                dragging.position.x = pos.x;
                dragging.position.y = pos.y;

                self._layout.recalculate();
            }
        }

        function downHandler(e) {
            self.triggerMouseMoving();
            self.mousedown = true;
        }
        function upHandler(e) {
            self.triggerMouseMoving();
            if (self.dragging) {
                self.graph.removeNode(self.dragging);
                self.dragging = undefined;
            }
            controls.noZoom = controls.noRotate = controls.noZoom = false;
            self.mousedown = false;
            //self.dispatchEvent( { type: 'node_mouseup', content: self.currentNodeId } );
        }
        function clickHandler(e) {
            self.triggerMouseMoving();
            controls.noZoom = controls.noRotate = controls.noZoom = false;
            self.mousedown = false;
            self.checkPick = true;
        }
        function windowResizeHandler() {
            var el = $(self.domElement),
                width = el.width(),
                height = el.height();

            self.triggerMouseMoving();
            camera.aspect = width / height;
            camera.updateProjectionMatrix();

            if (self._pickingTexture) {
                self._pickingTexture.dispose();
            }
            self._pickingTexture = new THREE.WebGLRenderTarget(width, height);
            self._pickingTexture.generateMipmaps = false;

            renderer.setSize(width, height);
        }
    };

    GraphRenderer.prototype._updateGeometry = function() {
        if (this._pickingParticleSystem) {
            this._pickingParticleSystem.geometry.verticesNeedUpdate = true;
        }

        if (this._lines) {
            this._lines.geometry.verticesNeedUpdate = true;
        }
    };

    GraphRenderer.prototype.addToRenderLoop = function() {
        var renderer = this._renderer,
            scene = this._scene,
            camera = this._camera,
            controls = this._controls,
            mouse = this._mouse,
            pickedId = null,
            self = this;

        this.running = true;

        function pick() {
            if (!mouse.x || !mouse.y) {
                return;
            }
            renderer.render(self._pickingScene, camera, self._pickingTexture);

            var gl = renderer.getContext(),
                //read the pixel under the mouse from the texture
                pixelBuffer = new Uint8Array(4);

            gl.readPixels(
                mouse.x,
                self._pickingTexture.height - mouse.y,
                1,
                1,
                gl.RGBA,
                gl.UNSIGNED_BYTE,
                pixelBuffer
            );

            //interpret the pixel as an ID
            var id = (pixelBuffer[0] << 16) | (pixelBuffer[1] << 8) | (pixelBuffer[2]);
            if (id !== pickedId) {
                pickedId = id;
                var nodeId = self._pickingData[ id ];
                if (nodeId) {
                    self.currentNodeId = nodeId;
                    self.dispatchEvent({
                        type: 'node_click',
                        content: self.currentNodeId
                    });
                } else {
                    self.currentNodeId = undefined;
                    self.dispatchEvent({
                        type: 'node_click',
                        content: null
                    });
                }
            }
        }

        this.continueAnimation = function() {
            requestAnimationFrame(render);
        };

        function render() {
            if (!self.running) return;

            if (self.mousemoving || !self._layout.finished) {
                requestAnimationFrame(render);
            }

            var needsUpdateGeometry = false;
            if (!self._layout.finished) {
                self._layout.generate();
                needsUpdateGeometry = true;
            }

            if (self.dragging) {
                needsUpdateGeometry = true;
                self.dragging.layout = {};
            }

            controls.update();

            if (self.graph.needsUpdate) {
                self._handleGraphNeedsUpdate();
                self.graph.needsUpdate = false;
                self._updateGeometry();
            } else if (needsUpdateGeometry) {
                self._updateGeometry();
            }

            if (!self.mousedown && self.checkPick) {
                pick();
                self.checkPick = false;
            }

            renderer.render(self._scene, camera);

            if (self.stats) {
                self.stats.update();
            }
        }
        render();
    };

    GraphRenderer.prototype._handleGraphNeedsUpdate = function() {
        var nodes = this.graph.nodes,
            len = nodes.length,
            pickingData = this._pickingData || [],
            pickingGeometry;

        pickingData.length = 0;
        pickingGeometry = new THREE.Geometry();

        for (var i = 0; i < len; i++) {
            var node = nodes[i],
                vertex = node.position;

            if (!vertex) {
                vertex = new THREE.Vector3();
                vertex.x = 500 * Math.random() - 250;
                vertex.y = 500 * Math.random() - 250;
                vertex.z = 500 * Math.random() - 250;
                node.position = vertex;
            }

            if (node._sprite && node.needsRemove) {
                this._scene.remove(node._sprite);
            } else if (node._sprite && node.needsUpdate) {
                this._scene.remove(node._sprite);
                node._hasline = false;
                this._scene.add(this._spriteForNode(node));
            } else if (!node._sprite || node.needsUpdate) {
                this._scene.add(this._spriteForNode(node));
            }
            node.needsUpdate = false;

            if (!node.needsRemove) {
                pickingGeometry.vertices.push(vertex);

                pickingGeometry.colors.push(new THREE.Color(i));
                pickingData[i] = node.id;
            }
        }

        if (this._pickingParticleSystem) {
            this._pickingScene.remove(this._pickingParticleSystem);
        }
        var pickingMaterial = new THREE.ParticleBasicMaterial({
            size: 200,
            vertexColors: true
        });

        this._pickingParticleSystem = new THREE.ParticleSystem(
            pickingGeometry,
            pickingMaterial
        );
        this._pickingScene.add(this._pickingParticleSystem);

        var edges = this.graph.edges,
            edgesLength = edges.length,
            geometry = new THREE.Geometry();

        // TODO: reuse line
        if (this._lines) {
            this._scene.remove(this._lines);
            this._lines = null;
        }
        if (edgesLength) {
            this._lines = new THREE.Line(
                geometry,
                new THREE.LineBasicMaterial({ color: 0xBBBBBB }),
                THREE.LinePieces
            );
            for (var edgeIndex = 0; edgeIndex < edgesLength; edgeIndex++) {
                var edge = edges[edgeIndex];

                if (!edge.source.needsRemove && !edge.target.needsRemove) {
                    geometry.vertices.push(edge.source.position);
                    geometry.vertices.push(edge.target.position);
                }
            }
            this._scene.add(this._lines);
        }

        this._layout.init();
        this._layout.recalculate();
    };

    GraphRenderer.prototype._spriteForNode = function(node) {
        THREE.ImageUtils.crossOrigin = undefined;

        var texture = THREE.ImageUtils.loadTexture(node.data.icon);
        texture.needsUpdate = true;

        var spriteMaterial = new THREE.SpriteMaterial({
                map: texture,
                transparent: true
            }),
            sprite = new THREE.Sprite(spriteMaterial);

        sprite.name = node.data.label;
        sprite.scale.set(node.data.iconWidth, node.data.iconHeight, 1.0);
        sprite.position = node.position;

        var canvas = document.createElement('canvas');
        canvas.width = 600;
        canvas.height = node.data.iconHeight + 125;

        var context = canvas.getContext('2d'),
            fontsize = 40;

        context.font = fontsize + 'px Helvetica';

        var metrics = context.measureText(node.data.label),
            textWidth = metrics.width;

        context.fillStyle = 'rgba(128, 128, 128, 1.0)';
        context.fillText(node.data.label, canvas.width / 2 - textWidth / 2, canvas.height - fontsize * 0.5);

        var textTexture = new THREE.Texture(canvas);
        textTexture.needsUpdate = true;

        var textSpriteMaterial = new THREE.SpriteMaterial({
                map: textTexture,
                color: 0xffffff
            }),
            textSprite = new THREE.Sprite(textSpriteMaterial);

        textSprite.scale.set(canvas.width, canvas.height, 1.0);
        textSprite.position = new THREE.Vector3(0, 0, 0);
        sprite.add(textSprite);

        node._sprite = sprite;

        return sprite;
    };

    GraphRenderer.prototype._setupScene = function() {

        this._scene = new THREE.Scene();
        this._pickingScene = new THREE.Scene();

        this._camera.position.z = 2400;
    };

    GraphRenderer.prototype._setupLayout = function() {
        this._layout = new ForceDirectedLayout(this.graph, {
            iterations: 5000,
            attraction: 2,
            repulsion: 30,
            width: this.domElement.offsetWidth * 0.1,
            height: this.domElement.offsetHeight * 0.1,
            layout: '3d'
        });

        this._layout.init();
    };

    GraphRenderer.prototype.showStats = function() {
        var self = this;

        require(['three-stats'], function(Stats) {
            var stats = new Stats();
            stats.domElement.style.position = 'absolute';
            stats.domElement.style.top = '0px';
            self.domElement.appendChild(stats.domElement);
            self.stats = stats;
        });
    };

    return GraphRenderer;
});
